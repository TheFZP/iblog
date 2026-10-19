package com.iblog.module.stock.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iblog.module.stock.support.StockHttpClient.StockDataException;

import java.util.ArrayList;
import java.util.List;

/**
 * 东方财富 fflow 接口的响应解析（纯函数，不碰网络，便于单测）。
 *
 * <p>上游两种返回形态都要支持：</p>
 * <ul>
 *   <li><b>JSONP</b>：{@code jQuery({json});} —— 桌面版固定带 {@code &cb=jQuery} 就是这个原因</li>
 *   <li><b>裸 JSON</b>：不带 {@code cb} 参数时返回</li>
 * </ul>
 *
 * <p>业务错误以 <b>HTTP 200 + rc≠0</b> 返回（实测不存在/无数据的代码返回 {@code rc=100, data=null}），
 * 所以不能只看 HTTP 状态码，必须检查 {@code rc}。</p>
 */
public final class EmFflowParser {

    private EmFflowParser() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** JSONP 前缀，如 jQuery( / jQuery12345_6789( —— 用正则取最后一个 '(' 前的部分更稳妥 */
    private static final java.util.regex.Pattern JSONP_WRAP =
            java.util.regex.Pattern.compile("^[\\w.$]*\\((.*)\\)[;\\s]*$", java.util.regex.Pattern.DOTALL);

    /**
     * 解析响应文本，返回 {@code data.klines} 数组的字符串列表（原样，不拆分字段）。
     *
     * @param text 响应正文
     * @return klines 列表；上游无数据时返回空列表（rc=100 / data=null 属于「无数据」而非「出错」）
     * @throws StockDataException 响应不是合法 JSON、或 rc 表示真正的错误
     */
    public static List<String> parseKlines(String text) {
        JsonNode root = parseRoot(text);
        long rc = root.path("rc").asLong(-1);
        JsonNode data = root.path("data");
        // rc=100 + data=null：代码不存在或上游无该股资金流数据，按「无数据」处理
        if (rc != 0 || data.isMissingNode() || data.isNull()) {
            return new ArrayList<>();
        }
        JsonNode klines = data.path("klines");
        List<String> out = new ArrayList<>();
        if (!klines.isArray()) {
            return out;
        }
        for (JsonNode n : klines) {
            if (n.isTextual() && !n.asText().isBlank()) {
                out.add(n.asText());
            }
        }
        return out;
    }

    /**
     * 解析响应文本为根节点（剥掉 JSONP 外壳）。
     */
    public static JsonNode parseRoot(String text) {
        if (text == null || text.isBlank()) {
            throw new StockDataException("资金流向接口返回空响应");
        }
        String body = text.trim();
        java.util.regex.Matcher m = JSONP_WRAP.matcher(body);
        if (m.matches()) {
            body = m.group(1);
        } else if (body.startsWith("jQuery")) {
            // 形态异常但确实是 JSONP，给出可定位的错误而不是让 Jackson 抛一堆无关信息
            throw new StockDataException("资金流向接口 JSONP 格式异常");
        }
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            throw new StockDataException("资金流向数据解析失败：" + e.getMessage());
        }
    }

    /**
     * 按逗号拆分一行 klines。
     */
    public static String[] splitLine(String line) {
        return line.split(",", -1);
    }
}
