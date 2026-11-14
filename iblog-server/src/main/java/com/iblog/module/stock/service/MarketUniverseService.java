package com.iblog.module.stock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iblog.module.stock.model.HotStockItem;
import com.iblog.module.stock.support.StockCode;
import com.iblog.module.stock.support.StockHttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全市场 A 股列表。
 *
 * <p>行情接口只提供单只/批量报价，没有「全市场股票列表」；东财 {@code clist}
 * 对线上机房 IP 会被 WAF 重置（见 {@link EastmoneyService} 的记录），所以这里走
 * 新浪行情中心 {@code hs_a} 节点分页：每页 100 条，全市场约 55 页。</p>
 *
 * <p>整轮抓取只在每天 15:30 的预测任务前触发一次，缓存 12 小时；失败时退回
 * 两融标的 + 热榜，保证预测任务仍有可扫描的股票池，而不是直接空跑。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketUniverseService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String REFERER_SINA = "https://finance.sina.com.cn/";

    private static final String URL_COUNT =
            "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php"
                    + "/Market_Center.getHQNodeStockCount?node=hs_a";

    private static final String URL_PAGE =
            "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php"
                    + "/Market_Center.getHQNodeData?num=" + 100
                    + "&sort=symbol&asc=1&node=hs_a&symbol=&_s_r_a=page&page=%d";

    /** 新浪每页硬上限 100，传更大也只返 100。 */
    private static final int PAGE_SIZE = 100;
    /** 沪深 A 股 5000+ 只；低于 3000 视为分页抓取不完整。 */
    private static final int MIN_TOTAL = 3000;
    /** 防御性上限：正常 60 页左右，超过说明上游分页行为异常。 */
    private static final int MAX_PAGES = 80;
    /** 列表一天变一次，12 小时缓存足够；任务本身也只在收盘后跑。 */
    private static final long CACHE_TTL_MS = 12 * 60 * 60 * 1000L;
    /** 分页抓取失败后的退避，避免 15:30 的补偿逻辑反复打上游。 */
    private static final long RETRY_BACKOFF_MS = 5 * 60 * 1000L;
    /** 页与页之间留一点间隔，55 次连续请求对免费接口已经不算轻。 */
    private static final long PAGE_INTERVAL_MS = 120L;
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(6);

    private final StockHttpClient http;

    private final MarginTargetService marginTargetService;

    private final HotListService hotListService;

    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    private volatile List<String> codes = List.of();
    private volatile long fetchedAt = 0L;
    private volatile long nextAttemptAt = 0L;

    /**
     * 当前全市场代码（归一化）。
     *
     * <p>缓存过期时同步刷新一次；抓到的新列表不完整则退回两融 + 热榜。
     * 永远不抛异常：调用方是收盘后的定时任务，空列表要让上层记日志而不是中断。</p>
     */
    public List<String> codes() {
        long now = System.currentTimeMillis();
        if (!codes.isEmpty() && now - fetchedAt < CACHE_TTL_MS) {
            return codes;
        }
        if (now < nextAttemptAt || !refreshing.compareAndSet(false, true)) {
            return codes;
        }
        try {
            List<String> fetched = fetchSinaAll();
            if (fetched.size() >= MIN_TOTAL) {
                codes = fetched;
                fetchedAt = System.currentTimeMillis();
                nextAttemptAt = 0L;
                log.info("全市场列表：新浪 hs_a 抓到 {} 只", fetched.size());
                return codes;
            }
            log.warn("全市场列表：新浪只抓到 {} 只（阈值 {}），改用两融 + 热榜兜底",
                    fetched.size(), MIN_TOTAL);
            List<String> fallback = fallbackUniverse();
            if (!fallback.isEmpty()) {
                codes = fallback;
                fetchedAt = System.currentTimeMillis();
                nextAttemptAt = fetchedAt + RETRY_BACKOFF_MS;
                log.warn("全市场列表：兜底池 {} 只（两融 + 热榜），{} 分钟后再试新浪",
                        fallback.size(), RETRY_BACKOFF_MS / 60_000);
            }
        } catch (RuntimeException ex) {
            // 保留上一份列表（如果有），并退避，避免上游抖动时反复整轮重试
            nextAttemptAt = System.currentTimeMillis() + RETRY_BACKOFF_MS;
            log.warn("全市场列表刷新失败：{}，{} 分钟后重试", ex.getMessage(), RETRY_BACKOFF_MS / 60_000);
        } finally {
            refreshing.set(false);
        }
        return codes;
    }

    /** 强制刷新（供手工触发 / 单测使用）。 */
    public List<String> refreshNow() {
        fetchedAt = 0L;
        nextAttemptAt = 0L;
        return codes();
    }

    // ---------- 新浪 ----------

    private List<String> fetchSinaAll() {
        int total = readCount();
        int pages = total > 0 ? (total + PAGE_SIZE - 1) / PAGE_SIZE : MAX_PAGES;
        pages = Math.min(pages, MAX_PAGES);

        Set<String> result = new LinkedHashSet<>();
        for (int page = 1; page <= pages; page++) {
            List<String> pageCodes = fetchSinaPage(page);
            if (pageCodes.isEmpty()) {
                break;
            }
            result.addAll(pageCodes);
            if (total > 0 && result.size() >= total) {
                break;
            }
            sleepBetweenPages();
        }
        return new ArrayList<>(result);
    }

    private int readCount() {
        try {
            String text = http.getTextNoKeepAlive(URL_COUNT, REFERER_SINA,
                    StandardCharsets.UTF_8, FETCH_TIMEOUT);
            if (text == null) {
                return -1;
            }
            return Integer.parseInt(text.trim().replace("\"", ""));
        } catch (Exception ex) {
            log.debug("全市场列表：数量接口失败 {}", ex.getMessage());
            return -1;
        }
    }

    private List<String> fetchSinaPage(int page) {
        String url = String.format(URL_PAGE, page);
        try {
            String text = http.getTextNoKeepAlive(url, REFERER_SINA,
                    StandardCharsets.UTF_8, FETCH_TIMEOUT);
            List<String> parsed = parsePage(text);
            if (parsed.isEmpty()) {
                log.debug("全市场列表：第 {} 页为空", page);
            }
            return parsed;
        } catch (Exception ex) {
            log.debug("全市场列表：第 {} 页失败 {}", page, ex.getMessage());
            return List.of();
        }
    }

    /** 解析新浪 {@code getHQNodeData} 的 JSON 数组，返回归一化 6 位代码。 */
    public static List<String> parsePage(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (Exception ex) {
            return List.of();
        }
        if (root == null || !root.isArray()) {
            return List.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode node : root) {
            String raw = node.path("code").asText("");
            if (raw.isEmpty()) {
                raw = node.path("symbol").asText("");
            }
            String code = StockCode.normalize(raw);
            if (isAShareCode(code)) {
                result.add(code);
            }
        }
        return new ArrayList<>(result);
    }

    private static boolean isAShareCode(String code) {
        return code.length() == 6 && code.chars().allMatch(Character::isDigit);
    }

    private static void sleepBetweenPages() {
        try {
            Thread.sleep(PAGE_INTERVAL_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- 兜底 ----------

    private List<String> fallbackUniverse() {
        Set<String> result = new LinkedHashSet<>();
        try {
            result.addAll(marginTargetService.codes());
        } catch (RuntimeException ex) {
            log.debug("全市场列表：两融标的兜底失败 {}", ex.getMessage());
        }
        try {
            for (HotStockItem item : hotListService.fetchHotList()) {
                String code = StockCode.normalize(item.getCode());
                if (isAShareCode(code)) {
                    result.add(code);
                }
            }
        } catch (RuntimeException ex) {
            log.debug("全市场列表：热榜兜底失败 {}", ex.getMessage());
        }
        return new ArrayList<>(result);
    }
}
