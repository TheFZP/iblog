package com.iblog.module.paper.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标口径单测：固定输入 → 明确期望值，防止后续调参把手算结果改坏。
 */
class IndicatorsTest {

    @Test
    void smaUsesLastPeriod() {
        assertEquals(4.0, Indicators.sma(new double[]{1, 2, 3, 4, 5}, 3), 1e-9);
        assertTrue(Double.isNaN(Indicators.sma(new double[]{1, 2}, 3)));
    }

    @Test
    void macdPositiveOnRisingSeries() {
        double[] closes = new double[40];
        for (int i = 0; i < closes.length; i++) {
            closes[i] = i + 1;
        }
        Indicators.Macd macd = Indicators.macd(closes);
        assertTrue(macd.dif() > 0);
        assertTrue(macd.dea() > 0);
        assertTrue(macd.hist() > 0);
    }

    @Test
    void rsiIsHundredWhenOnlyGains() {
        double[] closes = new double[20];
        for (int i = 0; i < closes.length; i++) {
            closes[i] = i + 1;
        }
        assertEquals(100.0, Indicators.rsi(closes, 14), 1e-9);
    }

    @Test
    void volumeRatioComparesWithPreviousAverage() {
        double[] volumes = {100, 100, 100, 100, 100, 200};
        assertEquals(2.0, Indicators.volumeRatio(volumes, 5), 1e-9);
    }
}
