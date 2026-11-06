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

    @Test
    void newsFieldsDefaultOnAndClamp() {
        // 默认开启（新闻是情绪市场的重要输入，与盘前预选一致）
        PaperStrategyParams p = PaperStrategyParams.parse(mapper, "{}");
        assertTrue(p.isNewsEnabled());
        assertEquals(15, p.getNewsScoreMax());
        assertEquals(10, p.getNewsPenaltyMax());
        assertFalse(p.isNewsHardFilter());
        assertEquals(-50, p.getNewsMinScore(), 0.001);
        assertEquals(0.6, p.getNewsMinConfidence(), 0.001);
        assertEquals(30, p.getNewsRefreshMin());

        // 显式关闭 + 越界钳制
        PaperStrategyParams c = PaperStrategyParams.parse(mapper,
                "{\"newsEnabled\":false,\"newsScoreMax\":500,\"newsPenaltyMax\":-3,"
                        + "\"newsMinScore\":-999,\"newsMinConfidence\":5,\"newsRefreshMin\":3}");
        assertFalse(c.isNewsEnabled());
        assertEquals(100, c.getNewsScoreMax());
        assertEquals(0, c.getNewsPenaltyMax());
        assertEquals(-100, c.getNewsMinScore(), 0.001);
        assertEquals(1.0, c.getNewsMinConfidence(), 0.001);
        assertEquals(10, c.getNewsRefreshMin());
    }

    @Test
    void newsRoundTrip() throws Exception {
        PaperStrategyParams p = PaperStrategyParams.parse(mapper,
                "{\"newsEnabled\":true,\"newsScoreMax\":20,\"newsPenaltyMax\":5,"
                        + "\"newsHardFilter\":true,\"newsMinScore\":-40,"
                        + "\"newsMinConfidence\":0.7,\"newsRefreshMin\":60}");
        PaperStrategyParams again = PaperStrategyParams.parse(mapper, p.toJson(mapper));
        assertTrue(again.isNewsEnabled());
        assertTrue(again.isNewsHardFilter());
        assertEquals(20, again.getNewsScoreMax());
        assertEquals(5, again.getNewsPenaltyMax());
        assertEquals(-40, again.getNewsMinScore(), 0.001);
        assertEquals(60, again.getNewsRefreshMin());
    }

    @Test
    void sentimentFieldsDefaultToOff() {
        PaperStrategyParams params = PaperStrategyParams.parse(mapper, "{}");
        assertFalse(params.isSentimentEnabled());
        assertEquals(0, params.getSentimentBuyMinScore(), 0.001);
        assertEquals(0, params.getSentimentExitScore(), 0.001);
        assertEquals(0, params.getHotRankTopN());
        assertFalse(params.isRequireHotRankUp());
        assertEquals(0, params.getHotnessScore());
        assertEquals(0, params.getSentimentChangeMaxPct(), 0.001);
    }

    @Test
    void sentimentFieldsParseAndClamp() {
        PaperStrategyParams params = PaperStrategyParams.parse(mapper,
                "{\"sentimentEnabled\":true,\"sentimentBuyMinScore\":45,\"sentimentExitScore\":20,"
                        + "\"hotRankTopN\":30,\"requireHotRankUp\":true,\"hotnessScore\":8,"
                        + "\"sentimentChangeMaxPct\":12.5}");
        assertTrue(params.isSentimentEnabled());
        assertEquals(45, params.getSentimentBuyMinScore(), 0.001);
        assertEquals(20, params.getSentimentExitScore(), 0.001);
        assertEquals(30, params.getHotRankTopN());
        assertTrue(params.isRequireHotRankUp());
        assertEquals(8, params.getHotnessScore());
        assertEquals(12.5, params.getSentimentChangeMaxPct(), 0.001);

        // 越界输入一律钳到合法区间，而不是抛错
        // 注意 sentimentExitScore 会被「不得高于开仓线」再压一次，所以这里同时给足开仓线才能验到 100 的上界
        PaperStrategyParams clamped = PaperStrategyParams.parse(mapper,
                "{\"sentimentBuyMinScore\":-20,\"sentimentExitScore\":500,"
                        + "\"hotRankTopN\":999,\"hotnessScore\":-5,\"sentimentChangeMaxPct\":99}");
        assertEquals(0, clamped.getSentimentBuyMinScore(), 0.001);
        assertEquals(0, clamped.getSentimentExitScore(), 0.001);
        assertEquals(100, clamped.getHotRankTopN());
        assertEquals(0, clamped.getHotnessScore());
        assertEquals(50, clamped.getSentimentChangeMaxPct(), 0.001);

        PaperStrategyParams clampedExit = PaperStrategyParams.parse(mapper,
                "{\"sentimentBuyMinScore\":90,\"sentimentExitScore\":500}");
        assertEquals(90, clampedExit.getSentimentExitScore(), 0.001);
    }

    @Test
    void exitScoreNeverAboveBuyScore() {
        // 清仓线高于开仓线时会「刚买就清仓」，parse 直接把清仓线压平到开仓线
        PaperStrategyParams params = PaperStrategyParams.parse(mapper,
                "{\"sentimentEnabled\":true,\"sentimentBuyMinScore\":40,\"sentimentExitScore\":70}");
        assertEquals(40, params.getSentimentExitScore(), 0.001);
        // 反向配置（清仓线更低）是合理的，保持原值
        PaperStrategyParams normal = PaperStrategyParams.parse(mapper,
                "{\"sentimentBuyMinScore\":60,\"sentimentExitScore\":25}");
        assertEquals(25, normal.getSentimentExitScore(), 0.001);
    }

    @Test
    void preMarketPreselectDefaultsOnAndParses() {
        // 默认开启：旧规则 JSON 里没有这个字段时也要生效
        assertTrue(PaperStrategyParams.parse(mapper, "{}").isPreMarketPreselect());

        // 显式关闭仍然有效
        assertFalse(PaperStrategyParams.parse(mapper,
                "{\"preMarketPreselect\":false}").isPreMarketPreselect());
        assertFalse(PaperStrategyParams.parse(mapper,
                "{\"preMarketPreselect\":\"0\"}").isPreMarketPreselect());

        // 兼容字符串/数字写法，与其它布尔字段的解析口径一致
        assertTrue(PaperStrategyParams.parse(mapper, "{\"preMarketPreselect\":\"1\"}").isPreMarketPreselect());
        assertTrue(PaperStrategyParams.parse(mapper, "{\"preMarketPreselect\":1}").isPreMarketPreselect());
    }

    @Test
    void sentimentRoundTrip() throws Exception {
        PaperStrategyParams params = PaperStrategyParams.parse(mapper,
                "{\"sentimentEnabled\":true,\"sentimentBuyMinScore\":50,\"sentimentExitScore\":30,"
                        + "\"hotRankTopN\":15,\"requireHotRankUp\":true,\"hotnessScore\":6,"
                        + "\"sentimentChangeMaxPct\":9.5}");
        PaperStrategyParams again = PaperStrategyParams.parse(mapper, params.toJson(mapper));
        assertTrue(again.isSentimentEnabled());
        assertEquals(50, again.getSentimentBuyMinScore(), 0.001);
        assertEquals(30, again.getSentimentExitScore(), 0.001);
        assertEquals(15, again.getHotRankTopN());
        assertTrue(again.isRequireHotRankUp());
        assertEquals(6, again.getHotnessScore());
        assertEquals(9.5, again.getSentimentChangeMaxPct(), 0.001);
    }

    @Test
    void heldScoreSellDefaultsOffAndClamps() throws Exception {
        // 默认关闭：老规则升级后不会突然开始自动清仓
        PaperStrategyParams p = PaperStrategyParams.parse(mapper, "{}");
        assertFalse(p.isHeldScoreSellEnabled());
        assertEquals(40, p.getHeldScoreSellMin());

        PaperStrategyParams c = PaperStrategyParams.parse(mapper,
                "{\"heldScoreSellEnabled\":true,\"heldScoreSellMin\":500}");
        assertTrue(c.isHeldScoreSellEnabled());
        assertEquals(200, c.getHeldScoreSellMin());

        PaperStrategyParams again = PaperStrategyParams.parse(mapper, c.toJson(mapper));
        assertTrue(again.isHeldScoreSellEnabled());
        assertEquals(200, again.getHeldScoreSellMin());
    }
}
