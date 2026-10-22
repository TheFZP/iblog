package com.iblog.module.paper.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperStrategyParamsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void legacyParamsKeepFullDayWindow() {
        PaperStrategyParams params = PaperStrategyParams.parse(mapper,
                "{\"minScore\":60,\"stopLossPct\":8,\"takeProfitPct\":15,\"maxHoldDays\":10}");
        assertEquals(60, params.getMinScore());
        assertFalse(params.isSellAtOpen());
        assertEquals("09:30", params.getBuyStartTime());
        assertEquals("15:00", params.getBuyEndTime());
        assertTrue(params.inBuyWindow(LocalDateTime.of(2026, 10, 22, 10, 0)));
        assertTrue(params.inBuyWindow(LocalDateTime.of(2026, 10, 22, 14, 59)));
        assertFalse(params.inBuyWindow(LocalDateTime.of(2026, 10, 22, 15, 1)));
    }

    @Test
    void overnightWindowParsedAndRespected() {
        PaperStrategyParams params = PaperStrategyParams.parse(mapper,
                "{\"sellAtOpen\":true,\"buyStartTime\":\"14:30\",\"buyEndTime\":\"15:00\"}");
        assertTrue(params.isSellAtOpen());
        assertEquals("14:30", params.getBuyStartTime());
        assertEquals("15:00", params.getBuyEndTime());
        assertFalse(params.inBuyWindow(LocalDateTime.of(2026, 10, 22, 10, 0)));
        assertTrue(params.inBuyWindow(LocalDateTime.of(2026, 10, 22, 14, 30)));
        assertTrue(params.inBuyWindow(LocalDateTime.of(2026, 10, 22, 15, 0)));
        assertFalse(params.inBuyWindow(LocalDateTime.of(2026, 10, 22, 15, 1)));
    }

    @Test
    void invalidTimeAndBooleanFallBack() {
        PaperStrategyParams params = PaperStrategyParams.parse(mapper,
                "{\"sellAtOpen\":\"1\",\"buyStartTime\":\"25:00\",\"buyEndTime\":\"abc\"}");
        assertTrue(params.isSellAtOpen());
        assertEquals("09:30", params.getBuyStartTime());
        assertEquals("15:00", params.getBuyEndTime());
    }

    @Test
    void invertedWindowDisablesRestriction() {
        PaperStrategyParams params = PaperStrategyParams.parse(mapper,
                "{\"buyStartTime\":\"15:00\",\"buyEndTime\":\"09:30\"}");
        assertTrue(params.inBuyWindow(LocalDateTime.of(2026, 10, 22, 11, 0)));
    }

    @Test
    void jsonRoundTripKeepsNewFields() throws Exception {
        PaperStrategyParams params = PaperStrategyParams.parse(mapper,
                "{\"sellAtOpen\":true,\"buyStartTime\":\"14:30\",\"buyEndTime\":\"15:00\"}");
        PaperStrategyParams again = PaperStrategyParams.parse(mapper, params.toJson(mapper));
        assertTrue(again.isSellAtOpen());
        assertEquals("14:30", again.getBuyStartTime());
        assertEquals("15:00", again.getBuyEndTime());
    }
}
