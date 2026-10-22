package com.iblog.module.paper.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iblog.module.stock.model.IntradayItem;
import com.iblog.module.stock.model.KlineItem;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 选股质量因子（换手率、流通市值、分时均价线、涨停记忆）的纯逻辑测试。
 */
class PaperScreenRulesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void inRangeHandlesBoundsAndDisabledFactor() {
        assertTrue(PaperScreenRules.inRange(5, 3, 15));
        assertTrue(PaperScreenRules.inRange(3, 3, 15));
        assertTrue(PaperScreenRules.inRange(15, 3, 15));
        assertFalse(PaperScreenRules.inRange(2.9, 3, 15));
        assertFalse(PaperScreenRules.inRange(15.1, 3, 15));
        // 无数据（0/负数/NaN）不加分
        assertFalse(PaperScreenRules.inRange(0, 3, 15));
        assertFalse(PaperScreenRules.inRange(-1, 3, 15));
        assertFalse(PaperScreenRules.inRange(Double.NaN, 3, 15));
        // 上下限同时为 0 表示该因子关闭
        assertFalse(PaperScreenRules.inRange(5, 0, 0));
        // 单边限制
        assertTrue(PaperScreenRules.inRange(5, 0, 10));
        assertFalse(PaperScreenRules.inRange(11, 0, 10));
        assertTrue(PaperScreenRules.inRange(6, 5, 0));
        assertFalse(PaperScreenRules.inRange(4, 5, 0));
    }

    @Test
    void vwapAboveRatioCountsMinutesAboveAveragePrice() {
        List<IntradayItem> items = new ArrayList<>();
        items.add(item(10.0, 9.9));
        items.add(item(10.2, 10.0));
        items.add(item(10.1, 10.05));
        items.add(item(9.8, 10.06));
        assertEquals(0.75, PaperScreenRules.vwapAboveRatio(items), 1e-9);

        // 均价缺失的分钟不参与统计
        items.add(item(10.0, 0));
        assertEquals(0.75, PaperScreenRules.vwapAboveRatio(items), 1e-9);
        assertEquals(0, PaperScreenRules.vwapAboveRatio(List.of()), 1e-9);
        assertEquals(0, PaperScreenRules.vwapAboveRatio(null), 1e-9);
    }

    @Test
    void hasLimitUpDetectsTouchedOrClosedAtLimit() {
        // 主板：昨收 10.00 → 涨停 11.00
        assertTrue(PaperScreenRules.hasLimitUp(bars(10.0, 10.6, 11.0), 3, "600000", "浦发银行"));
        assertTrue(PaperScreenRules.hasLimitUp(bars(10.0, 11.0, 10.8), 3, "600000", "浦发银行"));
        assertFalse(PaperScreenRules.hasLimitUp(bars(10.0, 10.9, 10.5), 3, "600000", "浦发银行"));
        // 创业板：20%，昨收 10.00 → 涨停 12.00
        assertTrue(PaperScreenRules.hasLimitUp(bars(10.0, 12.0, 11.5), 3, "300001", "特锐德"));
        assertFalse(PaperScreenRules.hasLimitUp(bars(10.0, 11.9, 11.5), 3, "300001", "特锐德"));
        // 窗口外的涨停不算
        assertFalse(PaperScreenRules.hasLimitUp(barsWithOldLimitUp(), 1, "600000", "浦发银行"));
        // 观察窗口放宽到 20 日后，前一根的涨停仍应被记住
        assertTrue(PaperScreenRules.hasLimitUp(barsWithOldLimitUp(), 20, "600000", "浦发银行"));
    }

    @Test
    void newFactorsDefaultsAndClamps() {
        PaperStrategyParams defaults = PaperStrategyParams.parse(mapper, "{}");
        assertEquals(5, defaults.getTurnoverScore());
        assertEquals(3, defaults.getTurnoverMinPct(), 1e-9);
        assertEquals(15, defaults.getTurnoverMaxPct(), 1e-9);
        assertEquals(5, defaults.getFloatCapScore());
        assertEquals(50, defaults.getFloatCapMinYi(), 1e-9);
        assertEquals(200, defaults.getFloatCapMaxYi(), 1e-9);
        assertEquals(10, defaults.getVwapScore());
        assertEquals(0.95, defaults.getVwapAboveRatio(), 1e-9);
        assertEquals(10, defaults.getLimitUpScore());
        assertEquals(20, defaults.getLimitUpWithinDays());

        PaperStrategyParams clamped = PaperStrategyParams.parse(mapper,
                "{\"turnoverScore\":500,\"turnoverMinPct\":-5,\"turnoverMaxPct\":500,"
                        + "\"floatCapScore\":-1,\"floatCapMinYi\":-1,\"floatCapMaxYi\":999999,"
                        + "\"vwapScore\":500,\"vwapAboveRatio\":2,\"limitUpScore\":-1,\"limitUpWithinDays\":0}");
        assertEquals(100, clamped.getTurnoverScore());
        assertEquals(0, clamped.getTurnoverMinPct(), 1e-9);
        assertEquals(100, clamped.getTurnoverMaxPct(), 1e-9);
        assertEquals(0, clamped.getFloatCapScore());
        assertEquals(0, clamped.getFloatCapMinYi(), 1e-9);
        assertEquals(100000, clamped.getFloatCapMaxYi(), 1e-9);
        assertEquals(100, clamped.getVwapScore());
        assertEquals(1, clamped.getVwapAboveRatio(), 1e-9);
        assertEquals(0, clamped.getLimitUpScore());
        assertEquals(1, clamped.getLimitUpWithinDays());

        // 落库 JSON 要包含全部新字段，避免前端读旧规则时缺项
        String json = clamped.toJson(mapper);
        assertTrue(json.contains("\"turnoverScore\""));
        assertTrue(json.contains("\"floatCapMaxYi\""));
        assertTrue(json.contains("\"vwapAboveRatio\""));
        assertTrue(json.contains("\"limitUpWithinDays\""));
        assertTrue(json.contains("\"firstBoardScore\""));
        assertTrue(json.contains("\"boardHeightMax\""));
    }

    @Test
    void firstBoardFactorsDefaultsAndClamps() {
        PaperStrategyParams defaults = PaperStrategyParams.parse(mapper, "{}");
        assertEquals(0, defaults.getFirstBoardScore());
        assertEquals(0, defaults.getBoardHeightMax());

        PaperStrategyParams clamped = PaperStrategyParams.parse(mapper,
                "{\"firstBoardScore\":500,\"boardHeightMax\":99,\"minScore\":500}");
        assertEquals(100, clamped.getFirstBoardScore());
        assertEquals(10, clamped.getBoardHeightMax());

        PaperStrategyParams negative = PaperStrategyParams.parse(mapper,
                "{\"firstBoardScore\":-20,\"boardHeightMax\":-3}");
        assertEquals(0, negative.getFirstBoardScore());
        assertEquals(0, negative.getBoardHeightMax());
    }

    @Test
    void boardHeightCountsSealedLimitUpsBackwards() {
        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        String yesterday = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1).toString();
        String dayBefore = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(2).toString();
        String older = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(3).toString();
        String oldest = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(4).toString();

        // 昨日首板：9.50 → 10.00（+5%）→ 11.00（+10%），今日盘中 bar 不参与
        List<KlineItem> firstBoard = List.of(
                barOn(older, 9.50), barOn(dayBefore, 10.00),
                barOn(yesterday, 11.00), barOn(today, 11.20));
        assertEquals(1, PaperScreenRules.boardHeight(firstBoard, "600000", "浦发银行"));
        assertTrue(PaperScreenRules.isFirstBoard(firstBoard, "600000", "浦发银行"));

        // 昨日二板（实际为三连板）：8.18 → 9.00 → 9.90 → 10.89 → 11.979
        List<KlineItem> secondBoard = List.of(
                barOn(oldest, 8.18), barOn(older, 9.00), barOn(dayBefore, 9.90),
                barOn(yesterday, 10.89), barOn(today, 11.10));
        assertEquals(3, PaperScreenRules.boardHeight(secondBoard, "600000", "浦发银行"));
        assertFalse(PaperScreenRules.isFirstBoard(secondBoard, "600000", "浦发银行"));

        // 昨日没有涨停
        List<KlineItem> noBoard = List.of(
                barOn(older, 9.50), barOn(dayBefore, 10.00),
                barOn(yesterday, 10.20), barOn(today, 10.10));
        assertEquals(0, PaperScreenRules.boardHeight(noBoard, "600000", "浦发银行"));

        // 盘前：最后一根就是昨日，不再往前找「今天」
        List<KlineItem> preOpen = List.of(
                barOn(dayBefore, 10.00), barOn(yesterday, 11.00));
        assertEquals(1, PaperScreenRules.boardHeight(preOpen, "600000", "浦发银行"));

        // 数据不足
        assertEquals(-1, PaperScreenRules.boardHeight(List.of(barOn(yesterday, 11.0)), "600000", "浦发银行"));
        assertEquals(-1, PaperScreenRules.boardHeight(List.of(), "600000", "浦发银行"));
    }

    private static KlineItem barOn(String date, double close) {
        KlineItem bar = bar(close, close, close);
        bar.setDate(date);
        return bar;
    }

    private static IntradayItem item(double price, double avgPrice) {
        IntradayItem item = new IntradayItem();
        item.setPrice(price);
        item.setAvgPrice(avgPrice);
        return item;
    }

    /** 3 根日 K：首根昨收 10.00，后两根用给定高点/收盘。 */
    private static List<KlineItem> bars(double prevClose, double high, double close) {
        List<KlineItem> list = new ArrayList<>();
        list.add(bar(prevClose, prevClose, prevClose));
        list.add(bar(prevClose, high, close));
        return list;
    }

    /** 涨停发生在窗口之外：最近一根平盘，前一根涨停。 */
    private static List<KlineItem> barsWithOldLimitUp() {
        List<KlineItem> list = new ArrayList<>();
        list.add(bar(9.0, 9.0, 9.0));
        list.add(bar(9.0, 9.9, 9.9));   // 9.0 → 9.9 涨停
        list.add(bar(9.9, 10.0, 9.95)); // 最近一根没有涨停
        return list;
    }

    private static KlineItem bar(double prevClose, double high, double close) {
        KlineItem bar = new KlineItem();
        bar.setOpen(prevClose);
        bar.setHigh(high);
        bar.setLow(prevClose);
        bar.setClose(close);
        bar.setVolume(1000);
        return bar;
    }
}
