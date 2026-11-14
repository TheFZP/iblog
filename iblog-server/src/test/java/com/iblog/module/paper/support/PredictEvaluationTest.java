package com.iblog.module.paper.support;

import com.iblog.module.stock.model.KlineItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PredictEvaluationTest {

    private static KlineItem bar(double open, double close) {
        KlineItem item = new KlineItem();
        item.setOpen(open);
        item.setClose(close);
        item.setHigh(Math.max(open, close));
        item.setLow(Math.min(open, close));
        return item;
    }

    @Test
    void computesBothReturnMeasures() {
        PredictEvaluation.Result result = PredictEvaluation.evaluate(10, bar(10.2, 10.5), 10);
        assertEquals(10.5, result.nextClose(), 1e-9);
        assertEquals(5.0, result.nextReturnPct(), 1e-9);
        assertEquals(2.94, result.nextOpenReturnPct(), 0.01);
        assertEquals(1, result.hit());
        assertEquals(1, result.hitClose());
        assertFalse(result.limitUpAtOpen());
    }

    @Test
    void flatDayCountsAsMissButStaysInDenominator() {
        PredictEvaluation.Result result = PredictEvaluation.evaluate(10, bar(10, 10), 10);
        assertEquals(0, result.hit());
        assertEquals(0, result.hitClose());
    }

    @Test
    void limitUpAtOpenCannotBeBought() {
        // 主板 10%：开盘直接 11 一字板，收盘 11.2
        PredictEvaluation.Result result = PredictEvaluation.evaluate(10, bar(11, 11.2), 10);
        assertTrue(result.limitUpAtOpen());
        assertEquals(0, result.hit());
        assertEquals(1, result.hitClose());
    }

    @Test
    void zeroOpenFallsBackToClose() {
        PredictEvaluation.Result result = PredictEvaluation.evaluate(10, bar(0, 10.5), 10);
        assertEquals(10.5, result.nextOpen(), 1e-9);
        assertEquals(0.0, result.nextOpenReturnPct(), 1e-9);
    }
}
