package com.iblog.module.stock.ai;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宏观新闻解析测试：只验证「哪些快讯该留下」和「什么时候该丢弃」，不碰网络与模型。
 */
class MacroNewsServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 21, 20, 0);

    private static String feed(String... items) {
        return "var ajaxResult={\"rc\":1,\"LivesList\":[" + String.join(",", items) + "]}";
    }

    private static String item(String title, String digest, String url, String time) {
        return "{\"title\":\"" + title + "\",\"digest\":\"" + digest + "\",\"url_w\":\"" + url
                + "\",\"showtime\":\"" + time + "\"}";
    }

    @Test
    void keepsTopicHitsAndDropsStockOnlyNews() {
        String body = feed(
                item("标普500再创新高", "美股三大指数集体收涨", "https://a.com/1", "2026-11-21 10:00:00"),
                item("光威复材回复提价", "投资者提问", "https://a.com/2", "2026-11-21 11:00:00"),
                item("韩国股市收盘下跌", "KOSPI 跌 1.2%", "https://a.com/3", "2026-11-21 15:00:00"));

        List<AiWebSearchService.SearchHit> hits =
                MacroNewsService.parseFeed(body, NOW, 48, 8);

        assertEquals(2, hits.size());
        assertEquals("标普500再创新高", hits.get(0).title());
        assertEquals("韩国股市收盘下跌", hits.get(1).title());
        assertEquals("东方财富快讯", hits.get(0).source());
    }

    @Test
    void dropsStaleAndNonHttpEntries() {
        String body = feed(
                item("美股上周回顾", "道指累涨", "https://a.com/old", "2026-11-10 10:00:00"),
                item("美股快讯", "纳指期货上涨", "javascript:void(0)", "2026-11-21 10:00:00"),
                item("美联储官员讲话", "非农数据", "http://a.com/new", "2026-11-21 12:00:00"));

        List<AiWebSearchService.SearchHit> hits =
                MacroNewsService.parseFeed(body, NOW, 48, 8);

        assertEquals(1, hits.size());
        assertEquals("美联储官员讲话", hits.get(0).title());
    }

    @Test
    void dedupesUrlsAndRespectsLimit() {
        String body = feed(
                item("美股一", "纳指", "https://a.com/1", "2026-11-21 10:00:00"),
                item("美股二", "道指", "https://a.com/1", "2026-11-21 11:00:00"),
                item("美股三", "标普", "https://a.com/3", "2026-11-21 12:00:00"));

        List<AiWebSearchService.SearchHit> hits =
                MacroNewsService.parseFeed(body, NOW, 48, 1);

        assertEquals(1, hits.size());
    }

    @Test
    void nonFeedBodyReturnsEmpty() {
        assertTrue(MacroNewsService.parseFeed("", NOW, 48, 8).isEmpty());
        assertTrue(MacroNewsService.parseFeed("<html>blocked</html>", NOW, 48, 8).isEmpty());
    }

    @Test
    void topicMatchingLooksAtTitleAndDigest() {
        assertTrue(MacroNewsService.matchesTopic("某公司公告", "受美股拖累"));
        assertFalse(MacroNewsService.matchesTopic("某公司公告", "业绩预增"));
    }
}
