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

    /**
     * A 股一个完整交易日的分钟数（上午 120 + 下午 120）。
     *
     * <p>量比要按「已经走过的分钟数」归一化，见
     * {@link Indicators#volumeRatio(double[], int, double, int)}。</p>
     */
    public static final int SESSION_MINUTES = 240;

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

    /** 当前时刻已经走过的交易分钟数（量比归一化用），见 {@link #elapsedMinutes(LocalDateTime)}。 */
    public int elapsedMinutesNow() {
        return elapsedMinutes(now());
    }

    /**
     * 从开盘起已经走过的交易分钟数，用于把「当日累计成交量」折算成分钟均量。
     *
     * <p>为什么要它：标准的量比是「当日每分钟均量 ÷ 过去 5 日每分钟均量」。
     * 如果直接用当日累计量除以前 5 日的全天均量，上午 10 点只走了 1/5 个交易日，
     * 算出来的量比会被系统性压低近 5 倍，导致「量比 &gt; 1」这类条件在上午几乎无法通过。
     * 收盘时两种口径等价，盘中的区别就是这几十个百分点。</p>
     *
     * <p>返回值夹在 1 ~ {@link #SESSION_MINUTES}：盘前（含集合竞价）按 1 分钟算，
     * 收盘后按整日算，避免除零或放大。</p>
     */
    public int elapsedMinutes(LocalDateTime at) {
        LocalTime time = at.toLocalTime();
        int minutes = 0;
        if (time.isAfter(MORNING_OPEN)) {
            minutes += Math.min(minutesBetween(MORNING_OPEN, time), 120);
        }
        if (time.isAfter(AFTERNOON_OPEN)) {
            minutes += Math.min(minutesBetween(AFTERNOON_OPEN, time), 120);
        }
        return Math.max(1, Math.min(minutes, SESSION_MINUTES));
    }

    private static int minutesBetween(LocalTime from, LocalTime to) {
        return (int) java.time.Duration.between(from, to).toMinutes();
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
