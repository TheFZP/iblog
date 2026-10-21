package com.iblog.module.paper.support;

/**
 * 选股/回测用到的技术指标（纯函数，无状态）。
 *
 * <p>口径与前端 {@code stockIndicators.ts / techIndicators.ts} 对齐：
 * 简单均线、EMA、MACD(12,26,9)、RSI(14)、量比。</p>
 */
public final class Indicators {

    private Indicators() {
    }

    /** 最后一根的简单均线；数据不足返回 NaN。 */
    public static double sma(double[] values, int period) {
        if (values == null || period <= 0 || values.length < period) {
            return Double.NaN;
        }
        double sum = 0;
        for (int i = values.length - period; i < values.length; i++) {
            sum += values[i];
        }
        return sum / period;
    }

    public static double[] ema(double[] values, int period) {
        double[] result = new double[values.length];
        if (values.length == 0 || period <= 0) {
            return result;
        }
        double alpha = 2.0 / (period + 1);
        result[0] = values[0];
        for (int i = 1; i < values.length; i++) {
            result[i] = alpha * values[i] + (1 - alpha) * result[i - 1];
        }
        return result;
    }

    /** MACD：DIF=EMA12-EMA26，DEA=EMA9(DIF)，柱=2*(DIF-DEA)。 */
    public static Macd macd(double[] closes) {
        if (closes == null || closes.length < 26) {
            return new Macd(Double.NaN, Double.NaN, Double.NaN);
        }
        double[] ema12 = ema(closes, 12);
        double[] ema26 = ema(closes, 26);
        double[] dif = new double[closes.length];
        for (int i = 0; i < closes.length; i++) {
            dif[i] = ema12[i] - ema26[i];
        }
        double[] dea = ema(dif, 9);
        double lastDif = dif[dif.length - 1];
        double lastDea = dea[dea.length - 1];
        return new Macd(lastDif, lastDea, 2 * (lastDif - lastDea));
    }

    /** RSI（Wilder 平滑，period 默认 14）。 */
    public static double rsi(double[] closes, int period) {
        if (closes == null || period <= 0 || closes.length <= period) {
            return Double.NaN;
        }
        double gain = 0;
        double loss = 0;
        for (int i = 1; i <= period; i++) {
            double diff = closes[i] - closes[i - 1];
            if (diff >= 0) {
                gain += diff;
            } else {
                loss -= diff;
            }
        }
        double avgGain = gain / period;
        double avgLoss = loss / period;
        for (int i = period + 1; i < closes.length; i++) {
            double diff = closes[i] - closes[i - 1];
            double up = Math.max(diff, 0);
            double down = Math.max(-diff, 0);
            avgGain = (avgGain * (period - 1) + up) / period;
            avgLoss = (avgLoss * (period - 1) + down) / period;
        }
        if (avgLoss == 0) {
            return 100;
        }
        double rs = avgGain / avgLoss;
        return 100 - 100 / (1 + rs);
    }

    /** 量比：最后一根成交量 / 之前 period 根的平均成交量；数据不足返回 NaN。 */
    public static double volumeRatio(double[] volumes, int period) {
        if (volumes == null || period <= 0 || volumes.length < period + 1) {
            return Double.NaN;
        }
        double sum = 0;
        for (int i = volumes.length - 1 - period; i < volumes.length - 1; i++) {
            sum += volumes[i];
        }
        double average = sum / period;
        return average <= 0 ? Double.NaN : volumes[volumes.length - 1] / average;
    }

    public record Macd(double dif, double dea, double hist) {
    }
}
