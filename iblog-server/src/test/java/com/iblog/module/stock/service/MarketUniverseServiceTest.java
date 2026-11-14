package com.iblog.module.stock.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketUniverseServiceTest {

    @Test
    void parsesSinaPageAndDeduplicates() {
        String json = "["
                + "{\"symbol\":\"sh600000\",\"code\":\"600000\",\"name\":\"浦发银行\"},"
                + "{\"symbol\":\"sz000001\",\"code\":\"000001\",\"name\":\"平安银行\"},"
                + "{\"symbol\":\"sh600000\",\"code\":\"600000\",\"name\":\"浦发银行\"},"
                + "{\"symbol\":\"hk00700\",\"code\":\"00700\",\"name\":\"腾讯控股\"}"
                + "]";
        List<String> codes = MarketUniverseService.parsePage(json);
        assertEquals(List.of("600000", "000001"), codes);
    }

    @Test
    void fallsBackToSymbolWhenCodeMissing() {
        assertEquals(List.of("600519"),
                MarketUniverseService.parsePage("[{\"symbol\":\"sh600519\"}]"));
    }

    @Test
    void malformedBodyYieldsEmptyList() {
        assertTrue(MarketUniverseService.parsePage("<html>403</html>").isEmpty());
        assertTrue(MarketUniverseService.parsePage("").isEmpty());
        assertTrue(MarketUniverseService.parsePage(null).isEmpty());
    }
}
