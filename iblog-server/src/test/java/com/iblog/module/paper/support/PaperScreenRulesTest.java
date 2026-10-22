package com.iblog.module.paper.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iblog.module.stock.model.IntradayItem;
import com.iblog.module.stock.model.KlineItem;
import org.junit.jupiter.api.Test;

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
