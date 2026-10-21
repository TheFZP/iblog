package com.iblog.module.paper.support;

import com.iblog.config.IblogProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A 股交易时段与交易日判断。
 *
 * <p>为什么不用官方交易日历：免费数据源没有稳定的交易日历接口，
 * 引入一份年度维护的节假日表又容易忘更新。这里的做法是：
 * 周末 + 配置的节假日（{@code iblog.paper.holiday-dates}）先挡掉；
 * 即使漏配了某个节假日，引擎还会用行情时间戳兜底（当天行情不动就不下单）。</p>
 */
@Component
@RequiredArgsConstructor
public class TradeCalendar {

    /** 行情统一用北京时间判断时段。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final LocalTime MORNING_OPEN = LocalTime.of(9, 30);
    private static final LocalTime MORNING_CLOSE = LocalTime.of(11, 30);
    private static final LocalTime AFTERNOON_OPEN = LocalTime.of(13, 0);
    private static final LocalTime AFTERNOON_CLOSE = LocalTime.of(15, 0);

    private final IblogProperties properties;

    public LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public String todayText() {
        return today().format(DATE_FORMAT);
    }

    public LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }

    /** 是否交易日：非周末且不在节假日表里。 */
    public boolean isTradingDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        return !holidays().contains(date.format(DATE_FORMAT));
    }

    /** 是否在连续竞价时段（9:30-11:30、13:00-15:00，含边界）。 */
    public boolean isTradingTime(LocalDateTime at) {
        if (!isTradingDay(at.toLocalDate())) {
            return false;
        }
        LocalTime time = at.toLocalTime();
        boolean morning = !time.isBefore(MORNING_OPEN) && !time.isAfter(MORNING_CLOSE);
        boolean afternoon = !time.isBefore(AFTERNOON_OPEN) && !time.isAfter(AFTERNOON_CLOSE);
        return morning || afternoon;
    }

    public boolean isTradingTimeNow() {
        return isTradingTime(now());
    }

    /**
     * 是否允许下单。本地开发开了 {@code force-trading-hours} 后，
     * 晚上也能手动买卖、跑引擎，方便调试（线上保持 false）。
     */
    public boolean allowOrders() {
        return properties.getPaper().isForceTradingHours() || isTradingTimeNow();
    }

    private Set<String> holidays() {
        String raw = properties.getPaper().getHolidayDates();
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toSet());
    }
}
