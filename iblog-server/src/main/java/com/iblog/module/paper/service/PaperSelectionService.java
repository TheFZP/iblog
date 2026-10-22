package com.iblog.module.paper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.iblog.config.IblogProperties;
import com.iblog.module.paper.entity.PaperSignal;
import com.iblog.module.paper.entity.PaperStrategy;
import com.iblog.module.paper.mapper.PaperSignalMapper;
import com.iblog.module.paper.support.Indicators;
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

    private final HotListService hotListService;
    private final KlineCacheService klineCacheService;
    private final EastmoneyService eastmoneyService;
    private final TencentService tencentService;
    private final PaperQuoteService quoteService;
    private final PaperSignalMapper signalMapper;
    private final PaperStrategyService strategyService;
    private final TradeCalendar calendar;
    private final ObjectMapper objectMapper;

    /** 每个账户最近一次选股时间，避免自动交易每 15 秒重扫一遍。 */
    private final Map<Long, Long> lastRefreshAt = new ConcurrentHashMap<>();

    /** 重新选股并覆盖当日候选（手动刷新/定时刷新都走这里）。 */
    public List<PaperSignal> refresh(Long accountId) {
        PaperStrategy strategy = strategyService.ensureDefault(accountId);
        PaperStrategyParams params = PaperStrategyParams.parse(objectMapper, strategy.getParams());
        String today = calendar.todayText();

        List<HotStockItem> hot = List.of();
        try {
            hot = hotListService.fetchHotList();
        } catch (RuntimeException ex) {
            log.warn("选股：热榜获取失败 {}", ex.getMessage());
        }

        List<PaperSignal> selected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int scanned = 0;
        int[] vwapBudget = {MAX_VWAP_CHECKS};
        for (HotStockItem item : hot) {
            if (scanned >= params.getUniverseSize()) {
                break;
            }
            String code = StockCode.normalize(item.getCode());
            if (code.isEmpty() || StockCode.isHk(code) || !seen.add(code)) {
                continue;
            }
            String name = item.getName() == null ? "" : item.getName();
            if (name.toUpperCase().contains("ST")) {
                continue;
            }
            scanned++;
            try {
                PaperSignal signal = evaluate(strategy, params, code, name, item, vwapBudget);
                if (signal != null) {
                    selected.add(signal);
                }
            } catch (RuntimeException ex) {
                log.warn("选股评分失败 code={} msg={}", code, ex.getMessage());
            }
        }

        selected.sort(Comparator.comparingInt(PaperSignal::getScore).reversed());
        int limit = Math.max(1, params.getMaxCandidates());
        if (selected.size() > limit) {
            selected = new ArrayList<>(selected.subList(0, limit));
        }

        signalMapper.delete(Wrappers.<PaperSignal>lambdaQuery()
                .eq(PaperSignal::getAccountId, accountId)
                .eq(PaperSignal::getAction, PaperConstants.ACTION_CANDIDATE)
                .eq(PaperSignal::getTradeDate, today));
        for (PaperSignal signal : selected) {
            signal.setAccountId(accountId);
            signal.setStrategyId(strategy.getId());
            signal.setTradeDate(today);
            signalMapper.insert(signal);
        }
        strategyService.touch(strategy);
        lastRefreshAt.put(accountId, System.currentTimeMillis());
        log.info("模拟盘选股完成 account={} 扫描={} 命中={}", accountId, scanned, selected.size());
        return selected;
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

    private PaperSignal evaluate(PaperStrategy strategy, PaperStrategyParams params, String code, String name,
                                 HotStockItem hot, int[] vwapBudget) {
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
        // 分时均价线：放最后校验，只对「靠这一项刚好能越线」的票拉分时，避免拖慢整轮刷新
        if (params.getVwapScore() > 0
                && score + params.getVwapScore() >= params.getMinScore()
                && vwapBudget != null && vwapBudget[0] > 0) {
            vwapBudget[0]--;
            try {
                IntradayData intraday = tencentService.fetchIntraday(code);
                double ratio = PaperScreenRules.vwapAboveRatio(intraday.getItems());
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
        PaperSignal signal = new PaperSignal();
        signal.setCode(code);
        signal.setName(name.isEmpty() ? code : name);
        signal.setAction(PaperConstants.ACTION_CANDIDATE);
        signal.setReason(String.join("、", reasons));
        signal.setScore(score);
        signal.setPrice(PaperNumbers.price(BigDecimal.valueOf(close)));
        return signal;
    }
}
