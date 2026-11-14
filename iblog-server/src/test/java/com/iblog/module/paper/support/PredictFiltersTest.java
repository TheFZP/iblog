package com.iblog.module.paper.support;

import com.iblog.module.stock.model.StockQuote;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PredictFiltersTest {

    private final PredictFilters.Options options = new PredictFilters.Options(
            5000, 2, 500, 9.5, true, true, true);

    private static StockQuote quote(String code, String name, double price, double prevClose) {
        StockQuote quote = new StockQuote();
        quote.setCode(code);
        quote.setName(name);
        quote.setPrice(price);
        quote.setPrevClose(prevClose);
        quote.setOpen(prevClose);
        quote.setHigh(price);
        quote.setLow(prevClose);
        quote.setVolume(100_000);
        quote.setTurnover(20_000);
        quote.setChangePct((price - prevClose) / prevClose * 100);
        quote.setTurnoverRate(5);
        quote.setFloatMarketCap(100);
        return quote;
    }

    @Test
    void normalStockPasses() {
        assertNull(PredictFilters.rejectReason(quote("600000", "浦发银行", 10.2, 10), options));
    }

    @Test
    void rejectsSpecialTreatmentAndBse() {
        assertNotNull(PredictFilters.rejectReason(quote("600000", "ST浦发", 10, 10), options));
        assertNotNull(PredictFilters.rejectReason(quote("600000", "*ST浦发", 10, 10), options));
        assertNotNull(PredictFilters.rejectReason(quote("600000", "退市浦发", 10, 10), options));
        assertNotNull(PredictFilters.rejectReason(quote("600000", "N浦发", 10, 10), options));
        assertNotNull(PredictFilters.rejectReason(quote("430047", "北交测试", 10, 10), options));
        // 关掉 ST 过滤后放行
        PredictFilters.Options loose = new PredictFilters.Options(
                5000, 2, 500, 9.5, false, false, true);
        assertNull(PredictFilters.rejectReason(quote("600000", "ST浦发", 10, 10), loose));
    }

    @Test
    void rejectsSuspendedAndThinVolume() {
        StockQuote suspended = quote("600000", "浦发银行", 10, 10);
        suspended.setVolume(0);
        assertNotNull(PredictFilters.rejectReason(suspended, options));

        StockQuote thin = quote("600000", "浦发银行", 10, 10);
        thin.setTurnover(1000);
        assertNotNull(PredictFilters.rejectReason(thin, options));

        StockQuote cheap = quote("600000", "浦发银行", 1.5, 1.5);
        assertNotNull(PredictFilters.rejectReason(cheap, options));
    }

    @Test
    void rejectsClosingLimitUp() {
        // 主板 10%：10 → 11 封板
        StockQuote main = quote("600000", "浦发银行", 11, 10);
        assertTrue(PredictFilters.isLimitUpAtClose("600000", "浦发银行", main));
        assertNotNull(PredictFilters.rejectReason(main, options));

        // 创业板 20%：10 → 12 才是涨停
        StockQuote gem = quote("300750", "宁德时代", 11, 10);
        assertFalse(PredictFilters.isLimitUpAtClose("300750", "宁德时代", gem));
        StockQuote gemLimit = quote("300750", "宁德时代", 12, 10);
        assertTrue(PredictFilters.isLimitUpAtClose("300750", "宁德时代", gemLimit));

        assertEquals(20, PredictFilters.limitUpPct("688001", "科创测试"), 1e-9);
        assertEquals(5, PredictFilters.limitUpPct("600000", "ST浦发"), 1e-9);
        assertEquals(10, PredictFilters.limitUpPct("000001", "平安银行"), 1e-9);
    }
}
