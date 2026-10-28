package com.iblog.module.stock.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iblog.module.stock.support.StockHttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 问财（同花顺 iwencai）选股代理。
 *
 * <p><b>为什么要后端代理而不是前端直连</b>：问财要求请求带 Cookie {@code v}，
 * 而这个 {@code v} 只能在<b>浏览器里</b>由问财自己的 {@code chameleon.js} 生成
 * （实测：纯 Node 沙箱 + 各种 stub 都生成不出来，它依赖真实浏览器指纹）。
 * 所以流程是：前端生成 v → 把它传给后端 → 后端带完整浏览器头转发。
 * 后端直连的好处是绕开浏览器 CORS（问财不返回 CORS 头），且 User-Agent/Referer 可控。</p>
 *
 * <p><b>v 的寿命很短</b>：同一个 v 大约能连用 4~6 次，第 7 次起问财返回
 * {@code status_code=-306} 或直接 403。所以这里把「v 失效」显式区分出来
 * （{@link IwencaiException#rateLimited()}），让前端换新 v 重试，而不是报一个含糊的失败。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IwencaiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String ENDPOINT = "https://www.iwencai.com/unifiedwap/unified-wap/v2/result/get-robot-data";
    private static final String REFERER = "https://www.iwencai.com/unifiedwap/";
    private static final String ORIGIN = "https://www.iwencai.com";
    /** 服务端公开的会话凭证，与桌面版一致（不是用户数据）。 */
    static final String DEFAULT_TOKEN = "0ac9879417859978476843866";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    /** 单页最多 100 行（问财服务端上限）。 */
    private static final int MAX_PER_PAGE = 100;

    private final StockHttpClient http;

    /** 问财选股结果。 */
    public record IwencaiResult(List<String> columns, List<List<String>> rows, String condition, int rowCount) {
    }

    /** 问财调用失败；{@code rateLimited} 表示需要前端换 v 重试。 */
    public static class IwencaiException extends RuntimeException {

        private final boolean rateLimited;

        public IwencaiException(String message, boolean rateLimited) {
            super(message);
            this.rateLimited = rateLimited;
        }

        public boolean rateLimited() {
            return rateLimited;
        }
    }

    /**
     * 执行一次选股查询。
     *
     * @param question 自然语言选股条件
     * @param page     页码（问财服务端会忽略，翻页由调用方本地切片，这里仍照发）
     * @param perPage  每页行数，1~100
     * @param v        浏览器生成的防爬 Cookie
     */
    public IwencaiResult query(String question, int page, int perPage, String v) {
        if (question == null || question.isBlank()) {
            throw new IwencaiException("选股条件不能为空", false);
        }
        if (v == null || v.isBlank()) {
            throw new IwencaiException("缺少问财访问凭证，请刷新页面后重试", true);
        }
        String body = form(question.trim(), Math.max(1, page), Math.min(MAX_PER_PAGE, Math.max(1, perPage)));
        StockHttpClient.Response response = http.postForm(ENDPOINT, body, TIMEOUT,
                "Referer", REFERER,
                "Origin", ORIGIN,
                "Cookie", "v=" + v,
                "User-Agent", UA,
                "Accept", "application/json, text/plain, */*",
                "Accept-Language", "zh-CN,zh;q=0.9");

        if (response.status() == 403) {
            // 403 在问财这里不是「没权限」，几乎总是 v 失效或频率风控
            throw new IwencaiException("问财访问凭证已失效或请求过于频繁，正在重新获取", true);
        }
        if (response.status() != 200) {
            throw new IwencaiException("问财返回 HTTP " + response.status(), false);
        }
        return parse(response.text());
    }

    private static String form(String question, int page, int perPage) {
        StringBuilder sb = new StringBuilder();
        sb.append("question=").append(enc(question));
        sb.append("&source=Ths_iwencai_Xuangu");
        sb.append("&version=2.0");
        sb.append("&secondary_intent=stock");
        sb.append("&rsh=");
        sb.append("&page=").append(page);
        sb.append("&perpage=").append(perPage);
        sb.append("&token=").append(DEFAULT_TOKEN);
        // add_info 必须是合法 JSON 字符串，因此要再编码一次
        sb.append("&add_info=").append(enc("{\"urp\":{\"scene\":1,\"company\":1,\"business\":1},\"contentType\":\"json\"}"));
        return sb.toString();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 解析 {@code data.answer[0].txt[0].content.components[0].data}。 */
    private IwencaiResult parse(String text) {
        JsonNode root;
        try {
            root = MAPPER.readTree(text);
        } catch (Exception e) {
            // 拿到的往往是一整页 HTML（反爬页），没有解析价值
            log.warn("问财返回非 JSON，前 200 字符={}", text.substring(0, Math.min(200, text.length())));
            throw new IwencaiException("问财返回内容无法解析（可能触发了反爬），请稍后重试", true);
        }

        int statusCode = root.path("status_code").asInt(0);
        if (statusCode != 0) {
            String message = root.path("status_msg").asText("");
            if (statusCode == -9138 || statusCode == -306) {
                throw new IwencaiException("问财请求过于频繁（" + statusCode + "），正在重新获取凭证", true);
            }
            throw new IwencaiException("问财返回错误 " + statusCode + (message.isEmpty() ? "" : "：" + message), false);
        }

        JsonNode data = root.path("data").path("answer");
        if (!data.isArray() || data.isEmpty()) {
            return new IwencaiResult(List.of(), List.of(), "", 0);
        }
        JsonNode txt = data.get(0).path("txt");
        if (!txt.isArray() || txt.isEmpty()) {
            return new IwencaiResult(List.of(), List.of(), "", 0);
        }
        JsonNode component = txt.get(0).path("content").path("components");
        if (!component.isArray() || component.isEmpty()) {
            return new IwencaiResult(List.of(), List.of(), "", 0);
        }
        JsonNode payload = component.get(0).path("data");

        // 列定义：label 是给人看的中文列名，datas 里用的也是这个键
        List<String> columns = new ArrayList<>();
        JsonNode cols = payload.path("columns");
        if (cols.isArray()) {
            for (JsonNode col : cols) {
                String label = col.path("label").asText("");
                if (label.isEmpty()) {
                    label = col.path("key").asText("");
                }
                columns.add(label);
            }
        }

        List<List<String>> rows = new ArrayList<>();
        JsonNode datas = payload.path("datas");
        if (datas.isArray()) {
            for (JsonNode row : datas) {
                List<String> cells = new ArrayList<>();
                for (String column : columns) {
                    cells.add(cellText(row.get(column)));
                }
                rows.add(cells);
            }
        }

        JsonNode meta = payload.path("meta").path("extra");
        String condition = meta.path("condition").asText("");
        int rowCount = meta.path("row_count").asInt(rows.size());
        return new IwencaiResult(columns, rows, condition, rowCount);
    }

    private static String cellText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        if (node.isArray()) {
            // 有些列返回 [值, 单位]，拼起来
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : node) {
                if (item.isValueNode()) {
                    if (!sb.isEmpty()) {
                        sb.append(' ');
                    }
                    sb.append(item.asText());
                }
            }
            return sb.toString();
        }
        if (node.isObject()) {
            Map<String, String> parts = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> {
                if (entry.getValue().isValueNode()) {
                    parts.put(entry.getKey(), entry.getValue().asText());
                }
            });
            return parts.isEmpty() ? "" : MAPPER.valueToTree(parts).toString();
        }
        return node.asText("");
    }
}
