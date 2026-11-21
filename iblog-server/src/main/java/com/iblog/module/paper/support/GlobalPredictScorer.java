package com.iblog.module.paper.support;

import com.iblog.module.stock.model.KlineItem;
import com.iblog.module.stock.model.StockQuote;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全市场次日看涨打分（纯函数，无网络/无数据库）。
 *
 * <p>满分 100，新闻情绪额外 +10 / −8，外盘 + 宏观再额外 ±6，最终按可用满分归一化到 0~100。
 * 外盘/宏观是全市场级因子：它调整当天的整体松紧（影响概率与买入阈值），不改变同一天内部的个股排序。
 * 权重选择偏「收盘后的日线形态 + 量能」：15:30 扫描时当日盘口已经结束，
 * 盘中分时/资金流的数据在免费源上既不稳定也没有历史可比性。</p>
 *
 * <p>输出同时带因子快照（{@code features}），为后续逻辑回归/机器学习留训练数据；
 * 模型版本写在这条快照里，换权重后可以区分新旧样本。</p>
 */
public final class GlobalPredictScorer {

    public static final String MODEL_VERSION = "rule-v2";

    /** 基础因子满分。 */
    public static final int BASE_MAX_SCORE = 100;
    /** 新闻因子的最大加分（正向）。 */
    public static final int NEWS_MAX_BONUS = 10;
    /** 外盘 + 宏观合成的全市场因子最大正向加分。 */
    public static final int MARKET_MAX_BONUS = 6;

    private GlobalPredictScorer() {
    }

    /**
     * 全市场级背景：隔夜外盘涨跌与宏观新闻情绪。
     *
     * <p>这两个值对当天所有股票相同，作用是「整体收紧或放宽」，不会改变同一榜单内部的排序；
     * 真正决定名次的是个股 K 线形态与个股新闻。它的价值体现在概率、买入阈值与事后复盘上。</p>
     *
     * @param overseasPct 外盘合成涨跌幅（%）；null 表示没有数据
     * @param macroScore  宏观新闻情绪 -100~100；null 表示没抓到新闻
     */
    public record MarketContext(Double overseasPct, Integer macroScore) {

        public boolean isEmpty() {
            return overseasPct == null && macroScore == null;
        }
    }

    /**
     * @param quote            T 日收盘行情
     * @param bars             日 K（升序，最后一根是 T 日；由 {@link DailyBarAssembler} 修正）
     * @param newsScore        新闻情绪分 -100~100；null 表示没有可用新闻
     * @param marketSentiment  市场情绪 0~100；NaN 表示不可用
     */
    public static Result score(StockQuote quote, List<KlineItem> bars,
                               Integer newsScore, double marketSentiment) {
        return score(quote, bars, newsScore, marketSentiment, null);
    }

    /**
     * 带外盘/宏观背景的打分。
     *
     * @param marketContext 全市场背景；null 或两项都为空时与旧版行为完全一致
     */
    public static Result score(StockQuote quote, List<KlineItem> bars,
                               Integer newsScore, double marketSentiment,
                               MarketContext marketContext) {
        List<String> reasons = new ArrayList<>();
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("modelVersion", MODEL_VERSION);

        if (quote == null || bars == null || bars.size() < 2) {
            features.put("insufficientBars", bars == null ? 0 : bars.size());
            return new Result(0, 0, BASE_MAX_SCORE, List.of("数据不足"), features);
        }

        double[] closes = bars.stream().mapToDouble(KlineItem::getClose).toArray();
        double[] volumes = bars.stream().mapToDouble(KlineItem::getVolume).toArray();
        KlineItem last = bars.get(bars.size() - 1);
        double close = quote.getPrice() > 0 ? quote.getPrice() : last.getClose();

        int base = 0;

        // 1. 均线多头 / 仅站上 MA20
        double ma5 = Indicators.sma(closes, 5);
        double ma10 = Indicators.sma(closes, 10);
        double ma20 = Indicators.sma(closes, 20);
        features.put("ma5", round(ma5));
        features.put("ma10", round(ma10));
        features.put("ma20", round(ma20));
        if (!Double.isNaN(ma20) && close > ma5 && ma5 > ma10 && ma10 > ma20) {
            base += 18;
            reasons.add("均线多头");
        } else if (!Double.isNaN(ma20) && close > ma20) {
            base += 6;
            reasons.add("站上MA20");
        }

        // 2. MACD 红柱
        Indicators.Macd macd = Indicators.macd(closes);
        features.put("macdHist", round(macd.hist()));
        if (!Double.isNaN(macd.hist()) && macd.hist() > 0) {
            base += 12;
            reasons.add("MACD红柱");
        }

        // 3. 收盘位置：收在当日振幅上方 70% 以上
        double position = closePosition(last, close);
        features.put("closePosition", round(position));
        if (!Double.isNaN(position) && position >= 0.7) {
            base += 12;
            reasons.add("收盘接近最高");
        }

        // 4. 量比（收盘后口径 = 当日量 / 前 5 日均量）
        double volumeRatio = Indicators.volumeRatio(volumes, 5, TradeCalendar.SESSION_MINUTES,
                TradeCalendar.SESSION_MINUTES);
        features.put("volumeRatio", round(volumeRatio));
        if (!Double.isNaN(volumeRatio) && volumeRatio >= 1.2) {
            base += 10;
            reasons.add("量比" + String.format("%.1f", volumeRatio));
        }

        // 5. MA5 三天斜率向上
        double ma5Prev = closes.length >= 8
                ? Indicators.sma(Arrays.copyOf(closes, closes.length - 3), 5)
                : Double.NaN;
        features.put("ma5Prev", round(ma5Prev));
        if (!Double.isNaN(ma5) && !Double.isNaN(ma5Prev) && ma5 > ma5Prev) {
            base += 9;
            reasons.add("MA5上行");
        }

        // 6. RSI 40~70（有动能但未超买）
        double rsi = Indicators.rsi(closes, 14);
        features.put("rsi", round(rsi));
        if (!Double.isNaN(rsi) && rsi >= 40 && rsi <= 70) {
            base += 8;
            reasons.add("RSI适中");
        }

        // 7. 当日涨幅 0~7%
        features.put("changePct", round(quote.getChangePct()));
        if (quote.getChangePct() >= 0 && quote.getChangePct() <= 7) {
            base += 6;
            reasons.add("当日涨幅温和");
        }

        // 8. 换手率 3%~15%
        features.put("turnoverRate", round(quote.getTurnoverRate()));
        if (quote.getTurnoverRate() >= 3 && quote.getTurnoverRate() <= 15) {
            base += 6;
            reasons.add("换手活跃");
        }

        // 9. 市场情绪
        int sentimentPoints = sentimentPoints(marketSentiment);
        features.put("marketSentiment", round(marketSentiment));
        features.put("sentimentPoints", sentimentPoints);
        if (sentimentPoints > 0) {
            base += sentimentPoints;
            reasons.add("市场情绪" + (int) Math.round(marketSentiment));
        }

        // 10. 流通市值 30~300 亿
        features.put("floatMarketCap", round(quote.getFloatMarketCap()));
        if (quote.getFloatMarketCap() >= 30 && quote.getFloatMarketCap() <= 300) {
            base += 4;
            reasons.add("市值适中");
        }

        // 11. 近 20 日有涨停
        boolean limitUpRecently = hasLimitUpRecently(bars, quote, 20);
        features.put("limitUp20", limitUpRecently);
        if (limitUpRecently) {
            base += 4;
            reasons.add("近20日有涨停");
        }

        features.put("baseScore", base);

        // 12. 新闻：有则最多 +10，利空 -8；没有新闻不参与归一化分母
        int newsPoints = newsPoints(newsScore);
        features.put("newsScore", newsScore == null ? null : newsScore);
        features.put("newsPoints", newsPoints);
        if (newsPoints > 0) {
            reasons.add("新闻+" + newsPoints);
        } else if (newsPoints < 0) {
            reasons.add("新闻" + newsPoints);
        }

        // 13. 外盘 + 宏观：市场级背景，作用在「整体松紧」而不是个股排序
        boolean hasMarket = marketContext != null && !marketContext.isEmpty();
        int overseasPoints = overseasPoints(marketContext == null ? null : marketContext.overseasPct());
        int macroPoints = macroPoints(marketContext == null ? null : marketContext.macroScore());
        int marketPoints = marketPoints(marketContext);
        features.put("overseasPct", marketContext == null || marketContext.overseasPct() == null
                ? null : round(marketContext.overseasPct()));
        features.put("overseasPoints", overseasPoints);
        features.put("macroScore", marketContext == null ? null : marketContext.macroScore());
        features.put("macroPoints", macroPoints);
        features.put("marketPoints", marketPoints);
        if (overseasPoints != 0) {
            String pct = String.format(java.util.Locale.ROOT, "%+.1f%%", marketContext.overseasPct());
            reasons.add(overseasPoints > 0 ? "外盘走强(" + pct + ")" : "外盘走弱(" + pct + ")");
        }
        if (macroPoints != 0) {
            reasons.add(macroPoints > 0 ? "宏观偏暖" : "宏观偏冷");
        }

        int maxScore = BASE_MAX_SCORE + (newsScore == null ? 0 : NEWS_MAX_BONUS)
                + (hasMarket ? MARKET_MAX_BONUS : 0);
        double rawScore = base + newsPoints + marketPoints;
        int score = (int) Math.round(rawScore * BASE_MAX_SCORE / maxScore);
        score = Math.max(0, Math.min(100, score));

        if (reasons.isEmpty()) {
            reasons.add("信号一般");
        }
        return new Result(score, base, maxScore, List.copyOf(reasons), features);
    }

    /** 收盘在当日 [low, high] 中的位置 0~1；一字板/数据异常返回 NaN。 */
    static double closePosition(KlineItem bar, double close) {
        double range = bar.getHigh() - bar.getLow();
        if (range <= 0) {
            return Double.NaN;
        }
        return Math.max(0, Math.min(1, (close - bar.getLow()) / range));
    }

    static int sentimentPoints(double sentiment) {
        if (Double.isNaN(sentiment)) {
            return 0;
        }
        if (sentiment >= 60) {
            return 5;
        }
        if (sentiment >= 45) {
            return 3;
        }
        if (sentiment >= 30) {
            return 1;
        }
        return 0;
    }

    static int newsPoints(Integer newsScore) {
        if (newsScore == null) {
            return 0;
        }
        if (newsScore >= 20) {
            return NEWS_MAX_BONUS;
        }
        if (newsScore > 0) {
            return 5;
        }
        if (newsScore <= -20) {
            return -8;
        }
        if (newsScore < 0) {
            return -4;
        }
        return 0;
    }

    /**
     * 外盘 + 宏观合成的市场级加分，封顶 ±{@link #MARKET_MAX_BONUS}。
     *
     * <p>单独开成公开方法，是为了让模拟盘选股也能用同一套口径（它有自己的打分体系，
     * 不做归一化，只把这几分的边际影响加到总分上）。</p>
     */
    public static int marketPoints(MarketContext context) {
        if (context == null) {
            return 0;
        }
        int points = overseasPoints(context.overseasPct()) + macroPoints(context.macroScore());
        return Math.max(-MARKET_MAX_BONUS, Math.min(MARKET_MAX_BONUS, points));
    }

    /**
     * 外盘涨跌幅 → 加分。
     *
     * <p>阈值刻意宽松：A 股对外盘的映射不是线性的，外盘跌 0.3% 通常只影响开盘情绪，
     * 半个小时就修复了；只有 1% 以上才值得动分数。</p>
     */
    static int overseasPoints(Double weightedPct) {
        if (weightedPct == null || Double.isNaN(weightedPct)) {
            return 0;
        }
        if (weightedPct >= 1.5) {
            return 5;
        }
        if (weightedPct >= 0.5) {
            return 3;
        }
        if (weightedPct > -0.5) {
            return 0;
        }
        if (weightedPct > -1.5) {
            return -3;
        }
        return -5;
    }

    /** 宏观新闻情绪 → 加分，权重比外盘更低（新闻更嘈杂）。 */
    static int macroPoints(Integer macroScore) {
        if (macroScore == null) {
            return 0;
        }
        if (macroScore >= 20) {
            return 3;
        }
        if (macroScore > 0) {
            return 1;
        }
        if (macroScore <= -20) {
            return -3;
        }
        if (macroScore < 0) {
            return -1;
        }
        return 0;
    }

    /** 近 {@code lookback} 根内是否出现过涨停（收盘=最高且涨幅接近涨停幅度）。 */
    static boolean hasLimitUpRecently(List<KlineItem> bars, StockQuote quote, int lookback) {
        double limitPct = PredictFilters.limitUpPct(quote.getCode(), quote.getName());
        int start = Math.max(1, bars.size() - lookback);
        for (int i = start; i < bars.size(); i++) {
            KlineItem bar = bars.get(i);
            KlineItem prev = bars.get(i - 1);
            if (prev.getClose() <= 0) {
                continue;
            }
            double pct = (bar.getClose() - prev.getClose()) / prev.getClose() * 100;
            if (pct >= limitPct - 0.15 && bar.getClose() >= bar.getHigh() - 0.001) {
                return true;
            }
        }
        return false;
    }

    private static double round(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return value;
        }
        return Math.round(value * 10000) / 10000.0;
    }

    /**
     * @param score    最终分 0~100
     * @param rawScore 基础因子原始分（不含新闻）
     * @param maxScore 本次归一化使用的满分
     * @param reasons  命中理由
     * @param features 因子快照
     */
    public record Result(int score, double rawScore, int maxScore,
                         List<String> reasons, Map<String, Object> features) {
    }
}
