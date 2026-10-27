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

    /**
     * 收盘口径下与旧算法等价：已经走完 240 分钟，量比就是「当日量 ÷ 前 5 日均量」。
     * 这条是兼容性护栏，防止改归一化时把收盘后的结果改坏。
     */
    @Test
    void volumeRatioAtCloseMatchesFullDayAverage() {
        double[] volumes = {100, 100, 100, 100, 100, 200};
        assertEquals(2.0, Indicators.volumeRatio(volumes, 5, 240, 240), 1e-9);
    }

    /**
     * 盘中口径：当日累计量不变、只走了 1/4 个交易日，量比应该翻 4 倍。
     * 旧算法在这里给出 0.5（容易被误判成缩量），标准算法给出 2.0。
     */
    @Test
    void volumeRatioNormalizesByElapsedMinutes() {
        double[] volumes = {100, 100, 100, 100, 100, 50};
        assertEquals(0.5, Indicators.volumeRatio(volumes, 5), 1e-9);
        assertEquals(2.0, Indicators.volumeRatio(volumes, 5, 60, 240), 1e-9);
        assertEquals(1.0, Indicators.volumeRatio(volumes, 5, 120, 240), 1e-9);
    }

    @Test
    void volumeRatioReturnsNaNForIllegalInput() {
        double[] volumes = {100, 100, 100, 100, 100, 200};
        assertTrue(Double.isNaN(Indicators.volumeRatio(volumes, 150, 60, 240))); // 窗口不足
        assertTrue(Double.isNaN(Indicators.volumeRatio(volumes, 5, 0, 240)));    // 分钟数非法
        assertTrue(Double.isNaN(Indicators.volumeRatio(volumes, 5, 60, 0)));
        assertTrue(Double.isNaN(Indicators.volumeRatio(new double[]{0, 0, 0, 0, 0, 100}, 5, 60, 240)));
        assertTrue(Double.isNaN(Indicators.volumeRatio(null, 5, 60, 240)));
    }
}
