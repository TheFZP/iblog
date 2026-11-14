package com.iblog.module.stock.service;

import com.iblog.module.paper.support.PredictStatsCalculator;
import com.iblog.module.paper.support.TradeCalendar;
import com.iblog.module.stock.entity.StockPredictDaily;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 次日预测的查询与统计。
 */
@Service
@RequiredArgsConstructor
public class PredictStatsService {

    /** 日期列表最多回看的天数（自然日）。 */
    private static final int DATE_LOOKBACK_DAYS = 180;

    private final NextDayPredictStore store;
    private final TradeCalendar calendar;

    public record DailyView(LocalDate date, LocalDateTime generatedAt, Double marketSentiment,
                            List<StockPredictDaily> items) {
    }

    public record DateSummary(LocalDate date, int count, int evaluated) {
    }

    /** 指定日期榜单；{@code date == null} 时取最近一期。 */
    public DailyView daily(LocalDate date) {
        List<StockPredictDaily> rows;
        if (date == null) {
            rows = store.latestBefore(calendar.today().plusDays(1));
        } else {
            rows = store.listByDate(date);
        }
        if (rows.isEmpty()) {
            return new DailyView(date, null, null, List.of());
        }
        LocalDateTime generatedAt = null;
        Double sentiment = null;
        for (StockPredictDaily row : rows) {
            if (row.getCreateTime() != null
                    && (generatedAt == null || row.getCreateTime().isAfter(generatedAt))) {
                generatedAt = row.getCreateTime();
            }
            if (row.getMarketSentiment() != null) {
                sentiment = row.getMarketSentiment().doubleValue();
            }
        }
        return new DailyView(rows.get(0).getTradeDate(), generatedAt, sentiment, rows);
    }

    /** 最近有榜单的交易日（倒序）。 */
    public List<DateSummary> dates(int limit) {
        int max = Math.max(1, Math.min(90, limit));
        LocalDate from = calendar.today().minusDays(DATE_LOOKBACK_DAYS);
        List<StockPredictDaily> rows = store.listBetween(from, calendar.today());
        Map<LocalDate, int[]> grouped = new LinkedHashMap<>();
        for (StockPredictDaily row : rows) {
            if (row.getTradeDate() == null) {
                continue;
            }
            int[] counts = grouped.computeIfAbsent(row.getTradeDate(), k -> new int[2]);
            counts[0]++;
            if ("EVALUATED".equals(row.getEvalStatus())) {
                counts[1]++;
            }
        }
        List<DateSummary> result = new ArrayList<>();
        for (Map.Entry<LocalDate, int[]> entry : grouped.entrySet()) {
            result.add(new DateSummary(entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
        }
        result.sort((a, b) -> b.date().compareTo(a.date()));
        return result.size() > max ? result.subList(0, max) : result;
    }

    /** 近 N 个自然日的准确率统计（默认 30）。 */
    public PredictStatsCalculator.Stats stats(int days) {
        int span = Math.max(1, Math.min(365, days));
        LocalDate today = calendar.today();
        List<StockPredictDaily> rows = store.listBetween(today.minusDays(span), today);
        return PredictStatsCalculator.compute(rows, span);
    }
}
