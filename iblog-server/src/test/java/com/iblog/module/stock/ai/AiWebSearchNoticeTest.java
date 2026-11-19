package com.iblog.module.stock.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiWebSearchNoticeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void keepsOnlyOwnCompanyNoticesAndBuildsLinks() throws Exception {
        JsonNode list = JSON.readTree("""
                [
                  {"code":"AN1","securityShortName":"通化金马",
                   "title":"<em>通化金马</em>:股票交易异常波动公告",
                   "content":"本公司股票交易异常波动","date":"2026-11-19 00:00:00"},
                  {"code":"AN2","securityShortName":"睿能科技",
                   "title":"睿能科技:发行股份购买资产报告书",
                   "content":"终端客户包括通化金马","date":"2026-11-19 00:00:00"},
                  {"code":"AN3","securityShortName":"通化金马",
                   "title":"<em>通化金马</em>:股票交易异常波动公告",
                   "content":"重复条目","date":"2026-11-19 00:00:00"}
                ]
                """);
        List<AiWebSearchService.SearchHit> hits = AiWebSearchService.parseNoticeItems(
                list, "通化金马", "000766", 3);
        assertEquals(1, hits.size());
        AiWebSearchService.SearchHit hit = hits.get(0);
        assertEquals("通化金马:股票交易异常波动公告", hit.title());
        assertEquals("https://data.eastmoney.com/notices/detail/000766/AN1.html", hit.url());
        assertEquals("交易所公告", hit.source());
    }

    @Test
    void matchesFullWidthNamesAndSpaces() throws Exception {
        JsonNode list = JSON.readTree("""
                [
                  {"code":"AN4","securityShortName":"万  科Ａ",
                   "title":"万  科Ａ:关于股票交易异常波动的公告","content":"","date":"2026-11-19"}
                ]
                """);
        List<AiWebSearchService.SearchHit> hits = AiWebSearchService.parseNoticeItems(
                list, "万科A", "000002", 3);
        assertEquals(1, hits.size());
    }

    @Test
    void fallsBackToTitlePrefixWhenSecurityNameMissing() throws Exception {
        JsonNode list = JSON.readTree("""
                [
                  {"code":"AN5","securityShortName":"",
                   "title":"贵州茅台:关于回购股份的进展公告","content":"","date":"2026-11-19"}
                ]
                """);
        List<AiWebSearchService.SearchHit> hits = AiWebSearchService.parseNoticeItems(
                list, "贵州茅台", "600519", 3);
        assertEquals(1, hits.size());
    }

    @Test
    void respectsLimitAndToleratesBadPayload() throws Exception {
        JsonNode list = JSON.readTree("""
                [
                  {"code":"AN6","securityShortName":"宁德时代","title":"宁德时代:公告一","content":"","date":"1"},
                  {"code":"AN7","securityShortName":"宁德时代","title":"宁德时代:公告二","content":"","date":"2"}
                ]
                """);
        assertEquals(1, AiWebSearchService.parseNoticeItems(list, "宁德时代", "300750", 1).size());
        assertTrue(AiWebSearchService.parseNoticeItems(null, "宁德时代", "300750", 3).isEmpty());
        assertTrue(AiWebSearchService.parseNoticeItems(JSON.readTree("{}"), "宁德时代", "300750", 3).isEmpty());
    }
}
