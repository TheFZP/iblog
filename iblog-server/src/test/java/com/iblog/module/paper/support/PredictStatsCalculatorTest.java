package com.iblog.module.paper.support;

import com.iblog.module.stock.entity.StockPredictDaily;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PredictStatsCalculatorTest {

    private static StockPredictDaily row(LocalDate date, int score, int probability,
                                         String status, Integer hit, Integer hitClose,
                                         double openReturn, double closeReturn) {
        StockPredictDaily row = new StockPredictDaily();
        row.setTradeDate(date);
        row.setScore(score);
        row.setProbability(BigDecimal.valueOf(probability));
        row.setEvalStatus(status);
        row.setHit(hit);
        row.setHitClose(hitClose);
        row.setNextOpenReturnPct(BigDecimal.valueOf(openReturn));
        row.setNextReturnPct(BigDecimal.valueOf(closeReturn));
        return row;
    }

    @Test
    void computesOverallAndDailyAccuracy() {
        List<StockPredictDaily> rows = List.of(
                row(LocalDate.of(2026, 11, 10), 85, 60, "EVALUATED", 1, 1, 2.0, 3.0),
                row(LocalDate.of(2026, 11, 10), 75, 55, "EVALUATED", 0, 1, -1.0, 0.5),
                row(LocalDate.of(2026, 11, 11), 65, 50, "EVALUATED", 0, 0, -2.0, -3.0));

        PredictStatsCalculator.Stats stats = PredictStatsCalculator.compute(rows, 30);
        assertEquals(3, stats.totalSamples());
        assertEquals(3, stats.evaluated());
        assertEquals(1, stats.hits());
        assertEquals(33.33, stats.hitRatePct(), 0.01);
        assertEquals(2, stats.hitCloseHits());
        assertEquals(66.67, stats.hitCloseRatePct(), 0.01);
        assertEquals(-0.33, stats.avgOpenReturnPct(), 0.01);
        assertEquals(0.17, stats.avgNextReturnPct(), 0.01);
        assertEquals(2, stats.daily().size());
        assertEquals("2026-11-10", stats.daily().get(0).date());
        assertEquals(50.0, stats.daily().get(0).hitRatePct(), 0.01);
    }

    @Test
    void pendingAndNoDataAreExcludedFromDenominator() {
        List<StockPredictDaily> rows = List.of(
                row(LocalDate.of(2026, 11, 10), 85, 60, "EVALUATED", 1, 1, 1.0, 1.0),
                row(LocalDate.of(2026, 11, 11), 85, 60, "PENDING", null, null, 0, 0),
                row(LocalDate.of(2026, 11, 12), 85, 60, "NO_DATA", null, null, 0, 0));

        PredictStatsCalculator.Stats stats = PredictStatsCalculator.compute(rows, 30);
        assertEquals(3, stats.totalSamples());
        assertEquals(1, stats.evaluated());
        assertEquals(100.0, stats.hitRatePct(), 0.01);
    }

    @Test
    void emptyInputDoesNotDivideByZero() {
        PredictStatsCalculator.Stats stats = PredictStatsCalculator.compute(List.of(), 30);
        assertEquals(0, stats.totalSamples());
        assertEquals(0, stats.evaluated());
        assertEquals(0, stats.hitRatePct(), 1e-9);
        assertTrue(stats.daily().isEmpty());
    }

    @Test
    void bucketsGroupByScoreAndProbability() {
        List<StockPredictDaily> rows = List.of(
                row(LocalDate.of(2026, 11, 10), 85, 70, "EVALUATED", 1, 1, 2, 2),
                row(LocalDate.of(2026, 11, 10), 82, 62, "EVALUATED", 0, 0, -1, -1),
                row(LocalDate.of(2026, 11, 10), 55, 45, "EVALUATED", 1, 0, 1, -0.5));

        PredictStatsCalculator.Stats stats = PredictStatsCalculator.compute(rows, 30);
        PredictStatsCalculator.Bucket score80 = stats.byScore().stream()
                .filter(b -> "80-89".equals(b.label())).findFirst().orElseThrow();
        assertEquals(2, score80.samples());
        assertEquals(50.0, score80.hitRatePct(), 0.01);

        PredictStatsCalculator.Bucket prob60 = stats.byProbability().stream()
                .filter(b -> "60-69".equals(b.label())).findFirst().orElseThrow();
        assertEquals(1, prob60.samples());
        assertEquals(0.0, prob60.hitRatePct(), 0.01);
        assertEquals(1, stats.byProbability().stream()
                .filter(b -> "70+".equals(b.label())).findFirst().orElseThrow().hits());
    }
}
