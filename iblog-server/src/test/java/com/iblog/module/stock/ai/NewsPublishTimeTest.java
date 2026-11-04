package com.iblog.module.stock.ai;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class NewsPublishTimeTest {

    @Test
    void parsesCommonEastmoneyFormats() {
        assertEquals(LocalDateTime.of(2026, 11, 4, 9, 30, 5),
                AiNewsScoreService.parsePublishTime("2026-11-04 09:30:05"));
        assertEquals(LocalDateTime.of(2026, 11, 4, 9, 30),
                AiNewsScoreService.parsePublishTime("2026-11-04 09:30"));
        assertEquals(LocalDateTime.of(2026, 11, 4, 0, 0),
                AiNewsScoreService.parsePublishTime("2026-11-04"));
        assertEquals(LocalDateTime.of(2026, 11, 4, 9, 30),
                AiNewsScoreService.parsePublishTime("2026/11/04 09:30:00"));
    }

    @Test
    void monthDayWithoutYearUsesCurrentYear() {
        LocalDateTime parsed = AiNewsScoreService.parsePublishTime("11-04 09:30");
        assertNotNull(parsed);
        assertEquals(11, parsed.getMonthValue());
        assertEquals(4, parsed.getDayOfMonth());
        assertEquals(9, parsed.getHour());
    }

    @Test
    void blankOrUnparseableReturnsNull() {
        assertNull(AiNewsScoreService.parsePublishTime(null));
        assertNull(AiNewsScoreService.parsePublishTime(""));
        assertNull(AiNewsScoreService.parsePublishTime("刚刚"));
    }
}