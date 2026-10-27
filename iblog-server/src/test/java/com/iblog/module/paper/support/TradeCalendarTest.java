package com.iblog.module.paper.support;

import com.iblog.config.IblogProperties;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 交易时段相关的纯计算单测。
 *
 * <p>重点是 {@link TradeCalendar#elapsedMinutes(LocalDateTime)}：量比归一化全靠它，
 * 算错就会让盘中量比整体偏大或偏小，属于「错了也不报错、只是选股结果悄悄跑偏」的那类问题。</p>
 */
class TradeCalendarTest {

    private final TradeCalendar calendar = new TradeCalendar(new IblogProperties());

    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(LocalDate.of(2026, 10, 27), java.time.LocalTime.of(hour, minute));
    }

    @Test
    void elapsedMinutesStartsAtOneBeforeOpen() {
        // 盘前、集合竞价（9:25 那类）都还没走过连续竞价，夹到 1 防止除零
        assertEquals(1, calendar.elapsedMinutes(at(8, 0)));
        assertEquals(1, calendar.elapsedMinutes(at(9, 25)));
        assertEquals(1, calendar.elapsedMinutes(at(9, 30)));
        // 9:30 → 9:31 走完第 1 分钟，所以 9:32 才是 2 分钟
        assertEquals(1, calendar.elapsedMinutes(at(9, 31)));
        assertEquals(2, calendar.elapsedMinutes(at(9, 32)));
    }

    @Test
    void elapsedMinutesCountsMorningSession() {
        assertEquals(31, calendar.elapsedMinutes(at(10, 1)));
        assertEquals(120, calendar.elapsedMinutes(at(11, 30)));
        // 午休期间停在上午收盘的 120，不会继续累加
        assertEquals(120, calendar.elapsedMinutes(at(12, 0)));
        assertEquals(120, calendar.elapsedMinutes(at(12, 59)));
    }

    @Test
    void elapsedMinutesCountsAfternoonSession() {
        assertEquals(121, calendar.elapsedMinutes(at(13, 1)));
        assertEquals(150, calendar.elapsedMinutes(at(13, 30)));
        assertEquals(240, calendar.elapsedMinutes(at(15, 0)));
        // 收盘后夹到整日，避免放大
        assertEquals(240, calendar.elapsedMinutes(at(16, 0)));
    }

    @Test
    void sessionMinutesIsAFullDay() {
        assertEquals(240, TradeCalendar.SESSION_MINUTES);
        assertEquals(TradeCalendar.SESSION_MINUTES, calendar.elapsedMinutes(at(15, 30)));
    }
}
