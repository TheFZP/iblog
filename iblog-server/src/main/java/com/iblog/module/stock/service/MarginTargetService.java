package com.iblog.module.stock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iblog.module.stock.support.StockCode;
import com.iblog.module.stock.support.StockHttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 融资融券标的清单。
 *
 * <p>行情接口（腾讯 / 东财 push2）不提供「这只票能不能融资融券」的字段，只能单独取
 * 交易所披露的标的清单。两市的取数口径不同：</p>
 * <ul>
 *   <li><b>深市</b>：深交所「融资融券标的证券信息」的 xlsx（{@code CATALOGID=1834_xxpl}），
 *       覆盖深主板 + 创业板，一次下载即可；</li>
 *   <li><b>沪市</b>：东财数据中心「个股融资融券」（{@code RPTA_WEB_RZRQ_GGMX}），
 *       按最新交易日分页，覆盖沪主板 + 科创板。</li>
 * </ul>
 *
 * <p>为什么不用东财行情 clist（{@code fs=b:BK0596}）：本项目出口 IP 访问
 * {@code push2.eastmoney.com} 的 clist 会被 WAF 直接 ECONNRESET（见 {@link EastmoneyService}
 * 的同类记录），所以标的清单必须走非 clist 的接口。</p>
 *
 * <p>清单每天最多变一次，缓存 12 小时；整轮抓取在后台线程执行，不阻塞用户请求。
 * 任一市场抓取失败时沿用旧清单，并退避 5 分钟再试，绝不因为上游抖动把「融」标签抹掉。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarginTargetService {

    /** 深交所标的证券 xlsx（证券类型=股票，日期由沪市最新交易日决定）。 */
    private static final String SZSE_URL =
            "https://www.szse.cn/api/report/ShowReport"
                    + "?SHOWTYPE=xlsx&CATALOGID=1834_xxpl&txtDate=%s"
                    + "&tab1PAGENO=1&random=0.7&TABKEY=tab1&zqlb=GP";
    private static final String SZSE_REFERER = "https://www.szse.cn/disclosure/margin/object/index.html";

    /** 东财报表单页上限实测为 500。 */
    private static final int SH_PAGE_SIZE = 500;
    /** 单个市场至少这么多只才算抓取成功（沪深各有上千只标的）。 */
    private static final int MIN_MARKET_TARGETS = 500;
    private static final String SH_REFERER = "https://data.eastmoney.com/";
    /** 最新交易日：报表按日期倒序，取第一行即可。 */
    private static final String SH_LATEST_URL =
            "https://datacenter-web.eastmoney.com/api/data/v1/get"
                    + "?reportName=RPTA_WEB_RZRQ_GGMX&columns=DATE"
                    + "&pageNumber=1&pageSize=1&source=WEB&client=WEB"
                    + "&sortColumns=date&sortTypes=-1";
    /**
     * 东财数据中心个股两融明细（沪市）。
     *
     * <p>必须带日期过滤和唯一排序：不带 filter 时该报表的分页会重复返回同一页
     * （实测 pageNumber=2/3/4 返回完全相同的数据），这是报表 SQL 排序不稳定导致的。</p>
     */
    private static final String SH_URL =
            "https://datacenter-web.eastmoney.com/api/data/v1/get"
                    + "?reportName=RPTA_WEB_RZRQ_GGMX&columns=SCODE,DATE"
                    + "&pageNumber=%d&pageSize=" + SH_PAGE_SIZE
                    + "&source=WEB&client=WEB&filter=%%28DATE%%3D%%27%s%%27%%29"
                    + "&sortColumns=SCODE&sortTypes=1";
    /** 沪市约 2000 只，5 页足够；上限保护防止异常翻页。 */
    private static final int MAX_SH_PAGES = 20;
    /** 东财报表分页之间留一点间隔，避免触发限流。 */
    private static final long SH_PAGE_INTERVAL_MS = 300L;

    /** 两个市场都成功时至少应有这么多只，低于此值视为上游异常。 */
    private static final int MIN_TARGETS = 2000;

    /** 标的清单一天更新一次，缓存 12 小时足够，同时避免每个页面请求都打上游。 */
    private static final long CACHE_TTL_MS = 12 * 60 * 60 * 1000L;
    /** 刷新失败后的退避时间，避免每个请求都触发整轮抓取。 */
    private static final long RETRY_BACKOFF_MS = 5 * 60 * 1000L;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ROW = Pattern.compile("<row[^>]*>(.*?)</row>", Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile(
            "<c r=\"([A-Z]+)\\d+\"[^>]*>(?:<is><t>(.*?)</t></is>|<v>(.*?)</v>)</c>", Pattern.DOTALL);

    private final StockHttpClient http;

    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    private volatile Set<String> codes = Set.of();
    private volatile long fetchedAt = 0L;
    private volatile long nextAttemptAt = 0L;

    /** 应用启动后预热一次，避免首个页面请求时清单还是空的。 */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        codes();
    }

    /**
     * 当前全部两融标的（归一化代码）。
     *
     * <p>过期或为空时只触发后台刷新并立即返回旧值，不阻塞调用方。</p>
     */
    public Set<String> codes() {
        long now = System.currentTimeMillis();
        boolean stale = codes.isEmpty() || now - fetchedAt > CACHE_TTL_MS;
        if (stale && now >= nextAttemptAt && refreshing.compareAndSet(false, true)) {
            // 抓取一轮要几秒到十几秒，放到独立守护线程，绝不占用请求线程或公共线程池。
            Thread worker = new Thread(this::refreshSafely, "margin-target-refresh");
            worker.setDaemon(true);
            worker.start();
        }
        return codes;
    }

    /** 单只股票是否两融标的。 */
    public boolean isMargin(String rawCode) {
        String code = StockCode.normalize(rawCode);
        return !code.isEmpty() && codes().contains(code);
    }

    private void refreshSafely() {
        try {
            refresh();
        } catch (Throwable ex) {
            log.warn("融资融券标的刷新异常，沿用旧清单（{} 只）：{}", codes.size(), ex.getMessage());
        } finally {
            refreshing.set(false);
        }
    }

    private void refresh() {
        Set<String> merged = new LinkedHashSet<>();
        boolean complete = true;

        String date = null;
        try {
            Shanghai sh = fetchShanghai();
            date = sh.date();
            merged.addAll(sh.codes());
            if (!sh.complete()) {
                complete = false;
            }
            log.info("沪市两融标的：{} 只（{}）", sh.codes().size(), date);
        } catch (RuntimeException ex) {
            complete = false;
            log.warn("沪市两融标的获取失败：{}", ex.getMessage());
        }

        if (date == null) {
            complete = false;
        } else {
            try {
                Set<String> shenzhen = fetchShenzhen(date);
                merged.addAll(shenzhen);
                if (shenzhen.size() < MIN_MARKET_TARGETS) {
                    complete = false;
                }
                log.info("深市两融标的：{} 只（{}）", shenzhen.size(), date);
            } catch (RuntimeException ex) {
                complete = false;
                log.warn("深市两融标的获取失败：{}", ex.getMessage());
            }
        }

        if (complete && merged.size() >= MIN_TARGETS) {
            codes = merged;
            fetchedAt = System.currentTimeMillis();
            nextAttemptAt = 0L;
            log.info("融资融券标的刷新成功：{} 只", merged.size());
        } else if (codes.isEmpty() && !merged.isEmpty()) {
            // 冷启动只拿到一个市场，先展示这部分标签，5 分钟后再补全。
            codes = merged;
            fetchedAt = 0L;
            nextAttemptAt = System.currentTimeMillis() + RETRY_BACKOFF_MS;
            log.warn("融资融券标的仅部分获取（{} 只），稍后重试", merged.size());
        } else {
            nextAttemptAt = System.currentTimeMillis() + RETRY_BACKOFF_MS;
            log.warn("融资融券标的刷新不完整，沿用旧清单（{} 只）", codes.size());
        }
    }

    /** 抓取沪市最新交易日的全部两融标的，同时返回该交易日供深市复用。 */
    private Shanghai fetchShanghai() {
        String date = fetchLatestDate();
        Set<String> codes = new LinkedHashSet<>();
        int total = 0;
        for (int page = 1; page <= MAX_SH_PAGES; page++) {
            String text = http.getText(String.format(SH_URL, page, date), SH_REFERER, StandardCharsets.UTF_8);
            ShanghaiPage current = parseShanghai(text);
            if (page == 1) {
                total = current.total();
            }
            int before = codes.size();
            codes.addAll(current.codes());
            if (codes.size() == before) {
                // 分页没有带来新数据，继续翻也不会有了。
                break;
            }
            if (total > 0 && codes.size() >= total) {
                break;
            }
            pause(SH_PAGE_INTERVAL_MS);
        }
        if (codes.isEmpty()) {
            throw new IllegalStateException("沪市两融明细为空");
        }
        return new Shanghai(codes, date, total > 0 && codes.size() >= total);
    }

    /** 报表按日期倒序，第一行的日期就是最新交易日。 */
    private String fetchLatestDate() {
        String text = http.getText(SH_LATEST_URL, SH_REFERER, StandardCharsets.UTF_8);
        try {
            JsonNode data = JSON.readTree(text).path("result").path("data");
            if (data.isArray() && !data.isEmpty()) {
                String date = data.get(0).path("DATE").asText("");
                if (date.length() >= 10) {
                    return date.substring(0, 10);
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("沪市最新交易日解析失败：" + ex.getMessage(), ex);
        }
        throw new IllegalStateException("沪市最新交易日为空");
    }

    /** 深交所标的证券 xlsx，一次下载。 */
    private Set<String> fetchShenzhen(String date) {
        byte[] bytes = http.getBytes(String.format(SZSE_URL, date), SZSE_REFERER);
        Set<String> codes = parseXlsx(bytes);
        if (codes.isEmpty()) {
            throw new IllegalStateException("深市标的清单为空");
        }
        return codes;
    }

    /** 解析深交所 xlsx：xlsx 本质是 zip，表格数据在 {@code xl/worksheets/sheet1.xml}。 */
    static Set<String> parseXlsx(byte[] bytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().endsWith("xl/worksheets/sheet1.xml")) {
                    String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    return parseSheetXml(xml);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("深交所标的 xlsx 解析失败：" + ex.getMessage(), ex);
        }
        throw new IllegalStateException("深交所标的 xlsx 缺少工作表");
    }

    /**
     * 解析深交所工作表 XML。
     *
     * <p>该 xlsx 的单元格都是内联字符串（{@code t="inlineStr"}）：A=证券代码、
     * C=融资标的、D=融券标的，取值为 Y/N。只保留融资或融券至少一个为 Y 的证券。</p>
     */
    static Set<String> parseSheetXml(String xml) {
        Set<String> codes = new LinkedHashSet<>();
        Matcher rows = ROW.matcher(xml);
        while (rows.find()) {
            Map<String, String> cells = new HashMap<>();
            Matcher cell = CELL.matcher(rows.group(1));
            while (cell.find()) {
                cells.put(cell.group(1), cell.group(2) != null ? cell.group(2) : cell.group(3));
            }
            String raw = cells.get("A");
            if (raw == null || "证券代码".equals(raw)) {
                continue;
            }
            boolean margin = "Y".equals(cells.get("C")) || "Y".equals(cells.get("D"));
            String code = StockCode.normalize(raw);
            if (margin && !code.isEmpty()) {
                codes.add(code);
            }
        }
        return codes;
    }

    /** 解析一页沪市两融明细：{@code result.data[].SCODE} 是代码，{@code result.count} 是总数。 */
    static ShanghaiPage parseShanghai(String text) {
        Set<String> codes = new LinkedHashSet<>();
        int total = 0;
        try {
            JsonNode result = JSON.readTree(text).path("result");
            total = result.path("count").asInt(0);
            JsonNode data = result.path("data");
            if (data.isArray()) {
                for (JsonNode node : data) {
                    String code = StockCode.normalize(node.path("SCODE").asText(""));
                    if (!code.isEmpty()) {
                        codes.add(code);
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("沪市两融明细解析失败：{}", ex.getMessage());
        }
        return new ShanghaiPage(codes, total);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("融资融券标的刷新被中断", ex);
        }
    }

    record Shanghai(Set<String> codes, String date, boolean complete) {
    }

    record ShanghaiPage(Set<String> codes, int total) {
    }
}
