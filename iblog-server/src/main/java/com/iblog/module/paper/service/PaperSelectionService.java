package com.iblog.module.paper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.iblog.config.IblogProperties;
import com.iblog.module.paper.entity.PaperAccount;
import com.iblog.module.paper.entity.PaperPosition;
import com.iblog.module.paper.entity.PaperSignal;
import com.iblog.module.paper.entity.PaperStrategy;
import com.iblog.module.paper.mapper.PaperPositionMapper;
import com.iblog.module.paper.mapper.PaperSignalMapper;
import com.iblog.module.paper.support.GlobalPredictScorer;
import com.iblog.module.paper.support.Indicators;
import com.iblog.module.paper.support.LotRule;
import com.iblog.module.paper.support.PaperConstants;
import com.iblog.module.paper.support.PaperNumbers;
import com.iblog.module.paper.support.PaperScreenRules;
import com.iblog.module.paper.support.PaperStrategyParams;
import com.iblog.module.paper.support.TradeCalendar;
import com.iblog.module.stock.ai.MacroNewsService;
import com.iblog.module.stock.model.HotStockItem;
import com.iblog.module.stock.model.IntradayData;
import com.iblog.module.stock.model.KlineItem;
import com.iblog.module.stock.model.MarketSentiment;
import com.iblog.module.stock.model.MoneyFlow;
import com.iblog.module.stock.model.StockQuote;
import com.iblog.module.stock.service.EastmoneyService;
import com.iblog.module.stock.service.HotListService;
import com.iblog.module.stock.service.MarketSentimentService;
import com.iblog.module.stock.service.OverseasMarketService;
import com.iblog.module.stock.service.StockFundamentalService;
import com.iblog.module.stock.service.TencentService;
import com.iblog.module.stock.ai.AiNewsScoreService;
import com.iblog.module.stock.entity.StockNewsScore;
import com.iblog.module.paper.support.NewsScoreRule;
import com.iblog.module.stock.support.MarketSentimentRule;
import com.iblog.module.stock.support.StockCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
    private final PaperPositionMapper positionMapper;
    private final PaperSignalMapper signalMapper;
    private final PaperStrategyService strategyService;
    private final PaperAccountService accountService;
    private final MarketSentimentService sentimentService;
    private final OverseasMarketService overseasMarketService;
    private final MacroNewsService macroNewsService;
    private final AiNewsScoreService newsScoreService;
    private final IblogProperties properties;
    private final TradeCalendar calendar;
    private final ObjectMapper objectMapper;

    /** 每个账户最近一次选股时间，避免自动交易每 15 秒重扫一遍。 */
    private final Map<Long, Long> lastRefreshAt = new ConcurrentHashMap<>();
    private final PaperPredictStatService predictStatService;

    /**
     * 外盘/宏观背景：一次选股里所有票共用同一份，60 秒内复用。
     *
     * <p>底层两个服务各自有缓存（外盘 10 分钟、宏观新闻 30 分钟），这里再加一层是为了
     * 避免一次选股给几百只票各问一遍；失败也缓存 60 秒，防止上游抖动时整轮都在重试。</p>
     */
    private volatile GlobalPredictScorer.MarketContext marketContextCache;
    private volatile long marketContextAt;

    /** 重新选股并覆盖当日候选（手动刷新/定时刷新都走这里）。 */
    public List<PaperSignal> refresh(Long accountId) {
        return refresh(accountId, false, false);
    }

    /**
     * 盘前预选：开关打开且当天还没有候选时，用日 K 粗筛一批。
     *
     * <p>只在 9:25 后由引擎调用；当天已有候选（比如用户手动选过）就不覆盖，
     * 以免把完整评分的结果换成粗筛结果。</p>
     */
    public void preselectIfEnabled(Long accountId) {
        PaperStrategy strategy = strategyService.ensureDefault(accountId);
        PaperStrategyParams params = PaperStrategyParams.parse(objectMapper, strategy.getParams());
        if (!params.isPreMarketPreselect()) {
            return;
        }
        if (!recentCandidates(accountId).isEmpty()) {
            return;
        }
        List<PaperSignal> result = refresh(accountId, true, false);
        log.info("模拟盘盘前预选完成 account={} 候选={}", accountId, result.size());
    }

    /**
     * 开盘复核：预选后的完整选股，用盘中真实数据覆盖粗筛结果。
     *
     * <p>{@code keepWhenEmpty=true}：如果复核这一轮没选出任何票（例如分时数据还没落定），
     * 保留预选结果，而不是把候选清空 —— 有粗筛结果总比开盘后一无所有强。</p>
     *
     * @param onlyIfStillPreselected true=只在候选仍是开盘前选出的时候才复核（9:32 兜底用）：
     *                               9:30 那轮如果已经成功覆盖，候选 createTime 会晚于 9:30，
     *                               这里直接跳过，不重复扫一遍上游
     */
    public void refreshAfterOpenIfEnabled(Long accountId, boolean onlyIfStillPreselected) {
        PaperStrategy strategy = strategyService.ensureDefault(accountId);
        PaperStrategyParams params = PaperStrategyParams.parse(objectMapper, strategy.getParams());
        if (!params.isPreMarketPreselect()) {
            return;
        }
        if (onlyIfStillPreselected && !stillPreselected(accountId)) {
            return;
        }
        List<PaperSignal> result = refresh(accountId, false, true);
        log.info("模拟盘开盘复核完成 account={} 候选={}", accountId, result.size());
    }

    /** 当天候选是否还是开盘前（9:30 之前）选出的；没有候选时也返回 true，让复核兜底选一次。 */
    private boolean stillPreselected(Long accountId) {
        List<PaperSignal> candidates = recentCandidates(accountId);
        if (candidates.isEmpty()) {
            return true;
        }
        LocalDateTime cutoff = calendar.today().atTime(9, 30);
        return candidates.stream().allMatch(signal -> signal.getCreateTime() != null
                && signal.getCreateTime().isBefore(cutoff));
    }

    /**
     * @param preMarket     true=盘前预选：跳过所有依赖分时/当日成交的条件与加分项
     * @param keepWhenEmpty true=本轮没选出票时保留当天已有候选（用于开盘复核兜底）
     */
    private List<PaperSignal> refresh(Long accountId, boolean preMarket, boolean keepWhenEmpty) {
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

        // 情绪只在规则开了的时候取一次，整个池子共用（服务内部还有 60 秒缓存）。
        // 盘前涨跌家数还没形成，情绪分没有参考价值，预选阶段直接不取
        MarketSentiment sentiment = !preMarket && params.isSentimentEnabled()
                ? sentimentService.current() : null;

        List<Candidate> selected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int scanned = 0;
        int rank = 0;
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
            // 热榜名次：热榜本身就是按热度排的，用遍历序当名次，不额外请求
            rank++;
            // 只做热榜前 N 名：热度是稀缺注意力，排在后面的说明资金没在看
            if (params.getHotRankTopN() > 0 && rank > params.getHotRankTopN()) {
                break;
            }
            String name = item.getName() == null ? "" : item.getName();
            // 「非 ST」是硬条件，无论是否开严格模式都成立
            if (name.toUpperCase().contains("ST")) {
                continue;
            }
            scanned++;
            try {
                Candidate candidate = evaluate(strategy, params, code, name, item, rank, sentiment,
                        account, vwapBudget, deepBudget, fundamentalBudget, preMarket, false);
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
        // 顺带给当前持仓也按同一套策略打一遍分（不计热度，热度对已持有的票没意义），
        // 存内存缓存供持仓列表展示 —— 让用户一眼看到「策略现在觉得我这票值不值得拿」。
        // 不写信号、不进候选，纯展示，绝不触发交易。放在「保留候选」提前返回之前，
        // 保证开盘复核兜底那轮也会刷新持仓分。
        scoreHeldPositions(accountId, strategy, params, account, sentiment, preMarket);

        if (selected.isEmpty() && keepWhenEmpty) {
            log.warn("模拟盘开盘复核未选出候选，保留盘前预选结果 account={}", accountId);
            return recentCandidates(accountId);
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
        log.info("模拟盘选股完成 account={} 扫描={} 命中={} 盘前={}", accountId, scanned, result.size(), preMarket);
        return result;
    }

    /**
     * 尾盘预测：14:30 / 14:50 用当日最新数据给热榜与持仓打分。
     *
     * <p>与早盘选股的区别：不做买入侧硬性剔除、不设候选门槛，先用同一套因子打出
     * 原始分再按分数排名；买不买交给 {@code predictBuyMinScore} 在自动交易里判断。
     * 持仓也一起打分并写入 {@link #heldScores}，供「预测走弱卖出」使用。</p>
     */
    public List<PaperSignal> predict(Long accountId) {
        PaperStrategy strategy = strategyService.ensureDefault(accountId);
        PaperStrategyParams params = PaperStrategyParams.parse(objectMapper, strategy.getParams());
        if (!params.isPredictEnabled() || !calendar.isTradingDay(calendar.today())) {
            return recentCandidates(accountId);
        }
        String today = calendar.todayText();
        PaperAccount account = null;
        try {
            account = accountService.getById(accountId);
        } catch (RuntimeException ex) {
            log.debug("预测：账户读取失败 account={} msg={}", accountId, ex.getMessage());
        }

        List<HotStockItem> hot = List.of();
        try {
            hot = hotListService.fetchHotList();
        } catch (RuntimeException ex) {
            log.warn("预测：热榜获取失败 {}", ex.getMessage());
        }
        MarketSentiment sentiment = params.isSentimentEnabled() ? sentimentService.current() : null;

        List<Candidate> scored = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int[] vwapBudget = {MAX_VWAP_CHECKS};
        int[] deepBudget = {MAX_DEEP_CHECKS};
        int[] fundamentalBudget = {fundamentalService.upstreamRequests()};
        int rank = 0;
        int scanned = 0;
        for (HotStockItem item : hot) {
            if (scanned >= params.getUniverseSize()) {
                break;
            }
            String code = StockCode.normalize(item.getCode());
            if (code.isEmpty() || StockCode.isHk(code) || !seen.add(code)) {
                continue;
            }
            rank++;
            if (params.getHotRankTopN() > 0 && rank > params.getHotRankTopN()) {
                break;
            }
            String name = item.getName() == null ? "" : item.getName();
            if (name.toUpperCase().contains("ST")) {
                continue;
            }
            scanned++;
            try {
                Candidate candidate = evaluate(strategy, params, code, name, item, rank, sentiment,
                        account, vwapBudget, deepBudget, fundamentalBudget, false, true);
                if (candidate != null) {
                    scored.add(candidate);
                }
            } catch (RuntimeException ex) {
                log.warn("预测打分失败 code={} msg={}", code, ex.getMessage());
            }
        }

        // 持仓单独再打一遍（可能已经跌出热榜）。预算独立，避免和候选抢分时/深历史额度。
        scoreHeldPositions(accountId, strategy, params, account, sentiment, false);

        // 按分数降序取前 maxCandidates 只作为可买候选
        scored.sort(Comparator.comparingInt((Candidate c) -> c.signal().getScore()).reversed());
        int limit = Math.max(1, params.getMaxCandidates());
        if (scored.size() > limit) {
            scored = new ArrayList<>(scored.subList(0, limit));
        }

        signalMapper.delete(Wrappers.<PaperSignal>lambdaQuery()
                .eq(PaperSignal::getAccountId, accountId)
                .eq(PaperSignal::getAction, PaperConstants.ACTION_CANDIDATE)
                .eq(PaperSignal::getTradeDate, today));
        List<PaperSignal> result = new ArrayList<>(scored.size());
        for (Candidate candidate : scored) {
            PaperSignal signal = candidate.signal();
            signal.setAccountId(accountId);
            signal.setStrategyId(strategy.getId());
            signal.setTradeDate(today);
            signal.setReason("尾盘预测：" + signal.getReason());
            signalMapper.insert(signal);
            result.add(signal);
        }
        strategyService.touch(strategy);
        lastRefreshAt.put(accountId, System.currentTimeMillis());
        log.info("模拟盘尾盘预测完成 account={} 扫描={} 候选={}", accountId, scanned, result.size());
        // 把本轮候选落成胜率样本（预测时现价 → 当日收盘，结算时回填）
        try {
            predictStatService.recordCandidates(result);
        } catch (RuntimeException ex) {
            log.warn("尾盘预测候选落库失败 accountId={}", accountId, ex);
        }
        return result;
    }

    /** 候选 + 低价比，用于「按低价比升序」排序（PaperSignal 不落库这个字段）。 */
    private record Candidate(PaperSignal signal, double lowRatio) {
    }

    /** 持仓评分结果：仅展示用，命中候选规则的打分 + 理由。 */
    public record HeldScore(int score, String name, String reason) {
    }

    /** 最近一次重选股时对持仓打的分数，accountId → code → score。 */
    private final Map<Long, Map<String, HeldScore>> heldScoreCache = new ConcurrentHashMap<>();

    /**
     * 给当前持仓按同一策略打分并存缓存。
     *
     * <p>持仓可能不在热榜，热度相关项一律为 0（hot=null、hotRank=0），其余因子与候选完全一致。
     * 用独立预算，避免吃掉候选打分的分时/深历史/基本面子请求额度。</p>
     */
    private void scoreHeldPositions(Long accountId, PaperStrategy strategy, PaperStrategyParams params,
                                    PaperAccount account, MarketSentiment sentiment, boolean preMarket) {
        List<PaperPosition> positions;
        try {
            positions = positionMapper.selectList(Wrappers.<PaperPosition>lambdaQuery()
                    .eq(PaperPosition::getAccountId, accountId));
        } catch (RuntimeException ex) {
            log.debug("选股：持仓读取失败 account={} msg={}", accountId, ex.getMessage());
            return;
        }
        Map<String, HeldScore> scores = new ConcurrentHashMap<>();
        for (PaperPosition position : positions) {
            if (position.getCode() == null || position.getCode().isBlank()) {
                continue;
            }
            try {
                String name = position.getName() == null ? "" : position.getName();
                Candidate candidate = scoreCandidate(strategy, params,
                        StockCode.normalize(position.getCode()), name, account, sentiment, preMarket);
                if (candidate != null) {
                    scores.put(position.getCode(),
                            new HeldScore(candidate.signal().getScore(), name, candidate.signal().getReason()));
                }
            } catch (RuntimeException ex) {
                log.debug("选股：持仓打分失败 code={} msg={}", position.getCode(), ex.getMessage());
            }
        }
        heldScoreCache.put(accountId, scores);
    }

    /** 最近一次重选股给该账户持仓打的分数；无则返回空 map（不缓存 null，避免穿透查询）。 */
    public Map<String, HeldScore> heldScores(Long accountId) {
        Map<String, HeldScore> scores = heldScoreCache.get(accountId);
        return scores == null ? Map.of() : scores;
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

    /**
     * 给单只持仓打分：与候选同一套因子，但不套用买入侧的硬性剔除与入选门槛。
     * 热度相关项对已持有的票没有意义，传 {@code hot=null、hotRank=0}。
     */
    /**
     * 取外盘/宏观背景；任何一环失败都退化成「没有背景」，不影响选股本身。
     *
     * <p>口径提醒：盘中拿到的是「昨晚美股 + 日经/KOSPI 实时值」，
     * 与 15:30 次日预测那次「美日韩全部收盘」略有不同。</p>
     */
    private GlobalPredictScorer.MarketContext marketContext() {
        long now = System.currentTimeMillis();
        GlobalPredictScorer.MarketContext cached = marketContextCache;
        if (cached != null && now - marketContextAt < 60_000L) {
            return cached;
        }
        GlobalPredictScorer.MarketContext context;
        try {
            OverseasMarketService.Snapshot overseas = overseasMarketService.snapshot();
            MacroNewsService.MacroNews macro = macroNewsService.scan();
            context = new GlobalPredictScorer.MarketContext(
                    overseas.available() ? overseas.weightedPct() : null,
                    macro.available() ? macro.score() : null);
        } catch (RuntimeException ex) {
            log.debug("选股：外盘/宏观背景获取失败 err={}", ex.getMessage());
            context = new GlobalPredictScorer.MarketContext(null, null);
        }
        marketContextCache = context;
        marketContextAt = now;
        return context;
    }

    private Candidate scoreCandidate(PaperStrategy strategy, PaperStrategyParams params, String code, String name,
                                     PaperAccount account, MarketSentiment sentiment, boolean preMarket) {
        return evaluate(strategy, params, code, name, null, 0, sentiment, account,
                new int[]{MAX_VWAP_CHECKS}, new int[]{MAX_DEEP_CHECKS},
                new int[]{fundamentalService.upstreamRequests()}, preMarket, true);
    }

    /** 手动评分结果：分数、命中理由，以及当前规则的入选门槛。 */
    public record StockScore(String code, String name, int score, String reason, double minScore) {
    }

    /**
     * 按账户当前规则给任意一只股票打分。
     *
     * <p>口径与持仓评分一致（{@code forHeld=true}）：只回答「这套规则会给它多少分」，
     * 不套用买入侧的硬性剔除和入选门槛——被规则过滤掉的票也应该能看到分数，
     * 否则用户只知道「没选上」却不知道为什么。结果里的 {@code minScore} 供前端判断能否入选。</p>
     *
     * @return 日 K 不足（少于 30 根，多为新股）或代码非法时返回 null
     */
    public StockScore scoreStock(Long accountId, String rawCode, String name) {
        String code = StockCode.normalize(rawCode);
        if (code.isEmpty() || StockCode.isHk(code)) {
            return null;
        }
        PaperStrategy strategy = strategyService.ensureDefault(accountId);
        PaperStrategyParams params = PaperStrategyParams.parse(objectMapper, strategy.getParams());
        String stockName = name == null ? "" : name.trim();
        if (stockName.isEmpty()) {
            try {
                StockQuote quote = quoteService.quote(code);
                stockName = quote != null && quote.getName() != null ? quote.getName() : code;
            } catch (RuntimeException ex) {
                stockName = code;
            }
        }
        PaperAccount account = null;
        try {
            account = accountService.getById(accountId);
        } catch (RuntimeException ex) {
            log.debug("手动评分：账户读取失败 account={} msg={}", accountId, ex.getMessage());
        }
        MarketSentiment sentiment = params.isSentimentEnabled() ? sentimentService.current() : null;
        Candidate candidate = scoreCandidate(strategy, params, code, stockName, account, sentiment, false);
        if (candidate == null) {
            return null;
        }
        PaperSignal signal = candidate.signal();
        return new StockScore(code, stockName,
                signal.getScore() == null ? 0 : signal.getScore(),
                signal.getReason(), params.getMinScore());
    }

    private Candidate evaluate(PaperStrategy strategy, PaperStrategyParams params, String code, String name,
                               HotStockItem hot, int hotRank, MarketSentiment sentiment, PaperAccount account,
                               int[] vwapBudget, int[] deepBudget, int[] fundamentalBudget, boolean preMarket,
                               boolean forHeld) {
        List<KlineItem> bars = klineCacheService.load(code, MIN_BARS);
        if (bars.size() < 30) {
            return null;
        }
        String todayText = calendar.todayText();
        // 盘前日K接口常会带上「今天」这根未完成的 bar：收盘价就是集合竞价开盘价、
        // 成交量只有竞价那一点。拿它算均线/量比/连板都会失真，预选阶段直接剔除，
        // 只看截至昨天的完整日K；复核时重新拉取，自然带上当天的数据。
        KlineItem lastBar = bars.get(bars.size() - 1);
        if (preMarket && lastBar != null && todayText.equals(lastBar.getDate())) {
            bars = bars.subList(0, bars.size() - 1);
            if (bars.size() < 30) {
                return null;
            }
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
        if (!forHeld && params.getBoardHeightMax() > 0
                && (boardHeight < 1 || boardHeight > params.getBoardHeightMax())) {
            return null;
        }
        double ma5 = Indicators.sma(closes, 5);
        double ma10 = Indicators.sma(closes, 10);
        double ma20 = Indicators.sma(closes, 20);
        Indicators.Macd macd = Indicators.macd(closes);
        double rsi = Indicators.rsi(closes, 14);
        // 量比按「已走过的交易分钟数」归一化：盘中最后一根日 K 只有当日累计量，
        // 不折算的话上午算出来的量比会被压低好几倍（详见 Indicators#volumeRatio）。
        // 最后一根不是今天（盘前、或行情没刷新）时它已经是完整的一天，按整日算。
        KlineItem latestBar = bars.get(bars.size() - 1);
        boolean latestIsToday = latestBar != null && todayText.equals(latestBar.getDate());
        double volumeRatio = Indicators.volumeRatio(volumes, 5,
                latestIsToday ? calendar.elapsedMinutes(calendar.now()) : TradeCalendar.SESSION_MINUTES,
                TradeCalendar.SESSION_MINUTES);

        StockQuote quote = null;
        try {
            quote = quoteService.quote(code);
        } catch (RuntimeException ignored) {
            // 盘中取不到实时价不影响用日 K 打分
        }
        double changePct = quote != null && quote.getPrice() > 0 ? quote.getChangePct() : 0;

        MoneyFlow flow = null;
        // 东财资金流在本轮里已经熔断（连续失败）就直接跳过：每只票再问一次都是白等，
        // 而资金流只是加分项之一，跳过不影响其余日 K 因子打分
        if (!eastmoneyService.isUpstreamDown()) {
            try {
                flow = eastmoneyService.fetchMoneyFlow(code);
            } catch (RuntimeException ex) {
                log.debug("选股：资金流获取失败 code={} msg={}", code, ex.getMessage());
            }
        }

        // ---------- 用户自定义硬条件（严格过滤）：不满足直接剔除，不参与打分 ----------
        // 早盘放量/竞价量/站上均价三项都要分时，先拉一次共用，避免重复请求。
        // 盘前预选阶段分时还没生成，拉也是空，直接跳过
        IntradayData intraday = null;
        if (!preMarket && (params.getOpenTurnoverMinPct() > 0 || params.getAuctionTurnoverMinPct() > 0
                || params.isRequireAboveVwap())) {
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
        // 持仓评分只看「策略给它多少分」，不套用选股的硬性剔除条件：
        // 持仓已经买在手里，硬条件不满足恰恰是要展示的信号，而不是「没有分」。
        if (!forHeld && params.isStrictFilters()) {
            String reject = strictReject(params, code, name, bars, quote, intraday, account,
                    lowRatio, technicals, fundamentalBudget, preMarket);
            if (reject != null) {
                log.debug("选股：硬条件剔除 code={} name={} 原因={}", code, name, reject);
                return null;
            }
        }
        // 热度硬条件：只做热度还在上升的票（排名比上一次靠前）。持仓不在热榜，不适用。
        if (!forHeld && params.isRequireHotRankUp() && (hot == null || hot.getHotRankChg() <= 0)) {
            log.debug("选股：剔除热度未上升 code={} name={}", code, name);
            return null;
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
        // 当日涨幅：情绪越热，可容忍的上限越宽（冰点日追高就是接盘）
        double changeMax = params.getChangeMaxPct();
        double sentimentScore = sentiment != null && sentiment.isAvailable() ? sentiment.getScore() : Double.NaN;
        double effectiveChangeMax = MarketSentimentRule.effectiveChangeMax(
                changeMax, params.getSentimentChangeMaxPct(), sentimentScore);
        // 盘前只有集合竞价一个价格，涨跌幅还没意义
        if (!preMarket && changePct > params.getChangeMinPct() && changePct < effectiveChangeMax) {
            score += params.getChangeScore();
            reasons.add("当日温和上涨");
        }
        if (hot != null && hot.getHotRankChg() > 0) {
            score += params.getHotRankScore();
            reasons.add("热度上升");
        }
        // 热度分：热榜名次越靠前分越高，情绪越热这项越值钱（场内资金愿意接力）
        if (params.getHotnessScore() > 0 && hotRank > 0) {
            int weight = MarketSentimentRule.hotnessWeight(hotRank, params.getUniverseSize());
            if (weight > 0) {
                int boost = Math.round(params.getHotnessScore() * weight * 0.01f);
                if (boost > 0) {
                    score += boost;
                    reasons.add("热榜第 " + hotRank + " 名");
                }
            }
        }
        // 新闻情绪分：只做边际调整（利好最多加 newsScoreMax、利空最多扣 newsPenaltyMax），
        // 缓存不新鲜或扫新闻失败都按 0 分处理，绝不因为新闻因子让选股失败。盘前照常参与。
        if (params.isNewsEnabled()) {
            StockNewsScore news = newsScoreService.latest(code, params.getNewsRefreshMin());
            if (news != null) {
                int bonus = NewsScoreRule.bonus(params.getNewsScoreMax(), params.getNewsPenaltyMax(),
                        news.getScore() == null ? 0 : news.getScore(),
                        news.getConfidence() == null ? 0 : news.getConfidence().doubleValue());
                // 利好兑现：涨幅 >7% 或昨日已连板时，利好对次日买盘的边际作用下降，打五折
                int adjusted = NewsScoreRule.pricedInDiscount(bonus, changePct, boardHeight);
                boolean discounted = adjusted != bonus;
                bonus = adjusted;
                if (bonus != 0) {
                    score += bonus;
                    String verb = bonus > 0 ? "新闻+" + bonus : "新闻" + bonus;
                    String summary = news.getSummary();
                    reasons.add((summary == null || summary.isBlank()
                            ? verb + "（" + news.getLabel() + "）"
                            : verb + "（" + summary + "）") + (discounted ? "，高位打折" : ""));
                }
                // 硬过滤：置信度足够且分低于阈值才剔除（默认关）。持仓评分只展示分，不剔除。
                if (!forHeld && params.isNewsHardFilter()
                        && NewsScoreRule.filtered(news.getScore() == null ? 0 : news.getScore(),
                        news.getConfidence() == null ? 0 : news.getConfidence().doubleValue(),
                        params.getNewsMinConfidence(), params.getNewsMinScore())) {
                    log.debug("选股：新闻硬过滤剔除 code={} name={} 新闻分={}", code, name, news.getScore());
                    return null;
                }
            }
        }
        // 外盘 + 宏观：全市场级边际调整。同一天每只票加同样的分，不改变票与票之间的相对排序，
        // 但会让整轮选股的门槛更松或更紧——外盘大跌的日子，弱票更难够到 minScore。
        int marketPoints = GlobalPredictScorer.marketPoints(marketContext());
        if (marketPoints != 0) {
            score += marketPoints;
            reasons.add((marketPoints > 0 ? "外盘/宏观偏暖+" : "外盘/宏观偏冷") + marketPoints);
        }
        // 换手率：活跃但不过热（盘前换手率只有竞价那一点，不可比）
        if (!preMarket && quote != null && PaperScreenRules.inRange(quote.getTurnoverRate(),
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
        if (!preMarket && params.getVwapScore() > 0
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
        // 持仓评分不设入选门槛：低于 minScore 的持仓也要给出分数，这正是「策略还看不看得上」的信号
        if (!forHeld && (score < params.getMinScore() || reasons.isEmpty())) {
            return null;
        }
        if (forHeld && reasons.isEmpty()) {
            reasons.add("无加分项");
        }
        // 低价比只作为附注写进理由（放在阈值判定之后，避免它把 0 分的票「救」进候选）
        if (!Double.isNaN(lowRatio)) {
            reasons.add(String.format("距年内低点 %.0f%%", (lowRatio - 1) * 100));
        }
        // 盘前没有当日 K 线，候选价用集合竞价开盘价更贴近用户看到的「现价」；
        // 复核时会被当天的最新收盘价覆盖
        double signalPrice = preMarket && quote != null && quote.getOpen() > 0
                ? quote.getOpen() : close;
        PaperSignal signal = new PaperSignal();
        signal.setCode(code);
        signal.setName(name.isEmpty() ? code : name);
        signal.setAction(PaperConstants.ACTION_CANDIDATE);
        signal.setReason(String.join("、", reasons));
        signal.setScore(score);
        signal.setPrice(PaperNumbers.price(BigDecimal.valueOf(signalPrice)));
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
                                PaperScreenRules.Technicals technicals, int[] fundamentalBudget,
                                boolean preMarket) {
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
        // 2) 现价 > 今开（盘前「现价」就是开盘价，这项必然不成立，跳过）
        if (!preMarket && params.isRequireAboveOpen()) {
            double open = quote != null ? quote.getOpen() : 0;
            if (open <= 0) {
                return "无今开价";
            }
            if (price <= open) {
                return "现价未高于今开";
            }
        }
        // 3) 现价 > 分时均价（盘前没有分时数据，跳过）
        if (!preMarket && params.isRequireAboveVwap()) {
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
        // 4) 量比硬下限（盘前算出来的是昨日量比，与今天无关，跳过）
        if (!preMarket && params.getHardVolumeRatioMin() > 0
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
        // 盘前成交量还没形成，前三项（放量/竞价/实际换手）没有意义，只留后两项基本面
        boolean needFundamental = (!preMarket && (params.getOpenTurnoverMinPct() > 0
                || params.getAuctionTurnoverMinPct() > 0
                || params.getRealTurnoverMinPct() > 0))
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
            if (!preMarket && params.getAuctionTurnoverMinPct() > 0) {
                if (intraday == null || intraday.getItems() == null || intraday.getItems().isEmpty()) {
                    return "无分时数据（集合竞价量）";
                }
                double auction = PaperScreenRules.auctionVolume(intraday.getItems());
                if (auction <= 0) {
                    return "无集合竞价成交（开盘价即竞价，未撮合或分时不全）";
                }
                Double pct = fundamentalService.realTurnoverPct(code, auction);
                if (pct == null) {
                    return "无自由流通股数据（集合竞价换手率）";
                }
                if (pct < params.getAuctionTurnoverMinPct()) {
                    return String.format("集合竞价换手率 %.3f%% 低于 %.3f%%",
                            pct, params.getAuctionTurnoverMinPct());
                }
            }
            if (!preMarket && params.getOpenTurnoverMinPct() > 0) {
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
            if (!preMarket && params.getRealTurnoverMinPct() > 0) {
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
