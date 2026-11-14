package com.iblog.module.paper.support;

import com.iblog.module.stock.entity.StockPredictDaily;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 次日预测准确率统计（纯函数）。
 *
 * <p>口径：{@code hit} 是可交易口径（T+1 开盘买 → 收盘卖收益 &gt; 0），
 * {@code hitClose} 是收盘对收盘；平盘计入分母但不算命中；PENDING / NO_DATA 不计入分母。</p>
 */
public final class PredictStatsCalculator {

    private PredictStatsCalculator() {
    }

    public record Bucket(String label, int samples, int evaluated, int hits,
                         double hitRatePct, double avgOpenReturnPct) {
    }

    public record Daily(String date, int samples, int evaluated, int hits,
                        double hitRatePct, double avgOpenReturnPct, double avgCloseReturnPct) {
    }

    public record Stats(int days, int totalSamples, int evaluated, int hits,
                        double hitRatePct, int hitCloseHits, double hitCloseRatePct,
                        double avgNextReturnPct, double avgOpenReturnPct,
                        List<Daily> daily, List<Bucket> byScore, List<Bucket> byProbability) {
    }

    public static Stats compute(List<StockPredictDaily> rows, int days) {
        List<StockPredictDaily> list = rows == null ? List.of() : rows;

        int evaluated = 0;
        int hits = 0;
        int hitCloseHits = 0;
        double nextSum = 0;
        int nextCount = 0;
        double openSum = 0;
        int openCount = 0;

        Map<LocalDate, List<StockPredictDaily>> byDate = new TreeMap<>();
        for (StockPredictDaily row : list) {
            if (row.getTradeDate() != null) {
                byDate.computeIfAbsent(row.getTradeDate(), k -> new ArrayList<>()).add(row);
            }
            if (!"EVALUATED".equals(row.getEvalStatus())) {
                continue;
            }
            evaluated++;
            if (row.getHit() != null && row.getHit() == 1) {
                hits++;
            }
            if (row.getHitClose() != null && row.getHitClose() == 1) {
                hitCloseHits++;
            }
            if (row.getNextReturnPct() != null) {
                nextSum += row.getNextReturnPct().doubleValue();
                nextCount++;
            }
            if (row.getNextOpenReturnPct() != null) {
                openSum += row.getNextOpenReturnPct().doubleValue();
                openCount++;
            }
        }

        List<Daily> daily = new ArrayList<>();
        for (Map.Entry<LocalDate, List<StockPredictDaily>> entry : byDate.entrySet()) {
            daily.add(dailyBucket(entry.getKey(), entry.getValue()));
        }

        return new Stats(days, list.size(), evaluated, hits, rate(hits, evaluated),
                hitCloseHits, rate(hitCloseHits, evaluated),
                avg(nextSum, nextCount), avg(openSum, openCount),
                daily, scoreBuckets(list), probabilityBuckets(list));
    }

    private static Daily dailyBucket(LocalDate date, List<StockPredictDaily> rows) {
        int evaluated = 0;
        int hits = 0;
        double openSum = 0;
        int openCount = 0;
        double closeSum = 0;
        int closeCount = 0;
        for (StockPredictDaily row : rows) {
            if (!"EVALUATED".equals(row.getEvalStatus())) {
                continue;
            }
            evaluated++;
            if (row.getHit() != null && row.getHit() == 1) {
                hits++;
            }
            if (row.getNextOpenReturnPct() != null) {
                openSum += row.getNextOpenReturnPct().doubleValue();
                openCount++;
            }
            if (row.getNextReturnPct() != null) {
                closeSum += row.getNextReturnPct().doubleValue();
                closeCount++;
            }
        }
        return new Daily(date.toString(), rows.size(), evaluated, hits, rate(hits, evaluated),
                avg(openSum, openCount), avg(closeSum, closeCount));
    }

    /** 分数档：90-100、80-89……0-9，只保留有样本的档。 */
    public static List<Bucket> scoreBuckets(List<StockPredictDaily> rows) {
        List<Bucket> buckets = new ArrayList<>();
        for (int lower = 90; lower >= 0; lower -= 10) {
            final int min = lower;
            final int max = Math.min(100, lower + 9);
            String label = min + "-" + max;
            buckets.add(bucket(label, rows, row -> {
                Integer score = row.getScore();
                return score != null && score >= min && score <= max;
            }));
        }
        buckets.removeIf(b -> b.samples() == 0);
        return buckets;
    }

    /** 概率档：70+、60-69、50-59、40-49、<40。 */
    public static List<Bucket> probabilityBuckets(List<StockPredictDaily> rows) {
        List<Bucket> buckets = new ArrayList<>();
        buckets.add(bucket("70+", rows, row -> inProbability(row, 70, 100)));
        buckets.add(bucket("60-69", rows, row -> inProbability(row, 60, 69)));
        buckets.add(bucket("50-59", rows, row -> inProbability(row, 50, 59)));
        buckets.add(bucket("40-49", rows, row -> inProbability(row, 40, 49)));
        buckets.add(bucket("<40", rows, row -> inProbability(row, 0, 39)));
        buckets.removeIf(b -> b.samples() == 0);
        return buckets;
    }

    private static boolean inProbability(StockPredictDaily row, int lower, int upper) {
        if (row.getProbability() == null) {
            return false;
        }
        double p = row.getProbability().doubleValue();
        return p >= lower && p <= upper;
    }

    private static Bucket bucket(String label, List<StockPredictDaily> rows,
                                 java.util.function.Predicate<StockPredictDaily> filter) {
        int samples = 0;
        int evaluated = 0;
        int hits = 0;
        double openSum = 0;
        int openCount = 0;
        for (StockPredictDaily row : rows) {
            if (!filter.test(row)) {
                continue;
            }
            samples++;
            if (!"EVALUATED".equals(row.getEvalStatus())) {
                continue;
            }
            evaluated++;
            if (row.getHit() != null && row.getHit() == 1) {
                hits++;
            }
            if (row.getNextOpenReturnPct() != null) {
                openSum += row.getNextOpenReturnPct().doubleValue();
                openCount++;
            }
        }
        return new Bucket(label, samples, evaluated, hits, rate(hits, evaluated),
                avg(openSum, openCount));
    }

    private static double rate(int hits, int evaluated) {
        return evaluated == 0 ? 0 : round(hits * 100.0 / evaluated);
    }

    private static double avg(double sum, int count) {
        return count == 0 ? 0 : round(sum / count);
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
