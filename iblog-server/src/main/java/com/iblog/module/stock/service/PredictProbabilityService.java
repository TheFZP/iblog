package com.iblog.module.stock.service;

import com.iblog.config.IblogProperties;
import com.iblog.module.paper.support.TradeCalendar;
import com.iblog.module.stock.entity.StockPredictDaily;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 把规则分数换算成「次日上涨参考概率」。
 *
 * <p>没有历史样本时用启发式：{@code p0 = 40 + 25 × score / 100}，限制在 30~75。
 * 样本足够后按分数 10 分一档做贝叶斯平滑：
 * {@code p = (hits + k × p0/100) / (n + k)}，k 默认 30。
 * 这样小样本不会被一两天的运气带飞，样本多了会自然收敛到真实命中率。</p>
 *
 * <p>只使用 {@code trade_date < today} 的已评估样本，避免把今天（还没发生）的结果
 * 泄漏进概率。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PredictProbabilityService {

    private final NextDayPredictStore store;
    private final TradeCalendar calendar;
    private final IblogProperties properties;

    public record Estimate(double probability, String source, int samples) {
    }

    /** 计算某分数在 {@code today} 时点的参考概率。 */
    public Estimate estimate(int score, LocalDate today) {
        IblogProperties.Predict config = properties.getPredict();
        double heuristic = heuristic(score);

        LocalDate from = lookbackStart(today, config.getCalibrationLookbackDays());
        List<StockPredictDaily> samples;
        try {
            samples = store.listEvaluated(from, today.minusDays(1));
        } catch (RuntimeException ex) {
            log.debug("概率校准读取历史失败，退回启发式 err={}", ex.getMessage());
            return new Estimate(round(heuristic), "HEURISTIC", 0);
        }

        int lower = Math.max(0, score / 10 * 10);
        int upper = lower >= 100 ? 100 : lower + 9;
        int hits = 0;
        int n = 0;
        for (StockPredictDaily row : samples) {
            if (row.getScore() == null || row.getHit() == null) {
                continue;
            }
            if (row.getScore() < lower || row.getScore() > upper) {
                continue;
            }
            n++;
            hits += row.getHit();
        }

        if (n < config.getCalibrationMinSamples()) {
            return new Estimate(round(heuristic), "HEURISTIC", n);
        }
        double k = Math.max(1, config.getCalibrationShrinkK());
        double calibrated = (hits + k * heuristic / 100) / (n + k) * 100;
        return new Estimate(round(clamp(calibrated)), "CALIBRATED", n);
    }

    /** 启发式概率（30~75），分越高越接近上限。 */
    static double heuristic(int score) {
        double p = 40 + 25 * Math.max(0, Math.min(100, score)) / 100.0;
        return clamp(p);
    }

    private static double clamp(double value) {
        return Math.max(30, Math.min(75, value));
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private LocalDate lookbackStart(LocalDate today, int tradingDays) {
        LocalDate date = today;
        int days = Math.max(1, tradingDays);
        for (int i = 0; i < days; i++) {
            date = calendar.previousTradingDay(date);
        }
        return date;
    }
}
