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
 * <p>满分 100，新闻情绪额外 +10 / −8，最终按可用满分归一化到 0~100。
 * 权重选择偏「收盘后的日线形态 + 量能」：15:30 扫描时当日盘口已经结束，
 * 盘中分时/资金流的数据在免费源上既不稳定也没有历史可比性。</p>
 *
 * <p>输出同时带因子快照（{@code features}），为后续逻辑回归/机器学习留训练数据；
 * 模型版本写在这条快照里，换权重后可以区分新旧样本。</p>
 */
public final class GlobalPredictScorer {

    public static final String MODEL_VERSION = "rule-v1";

    /** 基础因子满分。 */
    public static final int BASE_MAX_SCORE = 100;
    /** 新闻因子的最大加分（正向）。 */
    public static final int NEWS_MAX_BONUS = 10;

    private GlobalPredictScorer() {
    }

    /**
     * @param quote            T 日收盘行情
     * @param bars             日 K（升序，最后一根是 T 日；由 {@link DailyBarAssembler} 修正）
     * @param newsScore        新闻情绪分 -100~100；null 表示没有可用新闻
     * @param marketSentiment  市场情绪 0~100；NaN 表示不可用
     */
    public static Result score(StockQuote quote, List<KlineItem> bars,
                               Integer newsScore, double marketSentiment) {
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

        int maxScore = BASE_MAX_SCORE + (newsScore == null ? 0 : NEWS_MAX_BONUS);
        double rawScore = base + newsPoints;
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
