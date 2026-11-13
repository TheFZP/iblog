package com.iblog.module.stock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iblog.module.stock.support.StockCode;
import com.iblog.module.stock.support.StockHttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 融资融券标的清单。
 *
 * <p>行情接口（腾讯 / 东财 push2）不提供「这只票能不能融资融券」的字段，只能单独取
 * 交易所披露的标的清单。这里用东财数据中心 {@code RPT_MARGIN_TARGET}：它返回当前
 * 全部两融标的（约 3900 只），一次请求就能拿全，不需要分页。</p>
 *
 * <p>清单每天最多变一次，缓存 12 小时；刷新失败沿用旧清单，绝不因为上游抖动把
 * 所有票的「融」标签瞬间抹掉。首次加载失败时返回空集合（前端只是不显示标签），
 * 不影响行情本身。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarginTargetService {

    private static final String URL =
            "https://datacenter-web.eastmoney.com/api/data/v1/get"
                    + "?reportName=RPT_MARGIN_TARGET&columns=SECUCODE"
                    + "&pageNumber=1&pageSize=5000&source=WEB&client=WEB";
    private static final String REFERER = "https://data.eastmoney.com/";
    /** 标的清单一天更新一次，缓存 12 小时足够，同时避免每个页面请求都打上游。 */
    private static final long CACHE_TTL_MS = 12 * 60 * 60 * 1000L;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final StockHttpClient http;

    private volatile Set<String> codes = Set.of();
    private volatile long fetchedAt = 0L;

    /** 当前全部两融标的（归一化代码）。过期或为空时尝试刷新，失败沿用旧值。 */
    public Set<String> codes() {
        long now = System.currentTimeMillis();
        if (codes.isEmpty() || now - fetchedAt > CACHE_TTL_MS) {
            synchronized (this) {
                now = System.currentTimeMillis();
                if (codes.isEmpty() || now - fetchedAt > CACHE_TTL_MS) {
                    refresh();
                }
            }
        }
        return codes;
    }

    /** 单只股票是否两融标的。 */
    public boolean isMargin(String rawCode) {
        String code = StockCode.normalize(rawCode);
        return !code.isEmpty() && codes().contains(code);
    }

    private void refresh() {
        try {
            String text = http.getText(URL, REFERER, StandardCharsets.UTF_8);
            Set<String> parsed = parse(text);
            if (!parsed.isEmpty()) {
                codes = parsed;
                fetchedAt = System.currentTimeMillis();
                log.info("融资融券标的刷新成功：{} 只", parsed.size());
            } else {
                log.warn("融资融券标的刷新为空，沿用旧清单（{} 只）", codes.size());
            }
        } catch (RuntimeException ex) {
            log.warn("融资融券标的刷新失败，沿用旧清单（{} 只）：{}", codes.size(), ex.getMessage());
        }
    }

    static Set<String> parse(String text) {
        Set<String> result = new LinkedHashSet<>();
        try {
            JsonNode root = JSON.readTree(text);
            JsonNode data = root.path("result").path("data");
            if (!data.isArray()) {
                return result;
            }
            for (JsonNode node : data) {
                // SECUCODE 形如 600519.SH / 000001.SZ，取点号前的纯代码
                String secucode = node.path("SECUCODE").asText("");
                int dot = secucode.indexOf('.');
                String code = StockCode.normalize(dot > 0 ? secucode.substring(0, dot) : secucode);
                if (!code.isEmpty()) {
                    result.add(code);
                }
            }
        } catch (Exception ex) {
            log.warn("融资融券标的解析失败：{}", ex.getMessage());
        }
        return result;
    }
}