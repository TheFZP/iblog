package com.iblog.module.stock.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iblog.config.IblogProperties;
import com.iblog.module.paper.service.KlineCacheService;
import com.iblog.module.paper.support.DailyBarAssembler;
import com.iblog.module.paper.support.GlobalPredictScorer;
import com.iblog.module.paper.support.PredictEvaluation;
import com.iblog.module.paper.support.PredictFilters;
import com.iblog.module.paper.support.TradeCalendar;
import com.iblog.module.stock.ai.AiNewsScoreService;
import com.iblog.module.stock.ai.AiWebSearchService;
import com.iblog.module.stock.ai.MacroNewsService;
import com.iblog.module.stock.entity.StockNewsScore;
import com.iblog.module.stock.entity.StockPredictDaily;
import com.iblog.module.stock.model.KlineItem;
import com.iblog.module.stock.model.MarketSentiment;
import com.iblog.module.stock.model.StockQuote;
import com.iblog.module.stock.support.StockCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全市场「次日看涨」预测的编排服务。
 *
 * <p>数据流：全市场列表 → 批量行情预筛 → 读 120 根日 K（带补拉预算）→ 规则打分 →
 * 前 M 只搜新闻并重算 → 概率校准 → 取前 K 写入榜单。次日收盘后再回填收益。</p>
 *
 * <p>其中叠加两类全市场背景：外盘（纳指/标普/道指/日经/KOSPI 的最近收盘涨跌）与
 * 宏观新闻（东财快讯里筛出的美股、日韩、美联储等条目）。两者都记在每行的 {@code features} 里，
 * 方便事后回测「那天外盘怎么样、模型有没有据此收紧」。</p>
 *
 * <p>所有上游都是 best-effort：列表失败有兜底池，行情/K 线失败只丢个别股票，
 * 新闻失败保留规则分。任务本身跑在独立单线程调度器上，不影响模拟盘 15 秒 tick。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NextDayPredictService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_PENDING_PER_RUN = 200;
    private static final int MAX_NO_DATA_TRADING_DAYS = 5;

    private final IblogProperties properties;
    private final TradeCalendar calendar;
    private final MarketUniverseService universeService;
    private final TencentService tencentService;
    private final MarketSentimentService sentimentService;
    private final OverseasMarketService overseasMarketService;
    private final MacroNewsService macroNewsService;
    private final KlineCacheService klineCacheService;
    private final AiNewsScoreService newsScoreService;
    private final PredictProbabilityService probabilityService;
    private final NextDayPredictStore store;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 下一次允许打 K 线上游的时间戳（单线程任务里够用）。 */
    private volatile long nextKlineFetchAt = 0L;

    public record GenerateResult(LocalDate tradeDate, boolean dryRun, int universe, int scored,
                                 int newsCovered, List<StockPredictDaily> items, long elapsedMs) {
    }

    public boolean hasData(LocalDate date) {
        return store.exists(date);
    }

    public List<StockPredictDaily> latestBefore(LocalDate date) {
        return store.latestBefore(date);
    }

    public List<StockPredictDaily> listByDate(LocalDate date) {
        return store.listByDate(date);
    }

    /**
     * 每日任务：先回填待评估的旧预测，再生成当天榜单。异常不外抛。
     */
    public void runDaily() {
        if (!properties.getPredict().isEnabled()) {
            return;
        }
        if (!calendar.isTradingDay(calendar.today())) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("次日预测：上一轮仍在运行，跳过本次触发");
            return;
        }
        try {
            LocalDate today = calendar.today();
            evaluatePending(today);
            GenerateResult result = doGenerate(today, false);
            log.info("次日预测完成 date={} 候选={} 打分={} 新闻={} 榜单={} 耗时={}ms",
                    result.tradeDate(), result.universe(), result.scored(), result.newsCovered(),
                    result.items().size(), result.elapsedMs());
        } catch (RuntimeException ex) {
            log.warn("次日预测任务失败 err={}", ex.toString());
        } finally {
            running.set(false);
        }
    }

    /**
     * 生成榜单；{@code dryRun=true} 时只返回结果不落库。
     *
     * <p>手工刷新接口调用这个入口；与定时任务共用同一把运行锁，避免同时跑两轮。</p>
     */
    public GenerateResult generate(LocalDate tradeDate, boolean dryRun) {
        if (!running.compareAndSet(false, true)) {
            log.info("次日预测：任务运行中，拒绝重复触发");
            return new GenerateResult(tradeDate, dryRun, 0, 0, 0, List.of(), 0);
        }
        try {
            return doGenerate(tradeDate, dryRun);
        } finally {
            running.set(false);
        }
    }

    private GenerateResult doGenerate(LocalDate tradeDate, boolean dryRun) {
        long start = System.currentTimeMillis();
        IblogProperties.Predict config = properties.getPredict();

        MarketSentiment sentiment = sentimentService.current();
        double sentimentScore = sentiment != null && sentiment.isAvailable()
                ? sentiment.getScore() : Double.NaN;

        // 外盘快照很便宜（一次请求、10 分钟缓存），先拿到手；宏观新闻要搜一次 + 调一次模型，
        // 放到确定有候选之后再抓，避免全市场列表失败时白花一次模型调用。
        OverseasMarketService.Snapshot overseas = overseasMarketService.snapshot();
        GlobalPredictScorer.MarketContext marketContext = new GlobalPredictScorer.MarketContext(
                overseas.available() ? overseas.weightedPct() : null, null);

        List<String> universe = universeService.codes();
        if (universe.isEmpty()) {
            log.warn("次日预测：全市场列表为空，本轮跳过");
            return new GenerateResult(tradeDate, dryRun, 0, 0, 0, List.of(),
                    System.currentTimeMillis() - start);
        }

        List<StockQuote> quotes;
        try {
            quotes = tencentService.fetchQuotesBatch(universe);
        } catch (RuntimeException ex) {
            log.warn("次日预测：批量行情失败 err={}", ex.getMessage());
            return new GenerateResult(tradeDate, dryRun, universe.size(), 0, 0, List.of(),
                    System.currentTimeMillis() - start);
        }

        PredictFilters.Options options = PredictFilters.Options.from(config);
        List<StockQuote> candidates = new ArrayList<>();
        for (StockQuote quote : quotes) {
            if (quote != null && PredictFilters.rejectReason(quote, options) == null) {
                candidates.add(quote);
            }
        }
        candidates.sort(Comparator.comparingDouble(StockQuote::getTurnover).reversed());
        if (candidates.size() > config.getPrefilterTopN()) {
            candidates = new ArrayList<>(candidates.subList(0, config.getPrefilterTopN()));
        }

        int[] budget = {Math.max(0, config.getKlineFetchBudget())};
        nextKlineFetchAt = 0L;
        List<Scored> scored = new ArrayList<>();
        int minBars = Math.max(20, config.getMinBars());
        for (int i = 0; i < candidates.size(); i++) {
            StockQuote quote = candidates.get(i);
            String code = StockCode.normalize(quote.getCode());
            List<KlineItem> bars = klineCacheService.loadCached(code, minBars);
            if (bars.isEmpty()) {
                bars = fetchBars(code, minBars, budget);
            }
            if (bars.isEmpty()) {
                continue;
            }
            DailyBarAssembler.Result assembled = DailyBarAssembler.assemble(
                    tradeDate.toString(), bars, quote);
            if (!assembled.reliable() && budget[0] > 0) {
                List<KlineItem> refetched = fetchBars(code, minBars, budget);
                if (!refetched.isEmpty()) {
                    assembled = DailyBarAssembler.assemble(tradeDate.toString(), refetched, quote);
                }
            }
            if (assembled.bars().size() < minBars) {
                continue;
            }
            GlobalPredictScorer.Result result = GlobalPredictScorer.score(
                    quote, assembled.bars(), null, sentimentScore, marketContext);
            scored.add(new Scored(quote, assembled.bars(), result));
            if ((i + 1) % 50 == 0) {
                log.info("次日预测：已处理 {}/{}，已打分 {}，K线预算剩余 {}",
                        i + 1, candidates.size(), scored.size(), budget[0]);
            }
        }

        scored.sort(SCORE_ORDER);

        // 前 M 只搜新闻，再用新闻分重算
        int newsTopM = Math.max(0, Math.min(config.getNewsTopM(), scored.size()));
        List<AiNewsScoreService.Target> targets = new ArrayList<>();
        for (int i = 0; i < newsTopM; i++) {
            StockQuote quote = scored.get(i).quote;
            targets.add(new AiNewsScoreService.Target(quote.getCode(), quote.getName()));
        }
        // 宏观新闻只抓一次，既作为个股精判的背景，也作为全市场级加分
        MacroNewsService.MacroNews macro = targets.isEmpty()
                ? MacroNewsService.MacroNews.empty()
                : macroNewsService.scan();
        Map<String, StockNewsScore> newsByCode = new LinkedHashMap<>();
        if (!targets.isEmpty()) {
            for (StockNewsScore news : newsScoreService.scanForPrediction(targets, macro.promptLines())) {
                newsByCode.put(StockCode.normalize(news.getCode()), news);
            }
        }
        marketContext = new GlobalPredictScorer.MarketContext(
                overseas.available() ? overseas.weightedPct() : null,
                macro.available() ? macro.score() : null);

        int newsCovered = 0;
        for (Scored item : scored) {
            StockNewsScore news = newsByCode.get(StockCode.normalize(item.quote.getCode()));
            if (news != null) {
                item.news = news;
                newsCovered++;
            }
            // 无论有没有个股新闻都要重算：宏观/外盘对每只票都生效
            item.result = GlobalPredictScorer.score(
                    item.quote, item.bars, news == null ? null : news.getScore(),
                    sentimentScore, marketContext);
        }
        scored.sort(SCORE_ORDER);

        int outputK = Math.max(1, Math.min(config.getOutputTopK(), scored.size()));
        List<StockPredictDaily> items = new ArrayList<>();
        for (int i = 0; i < outputK; i++) {
            items.add(toEntity(scored.get(i), i + 1, tradeDate, sentimentScore, overseas, macro));
        }

        if (!dryRun && !items.isEmpty()) {
            store.replaceDaily(tradeDate, items);
            newsScoreService.cleanupArticles(config.getNewsKeepDays());
        }

        return new GenerateResult(tradeDate, dryRun, universe.size(), scored.size(), newsCovered,
                items, System.currentTimeMillis() - start);
    }

    private List<KlineItem> fetchBars(String code, int minBars, int[] budget) {
        if (budget[0] <= 0) {
            return List.of();
        }
        double rate = properties.getPredict().getKlineFetchRatePerSec();
        if (rate <= 0) {
            rate = 3;
        }
        long now = System.currentTimeMillis();
        long wait = nextKlineFetchAt - now;
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        }
        nextKlineFetchAt = System.currentTimeMillis() + (long) (1000 / rate);
        budget[0]--;
        try {
            return klineCacheService.load(code, minBars);
        } catch (RuntimeException ex) {
            log.debug("次日预测：拉取日K失败 code={} err={}", code, ex.getMessage());
            return List.of();
        }
    }

    private StockPredictDaily toEntity(Scored scored, int rank, LocalDate tradeDate,
                                       double sentimentScore,
                                       OverseasMarketService.Snapshot overseas,
                                       MacroNewsService.MacroNews macro) {
        StockQuote quote = scored.quote;
        GlobalPredictScorer.Result result = scored.result;
        PredictProbabilityService.Estimate estimate =
                probabilityService.estimate(result.score(), tradeDate);

        StockPredictDaily row = new StockPredictDaily();
        row.setTradeDate(tradeDate);
        row.setRankNo(rank);
        row.setCode(StockCode.normalize(quote.getCode()));
        row.setName(quote.getName() == null ? "" : quote.getName());
        row.setScore(result.score());
        row.setRawScore(BigDecimal.valueOf(result.rawScore()).setScale(2, RoundingMode.HALF_UP));
        row.setMaxScore(result.maxScore());
        row.setProbability(BigDecimal.valueOf(estimate.probability()).setScale(1, RoundingMode.HALF_UP));
        row.setProbSource(estimate.source());
        row.setCalibrationN(estimate.samples());
        row.setReason(truncate(String.join("、", result.reasons()), 500));
        row.setModelVersion(GlobalPredictScorer.MODEL_VERSION);

        StockNewsScore news = scored.news;
        row.setNewsSummary(news == null ? "" : truncate(news.getSummary(), 500));
        row.setNewsScore(news == null || news.getScore() == null ? 0 : news.getScore());
        row.setNewsConfidence(news == null || news.getConfidence() == null
                ? BigDecimal.ZERO
                : news.getConfidence().min(BigDecimal.ONE).max(BigDecimal.ZERO));
        row.setNewsSources(news == null || news.getHeadlines() == null
                || news.getHeadlines().isBlank() ? "[]" : news.getHeadlines());
        Map<String, Object> features = new LinkedHashMap<>(result.features());
        if (macro != null && macro.available()) {
            features.put("macroSummary", macro.summary());
            features.put("macroAiScored", macro.aiScored());
            features.put("macroHeadlines", macro.headlines().stream()
                    .map(AiWebSearchService.SearchHit::title).toList());
        }
        if (overseas != null && overseas.available()) {
            features.put("overseasMoves", overseas.moves());
            features.put("overseasSource", overseas.source());
        }
        row.setFeatures(writeJson(features));

        row.setMarketSentiment(Double.isNaN(sentimentScore)
                ? null
                : BigDecimal.valueOf(sentimentScore).setScale(2, RoundingMode.HALF_UP));
        row.setPrevClose(BigDecimal.valueOf(quote.getPrice()).setScale(3, RoundingMode.HALF_UP));
        row.setChangePct(BigDecimal.valueOf(quote.getChangePct()).setScale(2, RoundingMode.HALF_UP));
        row.setTurnoverRate(BigDecimal.valueOf(quote.getTurnoverRate()).setScale(2, RoundingMode.HALF_UP));
        row.setAmountWan(BigDecimal.valueOf(quote.getTurnover()).setScale(2, RoundingMode.HALF_UP));
        boolean limitUp = PredictFilters.isLimitUpAtClose(quote.getCode(), quote.getName(), quote);
        row.setLimitUpAtClose(limitUp ? 1 : 0);
        row.setBuyable(limitUp || PredictFilters.isSpecialTreatment(quote.getName()) ? 0 : 1);
        row.setNextTradeDate(calendar.nextTradingDay(tradeDate));
        row.setEvalStatus("PENDING");
        row.setEvalRetry(0);
        LocalDateTime now = LocalDateTime.now();
        row.setCreateTime(now);
        row.setUpdateTime(now);
        return row;
    }

    // ---------- 回填 ----------

    /**
     * 回填已到期的预测。
     *
     * <p>预期 T+1 日就是今天或更早的 PENDING 行，逐只读日 K 找对应交易日的 bar。
     * 找不到（停牌/数据缺失）时原地重试；超过 5 个交易日仍无数据标记 NO_DATA，
     * 避免永远卡在 PENDING 里。</p>
     */
    public void evaluatePending(LocalDate today) {
        List<StockPredictDaily> pending = store.listPending(MAX_PENDING_PER_RUN);
        if (pending.isEmpty()) {
            return;
        }
        int evaluated = 0;
        int noData = 0;
        for (StockPredictDaily row : pending) {
            try {
                LocalDate expected = row.getNextTradeDate() != null
                        ? row.getNextTradeDate()
                        : calendar.nextTradingDay(row.getTradeDate());
                if (expected.isAfter(today)) {
                    continue;
                }
                // 当天收盘前不评估，避免把盘中价当成收盘价
                if (expected.equals(today)
                        && calendar.now().toLocalTime().isBefore(LocalTime.of(15, 5))) {
                    continue;
                }
                if (fill(row, expected)) {
                    evaluated++;
                } else {
                    row.setNextTradeDate(expected);
                    int retry = row.getEvalRetry() == null ? 1 : row.getEvalRetry() + 1;
                    row.setEvalRetry(retry);
                    if (tradingDaysSince(expected, today) > MAX_NO_DATA_TRADING_DAYS) {
                        row.setEvalStatus("NO_DATA");
                        noData++;
                    }
                    store.update(row);
                }
            } catch (RuntimeException ex) {
                log.debug("次日预测：回填失败 code={} err={}", row.getCode(), ex.getMessage());
            }
        }
        if (evaluated > 0 || noData > 0) {
            log.info("次日预测回填：成功={} 无数据={} 待处理={}",
                    evaluated, noData, pending.size() - evaluated - noData);
        }
    }

    /** 找到 T+1 bar 并写回；成功返回 true。 */
    private boolean fill(StockPredictDaily row, LocalDate expected) {
        if (row.getPrevClose() == null || row.getPrevClose().doubleValue() <= 0) {
            return false;
        }
        List<KlineItem> bars = klineCacheService.load(row.getCode(), 30);
        KlineItem bar = null;
        for (int i = bars.size() - 1; i >= 0; i--) {
            if (expected.toString().equals(bars.get(i).getDate())) {
                bar = bars.get(i);
                break;
            }
        }
        if (bar == null || bar.getClose() <= 0) {
            return false;
        }

        PredictEvaluation.Result result = PredictEvaluation.evaluate(
                row.getPrevClose().doubleValue(), bar,
                PredictFilters.limitUpPct(row.getCode(), row.getName()));

        row.setNextTradeDate(expected);
        row.setNextOpen(BigDecimal.valueOf(result.nextOpen()).setScale(3, RoundingMode.HALF_UP));
        row.setNextClose(BigDecimal.valueOf(result.nextClose()).setScale(3, RoundingMode.HALF_UP));
        row.setNextReturnPct(pct(result.nextReturnPct()));
        row.setNextOpenReturnPct(pct(result.nextOpenReturnPct()));
        row.setNextDayChangePct(pct(result.nextReturnPct()));
        row.setNextLimitUpAtOpen(result.limitUpAtOpen() ? 1 : 0);
        row.setHit(result.hit());
        row.setHitClose(result.hitClose());
        row.setEvalStatus("EVALUATED");
        store.update(row);
        return true;
    }

    private static BigDecimal pct(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    /** {@code from} 之后到 {@code today}（含）经过的交易日数。 */
    private int tradingDaysSince(LocalDate from, LocalDate today) {
        int days = 0;
        LocalDate date = from;
        while (date.isBefore(today) && days <= MAX_NO_DATA_TRADING_DAYS + 1) {
            date = calendar.nextTradingDay(date);
            days++;
        }
        return days;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static final Comparator<Scored> SCORE_ORDER =
            Comparator.comparingInt((Scored s) -> s.result.score()).reversed()
                    .thenComparing(Comparator.comparingDouble(
                            (Scored s) -> s.quote.getTurnover()).reversed());

    /** 中间态：行情 + 修正后的 K 线 + 打分结果 +（可选）新闻。 */
    private static final class Scored {
        private final StockQuote quote;
        private final List<KlineItem> bars;
        private GlobalPredictScorer.Result result;
        private StockNewsScore news;

        private Scored(StockQuote quote, List<KlineItem> bars,
                       GlobalPredictScorer.Result result) {
            this.quote = quote;
            this.bars = bars;
            this.result = result;
        }
    }
}
