package com.iblog.module.stock.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarginTargetServiceTest {

    @Test
    void parseExtractsNormalizedCodes() {
        String json = """
                {"result":{"data":[
                  {"SECUCODE":"600519.SH","SECURITY_NAME_ABBR":"贵州茅台"},
                  {"SECUCODE":"000001.SZ","SECURITY_NAME_ABBR":"平安银行"},
                  {"SECUCODE":"300750.SZ","SECURITY_NAME_ABBR":"宁德时代"},
                  {"SECUCODE":"600519.SH","SECURITY_NAME_ABBR":"贵州茅台"}
                ]},"success":true}
                """;
        Set<String> codes = MarginTargetService.parse(json);
        assertEquals(3, codes.size());
        assertTrue(codes.contains("600519"));
        assertTrue(codes.contains("000001"));
        assertTrue(codes.contains("300750"));
    }

    @Test
    void parseToleratesBadPayload() {
        assertTrue(MarginTargetService.parse("{}").isEmpty());
        assertTrue(MarginTargetService.parse("not json").isEmpty());
    }
}