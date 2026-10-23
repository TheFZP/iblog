package com.iblog.module.paper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.iblog.config.IblogProperties;
import com.iblog.module.paper.entity.PaperAccount;
import com.iblog.module.paper.entity.PaperSignal;
import com.iblog.module.paper.entity.PaperStrategy;
import com.iblog.module.paper.mapper.PaperSignalMapper;
import com.iblog.module.paper.support.Indicators;
import com.iblog.module.paper.support.LotRule;
import com.iblog.module.paper.support.PaperConstants;
import com.iblog.module.paper.support.PaperNumbers;
import com.iblog.module.paper.support.PaperScreenRules;
import com.iblog.module.paper.support.PaperStrategyParams;
import com.iblog.module.paper.support.TradeCalendar;
import com.iblog.module.stock.model.HotStockItem;
import com.iblog.module.stock.model.IntradayData;
import com.iblog.module.stock.model.KlineItem;
import com.iblog.module.stock.model.MoneyFlow;
import com.iblog.module.stock.model.StockQuote;
import com.iblog.module.stock.service.EastmoneyService;
import com.iblog.module.stock.service.HotListService;
import com.iblog.module.stock.service.StockFundamentalService;
import com.iblog.module.stock.service.TencentService;
import com.iblog.module.stock.support.StockCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自动选股：从热榜取候选池，用日 K + 资金流打分，命中阈值的写入 paper_signal。
 *
 * <p>为什么从热榜出发而不是全市场扫描：全市场 5000+ 只逐个拉日 K 会直接把上游打限流，
 * 而热榜本身已经过滤了一轮关注度；先把「热门 × 技术形态 × 资金流」跑通，
 * 再考虑扩池（P3 回测会给出扩池是否有价值的数据）。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaperSelectionService {

    /** 打分至少需要的日 K 根数 */
    private static final int MIN_BARS = 60;

    /**
     * 每次选股最多做多少次分时校验。
     *
     * <p>分时接口只能单只查询，候选池 40 只全查会把一次刷新拖到几十秒；
     * 这里只对「靠分时加分刚好能越线」的候选按扫描顺序校验前 15 只，
     * 漏掉的票最多少一个加分项，不至于因为限流把整轮选股拖垮。</p>
     */
    private static final int MAX_VWAP_CHECKS = 15;

    /**
     * 每次选股最多拉多少只的两年深历史（约 500 根日 K）。
     *
     * <p>深历史不能走 120 根的常规缓存，单只回源更慢；只对「靠新高加分刚好能越线」
     * 的候选按扫描顺序拉前 10 只，和分时校验同样的思路。</p>
     */
    private static final int MAX_DEEP_CHECKS = 10;

    /**
     * 每次选股最多让基本面服务真实请求上游多少次（自由流通股/股东户数/盈利）。
     *
     * <p>基本面走东财数据中心，一只票最多 3~4 个请求，全池（universeSize 上百只）
     * 一次扫完是分钟级；所以给预算。但预算必须按「真实上游请求数」扣，
     * 不能按候选只数扣：{@link StockFundamentalService} 有 6 小时缓存，
     * 第二轮起命中缓存是零成本，按只数扣会让整池报「预算用完」。
     * 首次全扫约 200 次请求，之后同一天基本走缓存。</p>
     */
    private static final int MAX_FUNDAMENTAL_CHECKS = 240;

    private final HotListService hotListService;
    private final KlineCacheService klineCacheService;
    private final EastmoneyService eastmoneyService;
    private final StockFundamentalService fundamentalService;
    private final TencentService tencentService;
    private final PaperQuoteService quoteService;
    private final PaperSignalMapper signalMapper;
    private final PaperStrategyService strategyService;
    private final PaperAccountService accountService;
    private final IblogProperties properties;
    private final TradeCalendar calendar;
    private final ObjectMapper objectMapper;

    /** 每个账户最近一次选股时间，避免自动交易每 15 秒重扫一遍。 */
    private final Map<Long, Long> lastRefreshAt = new ConcurrentHashMap<>();

    /** 重新选股并覆盖当日候选（手动刷新/定时刷新都走这里）。 */
    public List<PaperSignal> refresh(Long accountId) {
        PaperStrategy strategy = strategyService.ensureDefault(accountId);
        PaperStrategyParams params = PaperStrategyParams.parse(objectMapper, strategy.getParams());
        String today = calendar.todayText();
        PaperAccount account = null;
        try {
            account = accountService.getById(accountId);
        } catch (RuntimeException ex) {
            log.debug("选股：账户读取失败 account={} msg={}", accountId, ex.getMessage());
        }

        List<HotStockItem> hot = List.of();
        try {
            hot = hotListService.fetchHotList();
        } catch (RuntimeException ex) {
            log.warn("选股：热榜获取失败 {}", ex.getMessage());
        }

        List<Candidate> selected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int scanned = 0;
        int[] vwapBudget = {MAX_VWAP_CHECKS};
        int[] deepBudget = {MAX_DEEP_CHECKS};
        int[] fundamentalBudget = {fundamentalService.upstreamRequests()};
        for (HotStockItem item : hot) {
            if (scanned >= params.getUniverseSize()) {
                break;
            }
            String code = StockCode.normalize(item.getCode());
            if (code.isEmpty() || StockCode.isHk(code) || !seen.add(code)) {
                continue;
            }
            String name = item.getName() == null ? "" : item.getName();
            // 「非 ST」是硬条件，无论是否开严格模式都成立
            if (name.toUpperCase().contains("ST")) {
                continue;
            }
            scanned++;
            try {
                Candidate candidate = evaluate(strategy, params, code, name, item,
                        account, vwapBudget, deepBudget, fundamentalBudget);
                if (candidate != null) {
                    selected.add(candidate);
                }
            } catch (RuntimeException ex) {
                log.warn("选股评分失败 code={} msg={}", code, ex.getMessage());
            }
        }

        // 用户要的「现价与一年内最低价比从小到大」：开了按低价比升序就以此为主排序，
        // 同比值时再用分数兜底；没开则维持原来的分数降序
        if (params.isSortByLowRatioAsc()) {
            selected.sort((a, b) -> {
                double ra = a.lowRatio();
                double rb = b.lowRatio();
                boolean na = Double.isNaN(ra);
                boolean nb = Double.isNaN(rb);
                if (na != nb) {
                    return na ? 1 : -1;
                }
                if (!na) {
                    int cmp = Double.compare(ra, rb);
                    if (cmp != 0) {
                        return cmp;
                    }
                }
                return Integer.compare(b.signal().getScore(), a.signal().getScore());
            });
        } else {
            selected.sort(Comparator.comparingInt((Candidate c) -> c.signal().getScore()).reversed());
        }
        int limit = Math.max(1, params.getMaxCandidates());
        if (selected.size() > limit) {
            selected = new ArrayList<>(selected.subList(0, limit));
        }

        signalMapper.delete(Wrappers.<PaperSignal>lambdaQuery()
                .eq(PaperSignal::getAccountId, accountId)
                .eq(PaperSignal::getAction, PaperConstants.ACTION_CANDIDATE)
                .eq(PaperSignal::getTradeDate, today));
        List<PaperSignal> result = new ArrayList<>(selected.size());
        for (Candidate candidate : selected) {
            PaperSignal signal = candidate.signal();
            signal.setAccountId(accountId);
            signal.setStrategyId(strategy.getId());
            signal.setTradeDate(today);
            signalMapper.insert(signal);
            result.add(signal);
        }
        strategyService.touch(strategy);
        lastRefreshAt.put(accountId, System.currentTimeMillis());
        log.info("模拟盘选股完成 account={} 扫描={} 命中={}", accountId, scanned, result.size());
        return result;
    }

    /** 候选 + 低价比，用于「按低价比升序」排序（PaperSignal 不落库这个字段）。 */
    private record Candidate(PaperSignal signal, double lowRatio) {
    }

    /** 候选池为空/太旧时按最小间隔重扫，自动交易每 tick 调用。 */
    public List<PaperSignal> refreshIfStale(Long accountId) {
        PaperStrategy strategy = strategyService.ensureDefault(accountId);
        PaperStrategyParams params = PaperStrategyParams.parse(objectMapper, strategy.getParams());
        long intervalMs = Math.max(30, params.getSelectionRefreshSec()) * 1000L;
        Long at = lastRefreshAt.get(accountId);
        if (at != null && System.currentTimeMillis() - at < intervalMs) {
            return recentCandidates(accountId);
        }
        return refresh(accountId);
    }

    public List<PaperSignal> recentCandidates(Long accountId) {
        return recent(accountId, calendar.todayText());
    }

    /** 指定交易日的候选，按分数从高到低。 */
    public List<PaperSignal> recent(Long accountId, String date) {
        String day = (date == null || date.isBlank()) ? calendar.todayText() : date.trim();
        return signalMapper.selectList(Wrappers.<PaperSignal>lambdaQuery()
                .eq(PaperSignal::getAccountId, accountId)
                .eq(PaperSignal::getTradeDate, day)
                .eq(PaperSignal::getAction, PaperConstants.ACTION_CANDIDATE)
                .orderByDesc(PaperSignal::getScore)
                .orderByAsc(PaperSignal::getId));
    }

    /** 账户全部信号（含买卖），用于页面复盘。 */
    public List<PaperSignal> history(Long accountId, int limit) {
        return signalMapper.selectList(Wrappers.<PaperSignal>lambdaQuery()
                .eq(PaperSignal::getAccountId, accountId)
                .orderByDesc(PaperSignal::getId)
                .last("limit " + Math.min(Math.max(limit, 1), 200)));
    }

    // ---------- 打分 ----------

    private Candidate evaluate(PaperStrategy strategy, PaperStrategyParams params, String code, String name,
                               HotStockItem hot, PaperAccount account,
                               int[] vwapBudget, int[] deepBudget, int[] fundamentalBudget) {
        List<KlineItem> bars = klineCacheService.load(code, MIN_BARS);
        if (bars.size() < 30) {
            return null;
        }
        double[] closes = new double[bars.size()];
        double[] volumes = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            closes[i] = bars.get(i).getClose();
            volumes[i] = bars.get(i).getVolume();
        }
        double close = closes[closes.length - 1];
        if (close <= 0) {
            return null;
        }
        // 连板高度：配了上限就作为硬条件（一进二严格只做昨日首板），不满足直接剔除
        int boardHeight = PaperScreenRules.boardHeight(bars, code, name);
        if (params.getBoardHeightMax() > 0
                && (boardHeight < 1 || boardHeight > params.getBoardHeightMax())) {
            return null;
        }
        double ma5 = Indicators.sma(closes, 5);
        double ma10 = Indicators.sma(closes, 10);
        double ma20 = Indicators.sma(closes, 20);
        Indicators.Macd macd = Indicators.macd(closes);
        double rsi = Indicators.rsi(closes, 14);
        double volumeRatio = Indicators.volumeRatio(volumes, 5);

        StockQuote quote = null;
        try {
            quote = quoteService.quote(code);
        } catch (RuntimeException ignored) {
            // 盘中取不到实时价不影响用日 K 打分
        }
        double changePct = quote != null && quote.getPrice() > 0 ? quote.getChangePct() : 0;

        MoneyFlow flow = null;
        try {
            flow = eastmoneyService.fetchMoneyFlow(code);
        } catch (RuntimeException ex) {
            log.debug("选股：资金流获取失败 code={} msg={}", code, ex.getMessage());
        }

        // ---------- 用户自定义硬条件（严格过滤）：不满足直接剔除，不参与打分 ----------
        // 早盘放量/站上均价两项都要分时，先拉一次共用，避免重复请求
        IntradayData intraday = null;
        if (params.getOpenTurnoverMinPct() > 0 || params.isRequireAboveVwap()) {
            try {
                intraday = tencentService.fetchIntraday(code);
            } catch (RuntimeException ex) {
                log.debug("选股：分时获取失败 code={} msg={}", code, ex.getMessage());
            }
        }
        // 现价/一年内最低价：严格过滤和「按低价比升序」都要用，只算一次
        double lowRatio = computeLowRatio(params, code, bars, deepBudget);
        PaperScreenRules.Technicals technicals =
                new PaperScreenRules.Technicals(volumeRatio, ma5, ma20,
                        PaperScreenRules.volumeTrendRatio(bars, 5, 20));
        if (params.isStrictFilters()) {
            String reject = strictReject(params, code, name, bars, quote, intraday, account,
                    lowRatio, technicals, fundamentalBudget);
            if (reject != null) {
                log.debug("选股：硬条件剔除 code={} name={} 原因={}", code, name, reject);
                return null;
            }
        }

        int score = 0;
        List<String> reasons = new ArrayList<>();
        if (close > ma5 && ma5 > ma10 && ma10 > ma20) {
            score += params.getMaTrendScore();
            reasons.add("均线多头");
        } else if (close > ma20) {
            score += params.getMa20Score();
            reasons.add("站上20日线");
        }
        if (!Double.isNaN(macd.hist()) && macd.hist() > 0 && macd.dif() > macd.dea()) {
            score += params.getMacdScore();
            reasons.add("MACD红柱");
        }
        if (!Double.isNaN(volumeRatio) && volumeRatio >= params.getVolumeRatioMin()) {
            score += params.getVolumeRatioScore();
            reasons.add(String.format("量比 %.1f", volumeRatio));
        }
        if (flow != null && !flow.isUnavailable() && flow.getMainNetInflow() > 0) {
            score += params.getMoneyFlowScore();
            reasons.add("主力净流入");
            if (flow.getMainNetPct() >= params.getMoneyFlowPctMin()) {
                score += params.getMoneyFlowPctScore();
            }
        }
        if (!Double.isNaN(rsi) && rsi >= params.getRsiMin() && rsi <= params.getRsiMax()) {
            score += params.getRsiScore();
            reasons.add(String.format("RSI %.0f", rsi));
        }
        if (changePct > params.getChangeMinPct() && changePct < params.getChangeMaxPct()) {
            score += params.getChangeScore();
            reasons.add("当日温和上涨");
        }
        if (hot != null && hot.getHotRankChg() > 0) {
            score += params.getHotRankScore();
            reasons.add("热度上升");
        }
        // 换手率：活跃但不过热
        if (quote != null && PaperScreenRules.inRange(quote.getTurnoverRate(),
                params.getTurnoverMinPct(), params.getTurnoverMaxPct())) {
            score += params.getTurnoverScore();
            reasons.add(String.format("换手率 %.1f%%", quote.getTurnoverRate()));
        }
        // 流通市值：盘子适中，短线资金进出才方便
        if (quote != null && PaperScreenRules.inRange(quote.getFloatMarketCap(),
                params.getFloatCapMinYi(), params.getFloatCapMaxYi())) {
            score += params.getFloatCapScore();
            reasons.add(String.format("流通市值 %.0f 亿", quote.getFloatMarketCap()));
        }
        // 涨停记忆：近 N 个交易日出现过涨停，隔日更容易吸引跟风盘
        if (PaperScreenRules.hasLimitUp(bars, params.getLimitUpWithinDays(), code, name)) {
            score += params.getLimitUpScore();
            reasons.add(params.getLimitUpWithinDays() + " 日内有涨停");
        }
        // 连板结构：昨日首板额外加分，并在理由里写清是一板还是几板
        if (params.getBoardHeightMax() > 0) {
            reasons.add(boardHeight == 1 ? "昨日首板" : "昨日 " + boardHeight + " 连板");
        }
        if (params.getFirstBoardScore() > 0 && boardHeight == 1) {
            score += params.getFirstBoardScore();
            if (params.getBoardHeightMax() <= 0) {
                reasons.add("昨日首板");
            }
        }
        // 缩量上涨：日 K 就能判断，不额外请求
        if (params.getShrinkVolumeScore() > 0
                && PaperScreenRules.shrinkVolumeRise(bars, params.getShrinkVolumeMaxRatio())) {
            score += params.getShrinkVolumeScore();
            reasons.add("缩量上涨");
        }
        // 两年新高：需要约 500 根日 K，按预算只对可能越线的候选拉深历史
        if (params.getNewHighScore() > 0
                && score + params.getNewHighScore() >= params.getMinScore()
                && deepBudget != null && deepBudget[0] > 0) {
            deepBudget[0]--;
            try {
                List<KlineItem> deep = klineCacheService.deepLoad(code,
                        PaperScreenRules.TWO_YEAR_BARS + params.getNewHighWithinDays());
                // 深历史拉取失败时会退回到 120 根常规缓存，这时不能冒充「两年新高」
                boolean enough = deep != null && deep.size() >= 250;
                if (enough && PaperScreenRules.newHighWithin(deep, params.getNewHighWithinDays(),
                        PaperScreenRules.TWO_YEAR_BARS)) {
                    score += params.getNewHighScore();
                    reasons.add("近 " + params.getNewHighWithinDays() + " 日创两年新高");
                }
            } catch (RuntimeException ex) {
                log.debug("选股：深历史获取失败 code={} msg={}", code, ex.getMessage());
            }
        }
        // 分时均价线：放最后校验，只对「靠这一项刚好能越线」的票拉分时，避免拖慢整轮刷新
        if (params.getVwapScore() > 0
                && score + params.getVwapScore() >= params.getMinScore()
                && (intraday != null || (vwapBudget != null && vwapBudget[0] > 0))) {
            if (intraday == null && vwapBudget != null) {
                vwapBudget[0]--;
            }
            try {
                IntradayData data = intraday != null ? intraday : tencentService.fetchIntraday(code);
                double ratio = PaperScreenRules.vwapAboveRatio(data.getItems());
                if (ratio >= params.getVwapAboveRatio()) {
                    score += params.getVwapScore();
                    reasons.add(String.format("分时 %.0f%% 站上均价线", ratio * 100));
                }
            } catch (RuntimeException ex) {
                log.debug("选股：分时获取失败 code={} msg={}", code, ex.getMessage());
            }
        }
        if (score < params.getMinScore() || reasons.isEmpty()) {
            return null;
        }
        // 低价比只作为附注写进理由（放在阈值判定之后，避免它把 0 分的票「救」进候选）
        if (!Double.isNaN(lowRatio)) {
            reasons.add(String.format("距年内低点 %.0f%%", (lowRatio - 1) * 100));
        }
        PaperSignal signal = new PaperSignal();
        signal.setCode(code);
        signal.setName(name.isEmpty() ? code : name);
        signal.setAction(PaperConstants.ACTION_CANDIDATE);
        signal.setReason(String.join("、", reasons));
        signal.setScore(score);
        signal.setPrice(PaperNumbers.price(BigDecimal.valueOf(close)));
        return new Candidate(signal, lowRatio);
    }

    // ---------- 硬条件 ----------

    /**
     * 现价/一年内最低价：开了严格过滤或按低价比排序才计算（需要深历史，成本不低）。
     */
    private double computeLowRatio(PaperStrategyParams params, String code,
                                   List<KlineItem> bars, int[] deepBudget) {
        if (params.getLowRatioMax() <= 0 && !params.isSortByLowRatioAsc()) {
            return Double.NaN;
        }
        List<KlineItem> source = bars;
        if (bars.size() < PaperScreenRules.ONE_YEAR_BARS * 0.8 && deepBudget != null && deepBudget[0] > 0) {
            deepBudget[0]--;
            try {
                List<KlineItem> deep = klineCacheService.deepLoad(code, PaperScreenRules.ONE_YEAR_BARS + 5);
                if (deep != null && deep.size() > bars.size()) {
                    source = deep;
                }
            } catch (RuntimeException ex) {
                log.debug("选股：深历史获取失败 code={} msg={}", code, ex.getMessage());
            }
        }
        return PaperScreenRules.lowRatio(source, PaperScreenRules.ONE_YEAR_BARS);
    }

    /**
     * 严格过滤：逐条判断用户列出的硬条件，返回第一条不满足的中文原因；全部通过返回 null。
     *
     * <p>设计成返回原因而不是 boolean，是为了在 debug 日志里能直接看出「哪只票卡在哪个条件」，
     * 排查「为什么今天没选出来」时比逐个加日志快得多。</p>
     */
    private String strictReject(PaperStrategyParams params, String code, String name,
                                List<KlineItem> bars, StockQuote quote, IntradayData intraday,
                                PaperAccount account, double lowRatio,
                                PaperScreenRules.Technicals technicals, int[] fundamentalBudget) {
        double price = quote != null && quote.getPrice() > 0 ? quote.getPrice()
                : bars.get(bars.size() - 1).getClose();
        if (price <= 0) {
            return "无现价";
        }
        // 1) 现价与一年内最低价比上限
        if (params.getLowRatioMax() > 0) {
            if (Double.isNaN(lowRatio)) {
                return "一年最低价数据不足";
            }
            if (lowRatio > params.getLowRatioMax()) {
                return String.format("距年内低点 %.0f%% 超过 %.0f%%", (lowRatio - 1) * 100,
                        (params.getLowRatioMax() - 1) * 100);
            }
        }
        // 2) 现价 > 今开
        if (params.isRequireAboveOpen()) {
            double open = quote != null ? quote.getOpen() : 0;
            if (open <= 0) {
                return "无今开价";
            }
            if (price <= open) {
                return "现价未高于今开";
            }
        }
        // 3) 现价 > 分时均价
        if (params.isRequireAboveVwap()) {
            if (intraday == null || intraday.getItems() == null || intraday.getItems().isEmpty()) {
                return "无分时数据";
            }
            double avg = 0;
            for (var item : intraday.getItems()) {
                if (item != null && item.getAvgPrice() > 0) {
                    avg = item.getAvgPrice();
                }
            }
            if (avg <= 0) {
                return "无分时均价";
            }
            if (price < avg) {
                return "现价未高于分时均价";
            }
        }
        // 4) 量比硬下限
        if (params.getHardVolumeRatioMin() > 0
                && (Double.isNaN(technicals.volumeRatio()) || technicals.volumeRatio() < params.getHardVolumeRatioMin())) {
            return String.format("量比 %.2f 低于 %.2f", technicals.volumeRatio(), params.getHardVolumeRatioMin());
        }
        // 5) 5 日均价 / 20 日均价
        if (params.getMa5OverMa20Min() > 0) {
            if (technicals.ma20() <= 0) {
                return "均线数据不足";
            }
            double ratio = technicals.ma5() / technicals.ma20();
            if (ratio < params.getMa5OverMa20Min()) {
                return String.format("5日/20日均价 %.2f 低于 %.2f", ratio, params.getMa5OverMa20Min());
            }
        }
        // 6) 5 日均量 / 20 日均量
        if (params.getVol5OverVol20Min() > 0
                && (Double.isNaN(technicals.volumeTrend()) || technicals.volumeTrend() < params.getVol5OverVol20Min())) {
            return String.format("5日/20日均量 %.2f 低于 %.2f", technicals.volumeTrend(),
                    params.getVol5OverVol20Min());
        }
        // 7) 近 N 日不得涨停
        if (params.getNoLimitUpWithinDays() > 0
                && PaperScreenRules.hasLimitUp(bars, params.getNoLimitUpWithinDays(), code, name)) {
            return params.getNoLimitUpWithinDays() + " 日内出现过涨停";
        }
        // 8) 总市值下限
        if (params.getTotalCapMinYi() > 0) {
            double cap = quote != null ? quote.getTotalMarketCap() : 0;
            if (cap <= 0) {
                return "无总市值数据";
            }
            if (cap < params.getTotalCapMinYi()) {
                return String.format("总市值 %.0f 亿 低于 %.0f 亿", cap, params.getTotalCapMinYi());
            }
        }
        // 9) 早盘放量 + 实际换手率 + 股东户数 + 盈利：都要基本面，按真实上游请求数扣预算
        boolean needFundamental = params.getOpenTurnoverMinPct() > 0
                || params.getRealTurnoverMinPct() > 0
                || params.isRequireHolderShrink()
                || params.getProfitRequire() > 0;
        if (needFundamental) {
            if (fundamentalBudget == null) {
                return "基本面查询预算未初始化";
            }
            long used = fundamentalService.upstreamRequests() - fundamentalBudget[0];
            if (used >= MAX_FUNDAMENTAL_CHECKS) {
                return "本轮基本面请求已达上限 " + MAX_FUNDAMENTAL_CHECKS + " 次";
            }
            if (params.getOpenTurnoverMinPct() > 0) {
                if (intraday == null || intraday.getItems() == null || intraday.getItems().isEmpty()) {
                    return "无分时数据（早盘换手率）";
                }
                double volume = PaperScreenRules.windowVolume(intraday.getItems(),
                        params.getOpenTurnoverStart(), params.getOpenTurnoverEnd());
                Double pct = fundamentalService.windowTurnoverPct(code, volume);
                if (pct == null) {
                    return "无自由流通股数据";
                }
                if (pct < params.getOpenTurnoverMinPct()) {
                    return String.format("早盘换手率 %.2f%% 低于 %.2f%%", pct, params.getOpenTurnoverMinPct());
                }
            }
            if (params.getRealTurnoverMinPct() > 0) {
                double lots = quote != null && quote.getVolume() > 0 ? quote.getVolume()
                        : bars.get(bars.size() - 1).getVolume();
                Double pct = fundamentalService.realTurnoverPct(code, lots);
                if (pct == null) {
                    return "无自由流通股数据（实际换手率）";
                }
                if (pct < params.getRealTurnoverMinPct()) {
                    return String.format("实际换手率 %.2f%% 低于 %.2f%%", pct, params.getRealTurnoverMinPct());
                }
            }
            if (params.isRequireHolderShrink()) {
                Boolean shrink = fundamentalService.holderShrink(code);
                if (shrink == null) {
                    return "无股东户数数据";
                }
                if (!shrink) {
                    return "股东户数未减少（集中度未变小）";
                }
            }
            if (params.getProfitRequire() > 0) {
                Boolean profit = fundamentalService.profitOk(code, params.getProfitRequire());
                if (profit == null) {
                    return "无盈利数据";
                }
                if (!profit) {
                    return "不满足盈利要求";
                }
            }
        }
        // 10) 按当前可用资金判断买不买得起（科创 200 股起、创业/主板 100 股起）
        if (params.isAffordableOnly()) {
            BigDecimal cash = account != null && account.getCash() != null
                    ? account.getCash() : BigDecimal.ZERO;
            if (cash.signum() <= 0) {
                return "账户无可用现金";
            }
            // 与 PaperAutoTrader 的单票预算口径保持一致：总资产 × 单票仓位上限，再和现金取小
            BigDecimal totalAsset = account.getTotalAsset() != null ? account.getTotalAsset() : cash;
            BigDecimal perPosition = totalAsset.multiply(
                    BigDecimal.valueOf(properties.getPaper().getMaxPositionPct()));
            BigDecimal budget = cash.min(perPosition);
            int minLot = LotRule.minLot(code);
            BigDecimal minCost = BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(minLot));
            if (minCost.compareTo(budget) > 0) {
                return String.format("单票预算 %.0f 元买不起 %d 股（%s 现价 %.2f 需 %.0f 元）",
                        budget.doubleValue(), minLot, code, price, minCost.doubleValue());
            }
        }
        return null;
    }
}
