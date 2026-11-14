package com.iblog.module.stock.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iblog.common.result.R;
import com.iblog.module.stock.ai.AiNewsScoreService;
import com.iblog.module.stock.entity.StockNewsScore;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 新闻情绪查询：候选/持仓的新闻徽标与新闻面板。
 *
 * <p>数据来自 {@code stock_news_score}（每 30 分钟由 {@code AiNewsScoreService} 扫描覆盖），
 * 本接口只读，不触发扫描，也不会因为上游失败而拖慢页面。</p>
 */
@RestController
@RequestMapping("/api/stock/news")
@RequiredArgsConstructor
public class StockNewsController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final int MAX_BATCH = 80;

    private final AiNewsScoreService newsService;

    /** 单只股票的新闻详情（含原始标题/来源/时间）。 */
    @GetMapping
    public R<NewsDetail> detail(@RequestParam String code) {
        StockNewsScore score = newsService.detail(code);
        if (score == null) {
            return R.ok(null);
        }
        return R.ok(new NewsDetail(score.getCode(), score.getName(), nz(score.getScore()),
                confidence(score), score.getLabel(), score.getSummary(), score.getModel(),
                updatedAt(score), parseHeadlines(score.getHeadlines())));
    }

    /** 批量查询（候选/持仓表格用），只返回分数与摘要，不含原始标题。 */
    @GetMapping("/batch")
    public R<List<NewsItem>> batch(@RequestParam String codes) {
        List<String> list = Arrays.stream(codes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .limit(MAX_BATCH)
                .toList();
        List<NewsItem> items = newsService.current(list).stream()
                .map(s -> new NewsItem(s.getCode(), s.getName(), nz(s.getScore()),
                        confidence(s), s.getLabel(), s.getSummary(), s.getModel(), updatedAt(s)))
                .toList();
        return R.ok(items);
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static double confidence(StockNewsScore score) {
        BigDecimal value = score.getConfidence();
        return value == null ? 0 : value.doubleValue();
    }

    private static String updatedAt(StockNewsScore score) {
        return score.getUpdateTime() == null ? "" : score.getUpdateTime().format(TIME_FORMAT);
    }

    private static List<Headline> parseHeadlines(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isArray()) {
                return List.of();
            }
            List<Headline> list = new ArrayList<>();
            for (JsonNode node : root) {
                String url = node.path("url").asText("");
                list.add(new Headline(
                        node.path("title").asText(""),
                        url.startsWith("http://") || url.startsWith("https://") ? url : "",
                        node.path("source").asText(""),
                        node.path("date").asText(""),
                        node.path("scope").asText("stock")));
            }
            return list;
        } catch (Exception e) {
            return List.of();
        }
    }

    public record NewsItem(String code, String name, int score, double confidence,
                           String label, String summary, String model, String updatedAt) {
    }

    public record Headline(String title, String url, String source, String date, String scope) {
    }

    public record NewsDetail(String code, String name, int score, double confidence,
                             String label, String summary, String model, String updatedAt,
                             List<Headline> headlines) {
    }
}