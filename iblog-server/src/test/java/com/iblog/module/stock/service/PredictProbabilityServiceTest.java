package com.iblog.module.stock.service;

import com.iblog.config.IblogProperties;
import com.iblog.module.paper.support.TradeCalendar;
import com.iblog.module.stock.entity.StockPredictDaily;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PredictProbabilityServiceTest {

    private final IblogProperties properties = new IblogProperties();
    private final TradeCalendar calendar = new TradeCalendar(properties);
    private final NextDayPredictStore store = mock(NextDayPredictStore.class);
    private final PredictProbabilityService service =
            new PredictProbabilityService(store, calendar, properties);

    private static StockPredictDaily sample(int score, int hit) {
        StockPredictDaily row = new StockPredictDaily();
        row.setTradeDate(LocalDate.of(2026, 11, 1));
        row.setScore(score);
        row.setHit(hit);
        row.setEvalStatus("EVALUATED");
        return row;
    }

    @Test
    void heuristicIsMonotonicAndBounded() {
        double low = PredictProbabilityService.heuristic(10);
        double mid = PredictProbabilityService.heuristic(50);
        double high = PredictProbabilityService.heuristic(100);
        assertTrue(low < mid && mid < high);
        assertTrue(low >= 30 && high <= 75);
    }

    @Test
    void smallSampleStaysHeuristic() {
        when(store.listEvaluated(any(), any())).thenReturn(List.of(sample(80, 1), sample(82, 1)));
        PredictProbabilityService.Estimate estimate =
                service.estimate(85, LocalDate.of(2026, 11, 14));
        assertEquals("HEURISTIC", estimate.source());
        assertEquals(2, estimate.samples());
        assertEquals(Math.round(PredictProbabilityService.heuristic(85) * 10) / 10.0,
                estimate.probability(), 1e-9);
    }

    @Test
    void enoughSamplesSwitchToCalibrated() {
        List<StockPredictDaily> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            rows.add(sample(85, 1));
        }
        when(store.listEvaluated(any(), any())).thenReturn(rows);
        PredictProbabilityService.Estimate estimate =
                service.estimate(85, LocalDate.of(2026, 11, 14));
        assertEquals("CALIBRATED", estimate.source());
        assertEquals(40, estimate.samples());
        // 40/40 命中被 k=30 平滑，不会直接给 100%
        assertTrue(estimate.probability() > 75 - 0.001 || estimate.probability() == 75);
        assertEquals(75, estimate.probability(), 1e-9);
    }

    @Test
    void samplesOutsideScoreBandAreIgnored() {
        List<StockPredictDaily> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            rows.add(sample(30, 0));
        }
        when(store.listEvaluated(any(), any())).thenReturn(rows);
        PredictProbabilityService.Estimate estimate =
                service.estimate(85, LocalDate.of(2026, 11, 14));
        assertEquals("HEURISTIC", estimate.source());
        assertEquals(0, estimate.samples());
    }
}
