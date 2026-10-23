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
    void strictFilterFieldsParseAndClamp() {
        PaperStrategyParams params = PaperStrategyParams.parse(mapper,
                "{\"strictFilters\":true,\"lowRatioMax\":1.15,\"sortByLowRatioAsc\":true,"
                        + "\"openTurnoverMinPct\":2,\"openTurnoverStart\":\"09:25\",\"openTurnoverEnd\":\"09:40\","
                        + "\"realTurnoverMinPct\":2,\"requireAboveOpen\":true,\"requireAboveVwap\":true,"
                        + "\"hardVolumeRatioMin\":1,\"ma5OverMa20Min\":1,\"vol5OverVol20Min\":1,"
                        + "\"totalCapMinYi\":50,\"noLimitUpWithinDays\":3,"
                        + "\"requireHolderShrink\":true,\"profitRequire\":3,\"affordableOnly\":true}");
        assertTrue(params.isStrictFilters());
        assertEquals(1.15, params.getLowRatioMax(), 1e-9);
        assertTrue(params.isSortByLowRatioAsc());
        assertEquals(2, params.getOpenTurnoverMinPct(), 1e-9);
        assertEquals("09:25", params.getOpenTurnoverStart());
        assertEquals("09:40", params.getOpenTurnoverEnd());
        assertEquals(2, params.getRealTurnoverMinPct(), 1e-9);
        assertTrue(params.isRequireAboveOpen());
        assertTrue(params.isRequireAboveVwap());
        assertEquals(1, params.getHardVolumeRatioMin(), 1e-9);
        assertEquals(1, params.getMa5OverMa20Min(), 1e-9);
        assertEquals(1, params.getVol5OverVol20Min(), 1e-9);
        assertEquals(50, params.getTotalCapMinYi(), 1e-9);
        assertEquals(3, params.getNoLimitUpWithinDays());
        assertTrue(params.isRequireHolderShrink());
        assertEquals(3, params.getProfitRequire());
        assertTrue(params.isAffordableOnly());

        // 越界值被钳制：profitRequire 只有 0~3，noLimitUpWithinDays 不能为负
        PaperStrategyParams clamped = PaperStrategyParams.parse(mapper,
                "{\"profitRequire\":9,\"noLimitUpWithinDays\":-5,\"lowRatioMax\":-1}");
        assertEquals(3, clamped.getProfitRequire());
        assertEquals(0, clamped.getNoLimitUpWithinDays());
        assertEquals(0, clamped.getLowRatioMax(), 1e-9);
    }

    @Test
    void strictFiltersOffByDefault() {
        PaperStrategyParams params = PaperStrategyParams.parse(mapper, "{}");
        assertFalse(params.isStrictFilters());
        assertFalse(params.isSortByLowRatioAsc());
        assertFalse(params.isRequireAboveOpen());
        assertFalse(params.isRequireAboveVwap());
        assertFalse(params.isRequireHolderShrink());
        assertFalse(params.isAffordableOnly());
        assertEquals(0, params.getProfitRequire());
        assertEquals(0, params.getLowRatioMax(), 1e-9);
        assertEquals("09:25", params.getOpenTurnoverStart());
        assertEquals("09:40", params.getOpenTurnoverEnd());
    }

    @Test
    void openTurnoverWindowMatchesOnlyConfiguredMinutes() {
        PaperStrategyParams params = PaperStrategyParams.parse(mapper,
                "{\"openTurnoverStart\":\"09:25\",\"openTurnoverEnd\":\"09:40\"}");
        assertTrue(params.inOpenTurnoverWindow("09:25"));
        assertTrue(params.inOpenTurnoverWindow("09:33"));
        assertTrue(params.inOpenTurnoverWindow("09:40"));
        assertFalse(params.inOpenTurnoverWindow("09:24"));
        assertFalse(params.inOpenTurnoverWindow("09:41"));
        assertFalse(params.inOpenTurnoverWindow(""));
    }

    @Test
    void auctionTurnoverParamParsesAndClamps() {
        PaperStrategyParams params = PaperStrategyParams.parse(mapper,
                "{\"auctionTurnoverMinPct\":2.5}");
        assertEquals(2.5, params.getAuctionTurnoverMinPct(), 1e-9);
        // 默认关闭
        assertEquals(0, PaperStrategyParams.parse(mapper, "{}").getAuctionTurnoverMinPct(), 1e-9);
        // 越界钳制到 0~100
        assertEquals(100, PaperStrategyParams.parse(mapper,
                "{\"auctionTurnoverMinPct\":500}").getAuctionTurnoverMinPct(), 1e-9);
        assertEquals(0, PaperStrategyParams.parse(mapper,
                "{\"auctionTurnoverMinPct\":-3}").getAuctionTurnoverMinPct(), 1e-9);
        // 与早盘累计窗口是两个独立参数，改一个不影响另一个
        PaperStrategyParams both = PaperStrategyParams.parse(mapper,
                "{\"openTurnoverMinPct\":2,\"auctionTurnoverMinPct\":0.5}");
        assertEquals(2, both.getOpenTurnoverMinPct(), 1e-9);
        assertEquals(0.5, both.getAuctionTurnoverMinPct(), 1e-9);
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
