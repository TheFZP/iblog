package com.iblog.module.paper.support;

import com.iblog.module.stock.model.KlineItem;
import com.iblog.module.stock.model.StockQuote;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailyBarAssemblerTest {

    private static KlineItem bar(String date, double close) {
        KlineItem item = new KlineItem();
        item.setDate(date);
        item.setOpen(close);
        item.setHigh(close + 0.2);
        item.setLow(close - 0.2);
        item.setClose(close);
        item.setVolume(1000);
        item.setTurnover(close * 1000);
        return item;
    }

    private static StockQuote quote(double price, double prevClose) {
        StockQuote quote = new StockQuote();
        quote.setPrice(price);
        quote.setPrevClose(prevClose);
        quote.setOpen(price - 0.1);
        quote.setHigh(price + 0.1);
        quote.setLow(price - 0.2);
        quote.setVolume(5000);
        quote.setTurnover(6000); // 万元
        return quote;
    }

    @Test
    void replacesStaleTodayBarWithCloseQuote() {
        DailyBarAssembler.Result result = DailyBarAssembler.assemble(
                "2026-11-14",
                List.of(bar("2026-11-12", 9.9), bar("2026-11-14", 10.0)),
                quote(10.5, 9.9));

        assertEquals(2, result.bars().size());
        KlineItem today = result.bars().get(1);
        assertEquals("2026-11-14", today.getDate());
        assertEquals(10.5, today.getClose(), 1e-9);
        assertEquals(5000, today.getVolume(), 1e-9);
        assertEquals(60_000_000, today.getTurnover(), 1e-9); // 6000 万 → 6000 万元
        assertTrue(result.reliable());
    }

    @Test
    void appendsTodayBarWhenMissing() {
        DailyBarAssembler.Result result = DailyBarAssembler.assemble(
                "2026-11-14", List.of(bar("2026-11-13", 10.0)), quote(10.5, 10.0));
        assertEquals(2, result.bars().size());
        assertEquals("2026-11-14", result.bars().get(1).getDate());
    }

    @Test
    void flagsAdjustmentMismatch() {
        // 前复权缓存最后一根 10，但行情昨收 11：偏差 9% > 2%
        DailyBarAssembler.Result result = DailyBarAssembler.assemble(
                "2026-11-14", List.of(bar("2026-11-13", 10.0)), quote(11.2, 11.0));
        assertFalse(result.reliable());
        assertEquals(9.09, result.prevCloseDiffPct(), 0.01);
    }

    @Test
    void handlesMissingQuote() {
        DailyBarAssembler.Result result = DailyBarAssembler.assemble(
                "2026-11-14", List.of(bar("2026-11-13", 10.0)), null);
        assertEquals(1, result.bars().size());
        assertFalse(result.reliable());
    }
}
