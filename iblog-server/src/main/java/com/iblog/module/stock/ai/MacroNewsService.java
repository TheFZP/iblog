package com.iblog.module.stock.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.iblog.module.paper.support.TradeCalendar;
import com.iblog.module.stock.entity.StockNewsArticle;
import com.iblog.module.stock.mapper.StockNewsArticleMapper;
import com.iblog.module.stock.support.StockHttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 隔夜外盘 / 宏观新闻：从东方财富快讯里挑出与美股、日韩、美联储和全球风险相关的条目。
 *
 * <p><b>为什么不用个股搜索接口</b>：预测用的个股新闻走东财「关键词搜索」，实测它只索引
 * 带证券标签的稿件，「美联储」「美股」「纳指」这类词查不到结果（返回 0 条），
 * 拿不到真正的宏观资讯。快讯流（{@code getlist_102}）是全量财经电讯，按关键词过滤
 * 反而更完整，而且一次请求就能拿 50 条，不需要为每个主题各搜一次。</p>
 *
 * <p><b>为什么单独成一个服务</b>：它产出的是「全市场级」新闻，不是某只票的新闻，
 * 不能混进 {@link AiNewsScoreService} 的个股缓存里——那会让每只票都继承同一份标题。</p>
 *
 * <p>整条链路 best-effort：快讯拉不到或模型不可用时，关键词分照常给（或直接不可用），
 * 不能因为外盘而让 15:30 的主流程失败。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MacroNewsService {

    private static final String FEED_URL =
            "https://newsapi.eastmoney.com/kuaixun/v1/getlist_102_ajaxResult_50_1_.html";
    private static final String REFERER = "https://finance.eastmoney.com/";
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    /** 快讯流里一天能命中多少条宏观条目；超过这个数的标题对模型也是噪声。 */
    private static final int MAX_ITEMS = 8;
    /** 时效窗口：隔夜外盘新闻以 48 小时为界，覆盖周末。 */
    private static final int WINDOW_HOURS = 48;
    /** 落库用的伪代码：宏观新闻没有归属股票，用固定标识便于按 scope 区分。 */
    static final String MACRO_CODE = "MACRO";
    private static final String MACRO_NAME = "外盘宏观";

    /**
     * 命中这些词就认为属于外盘/宏观主题。
     *
     * <p>宁可多抓几条让模型筛，也不要漏掉真正影响次日开盘的信息；
     * 落在 8 条以内，prompt 成本可以忽略。</p>
     */
    private static final String[] TOPIC_KEYWORDS = {
            "美股", "纳指", "纳斯达克", "道指", "道琼斯", "标普", "费城半导体",
            "日经", "日本股市", "日股", "韩国", "KOSPI",
            "美联储", "非农", "CPI", "关税", "隔夜", "亚太股市", "欧股", "华尔街"
    };

    private static final DateTimeFormatter INPUT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter SHORT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final String PROMPT_SYSTEM =
            "你是一名 A 股隔夜外盘分析师。下面是从东方财富快讯里筛出的、"
                    + "与美股、日韩股市、美联储和全球宏观相关的标题与摘要。"
                    + "只根据这些内容判断对次日 A 股整体情绪的影响，输出严格 JSON："
                    + "{\"score\":-100~100整数,\"summary\":\"一句话结论（不超过40字）\"}。"
                    + "评分口径：重大利好 60~100，一般利好 20~59，中性 -19~19，"
                    + "一般利空 -59~-20，重大利空 -100~-60；没有实质信息时给 0。"
                    + "传闻与自媒体观点只能给 0~30 且 summary 注明“传闻”。"
                    + "不要输出任何 JSON 以外的内容。";

    /**
     * 缓存时长：模拟盘新闻扫描 9:00–15:05 每 30 分钟一轮，不缓存等于一天多打十几次快讯
     * 和十几次模型调用；宏观新闻是慢变量，半小时前的快照完全够用。
     */
    private static final long CACHE_MS = 30 * 60_000L;
    /** 没抓到内容时只缓存一会儿，别让一次网络抖动把半天都锁成「没有宏观新闻」。 */
    private static final long FAILURE_CACHE_MS = 2 * 60_000L;

    private final StockHttpClient http;
    private final NewsKeywordScorer keywordScorer;
    private final AiConfigService configService;
    private final AiLlmClient llmClient;
    private final StockNewsArticleMapper articleMapper;
    private final TradeCalendar calendar;

    private volatile MacroNews cache;
    private volatile long cacheAt;

    /**
     * 宏观新闻结果。
     *
     * @param score     情绪分 -100~100
     * @param summary   一句话结论（AI 可用时由模型给）
     * @param headlines 命中的快讯
     * @param aiScored  true 表示分数来自模型，false 表示关键词兜底
     */
    public record MacroNews(int score, String summary,
                            List<AiWebSearchService.SearchHit> headlines, boolean aiScored) {

        public static MacroNews empty() {
            return new MacroNews(0, "", List.of(), false);
        }

        public boolean available() {
            return !headlines.isEmpty();
        }

        /** 拼给个股精判 prompt 的行，带 [宏观] 前缀，避免与个股新闻混淆。 */
        public List<String> promptLines() {
            List<String> lines = new ArrayList<>();
            for (AiWebSearchService.SearchHit hit : headlines) {
                String source = hit.source() == null || hit.source().isBlank() ? "" : "[" + hit.source() + "] ";
                lines.add("[宏观] " + source + hit.title());
            }
            return lines;
        }
    }

    /** 抓取并打分（带缓存）；任何一步失败都退化成空结果，不抛异常。 */
    public MacroNews scan() {
        long now = System.currentTimeMillis();
        MacroNews cached = cache;
        if (cached != null && now - cacheAt < (cached.available() ? CACHE_MS : FAILURE_CACHE_MS)) {
            return cached;
        }
        MacroNews result = doScan();
        cache = result;
        cacheAt = now;
        return result;
    }

    private MacroNews doScan() {
        if (!calendar.isTradingDay(calendar.today())) {
            return MacroNews.empty();
        }
        List<AiWebSearchService.SearchHit> hits;
        try {
            hits = fetchMacroHits();
        } catch (RuntimeException ex) {
            log.warn("宏观新闻：快讯拉取失败 err={}", ex.getMessage());
            return MacroNews.empty();
        }
        if (hits.isEmpty()) {
            log.info("宏观新闻：快讯里没有命中外盘主题的条目");
            return MacroNews.empty();
        }

        int score = keywordScorer.scoreAll(hits);
        String summary = "";
        boolean aiScored = false;
        AiConfigService.Config config = configService.load();
        if (config.available()) {
            try {
                LlmResult refined = refineWithLlm(config, hits);
                if (refined != null) {
                    score = refined.score();
                    summary = refined.summary();
                    aiScored = true;
                }
            } catch (RuntimeException ex) {
                log.warn("宏观新闻：模型精判失败，保留关键词分 err={}", ex.getMessage());
            }
        }
        score = Math.max(-60, Math.min(60, score));
        persistArticles(hits);
        log.info("宏观新闻：命中={} 分={} 来源={}", hits.size(), score, aiScored ? "ai" : "keyword");
        return new MacroNews(score, summary, hits, aiScored);
    }

    /** 拉快讯、按主题和时效过滤；格式异常时返回空列表而不是抛错。 */
    List<AiWebSearchService.SearchHit> fetchMacroHits() {
        String body = http.getText(FEED_URL, REFERER, StandardCharsets.UTF_8);
        return parseFeed(body, LocalDateTime.now(), WINDOW_HOURS, MAX_ITEMS);
    }

    /**
     * 解析 {@code var ajaxResult={...}}，过滤主题与时效。
     *
     * <p>单独抽成静态方法便于用固定报文做单测。</p>
     */
    static List<AiWebSearchService.SearchHit> parseFeed(String body, LocalDateTime now,
                                                        int windowHours, int maxItems) {
        List<AiWebSearchService.SearchHit> hits = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return hits;
        }
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.warn("宏观新闻：快讯不是预期格式，前 120 字符={}",
                    body.substring(0, Math.min(120, body.length())));
            return hits;
        }
        JsonNode root;
        try {
            root = AiJson.MAPPER.readTree(body.substring(start, end + 1));
        } catch (Exception ex) {
            log.warn("宏观新闻：快讯解析失败 err={}", ex.toString());
            return hits;
        }
        JsonNode list = root.path("LivesList");
        if (!list.isArray()) {
            return hits;
        }
        LocalDateTime floor = now.minusHours(Math.max(1, windowHours));
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : list) {
            String title = item.path("title").asText("").trim();
            String digest = item.path("digest").asText("").trim();
            String url = item.path("url_w").asText("").trim();
            if (title.isEmpty() || !AiNewsScoreService.isHttpUrl(url)) {
                continue;
            }
            if (!matchesTopic(title, digest)) {
                continue;
            }
            LocalDateTime published = parseTime(item.path("showtime").asText(""));
            if (published != null && published.isBefore(floor)) {
                continue;
            }
            if (!seen.add(url)) {
                continue;
            }
            hits.add(new AiWebSearchService.SearchHit(title, url, digest,
                    item.path("showtime").asText(""), "东方财富快讯"));
            if (hits.size() >= maxItems) {
                break;
            }
        }
        return hits;
    }

    /** 标题或摘要命中任一主题词即算宏观新闻。 */
    static boolean matchesTopic(String title, String digest) {
        String text = (title == null ? "" : title) + " " + (digest == null ? "" : digest);
        for (String keyword : TOPIC_KEYWORDS) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private record LlmResult(int score, String summary) {
    }

    private LlmResult refineWithLlm(AiConfigService.Config config,
                                    List<AiWebSearchService.SearchHit> hits) {
        ArrayNode messages = AiJson.MAPPER.createArrayNode();
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", PROMPT_SYSTEM);
        StringBuilder news = new StringBuilder();
        for (AiWebSearchService.SearchHit hit : hits) {
            if (news.length() > 0) {
                news.append('\n');
            }
            news.append('-').append('[').append(hit.source()).append("] ")
                    .append(hit.title()).append('：').append(hit.snippet());
        }
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", news.toString());

        String raw = llmClient.complete(config.apiKey(), config.model(), messages, 300);
        JsonNode root;
        try {
            root = AiJson.MAPPER.readTree(cleanJson(raw));
        } catch (Exception ex) {
            return null;
        }
        if (root == null || !root.has("score")) {
            return null;
        }
        int score = Math.max(-100, Math.min(100, root.path("score").asInt(0)));
        return new LlmResult(score, root.path("summary").asText(""));
    }

    /** 从模型输出里截出 JSON 对象；模型偶尔会包一层说明文字。 */
    static String cleanJson(String raw) {
        if (raw == null) {
            return "{}";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return start >= 0 && end > start ? raw.substring(start, end + 1) : "{}";
    }

    private LocalDateTime parseTimeSafe(String text) {
        return parseTime(text);
    }

    static LocalDateTime parseTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.trim(), INPUT_TIME);
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(text.trim(), SHORT_TIME);
            } catch (Exception ignoredToo) {
                return null;
            }
        }
    }

    private void persistArticles(List<AiWebSearchService.SearchHit> hits) {
        LocalDateTime now = LocalDateTime.now();
        try {
            articleMapper.delete(new LambdaQueryWrapper<StockNewsArticle>()
                    .eq(StockNewsArticle::getTradeDate, calendar.today())
                    .eq(StockNewsArticle::getCode, MACRO_CODE));
            for (AiWebSearchService.SearchHit hit : hits) {
                StockNewsArticle article = new StockNewsArticle();
                article.setTradeDate(calendar.today());
                article.setCode(MACRO_CODE);
                article.setName(MACRO_NAME);
                article.setTitle(truncate(hit.title(), 300));
                article.setUrl(truncate(hit.url(), 1000));
                article.setUrlHash(AiNewsScoreService.md5(hit.url()));
                article.setSource(truncate(hit.source(), 64));
                article.setPublishedAt(parseTimeSafe(hit.date()));
                article.setSnippet(truncate(hit.snippet(), 1000));
                article.setScope("market");
                article.setBatch("macro");
                article.setFetchedAt(now);
                articleMapper.insert(article);
            }
        } catch (RuntimeException ex) {
            log.debug("宏观新闻落库失败 err={}", ex.getMessage());
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
