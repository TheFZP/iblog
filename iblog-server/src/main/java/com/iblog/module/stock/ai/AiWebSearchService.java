package com.iblog.module.stock.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iblog.module.stock.support.StockHttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * AI 的联网搜索与网页抓取。
 *
 * <p>搜索走东方财富的搜索 JSONP 接口（免费、无需 Key、返回 A 股相关新闻最相关）；
 * 抓取是自己实现的极简正文提取。这两件事本来是桌面版里用 Rust 做的
 * （{@code src-tauri/src/api/web.rs}），迁到服务端后好处是：Key 和抓取逻辑都留在后端，
 * 浏览器不必面对跨域和 SSRF 风险。</p>
 *
 * <p><b>为什么抓取要自己做 SSRF 防护</b>：URL 是模型给的，模型可能给出
 * {@code http://127.0.0.1:8080/...} 这类地址——那是从公网探内网的口子。
 * 所以这里对域名先做 DNS 解析再判断解析结果是否为内网地址，并且不允许跨主机重定向。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiWebSearchService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SEARCH_URL = "https://search-api-web.eastmoney.com/search/jsonp";
    private static final String SEARCH_REFERER = "https://so.eastmoney.com/";
    /** 正文最多保留这么多字符，再多模型也读不完，只会烧 token。 */
    private static final int MAX_BODY_CHARS = 20000;
    /** 抓回来的正文少于这个长度就认为没抓到实质内容。 */
    private static final int MIN_BODY_CHARS = 150;

    private final StockHttpClient http;

    /** 一条搜索结果。 */
    public record SearchHit(String title, String url, String snippet, String date, String source) {
    }

    /** 抓取结果。 */
    public record FetchResult(String url, String title, String content) {
    }

    /**
     * 搜索财经资讯。
     *
     * @param query      关键词（调用方负责先去掉「最新消息」这类泛词）
     * @param maxResults 条数上限，最多 15
     */
    public List<SearchHit> search(String query, int maxResults) {
        int size = Math.max(1, Math.min(15, maxResults));
        String param = buildSearchParam(query, size);
        String url = SEARCH_URL + "?cb=cb&param=" + encode(param);
        String body = http.getText(url, SEARCH_REFERER, StandardCharsets.UTF_8);
        List<SearchHit> hits = parseSearchBody(body);
        return hits.size() > size ? hits.subList(0, size) : hits;
    }

    private static String buildSearchParam(String query, int size) {
        // 手拼 JSON 而不是用 ObjectMapper，是为了保证字段顺序与东财期望的一致（它对顺序敏感）
        String kw = query.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"uid\":\"\",\"keyword\":\"" + kw + "\",\"type\":[\"cmsArticleWebOld\"],"
                + "\"client\":\"web\",\"clientType\":\"web\",\"clientVersion\":\"curr\","
                + "\"param\":{\"cmsArticleWebOld\":{\"searchScope\":\"default\",\"sort\":\"default\","
                + "\"pageIndex\":1,\"pageSize\":" + size + ",\"preTag\":\"<em>\",\"postTag\":\"</em>\"}}}";
    }

    /** 解析 JSONP：去掉 {@code cb(...)} 外壳后按 JSON 读。 */
    private List<SearchHit> parseSearchBody(String body) {
        List<SearchHit> hits = new ArrayList<>();
        if (body == null || body.isEmpty()) {
            return hits;
        }
        int start = body.indexOf('(');
        int end = body.lastIndexOf(')');
        if (start < 0 || end <= start) {
            log.warn("搜索结果不是 JSONP 格式，前 200 字符={}", body.substring(0, Math.min(200, body.length())));
            return hits;
        }
        try {
            JsonNode root = MAPPER.readTree(body.substring(start + 1, end));
            JsonNode list = root.path("result").path("cmsArticleWebOld");
            if (!list.isArray()) {
                return hits;
            }
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode item : list) {
                String title = stripTags(item.path("title").asText(""));
                String link = item.path("url").asText("");
                if (title.isEmpty() || link.isEmpty() || !seen.add(link)) {
                    continue;
                }
                hits.add(new SearchHit(title, link,
                        stripTags(item.path("content").asText("")),
                        item.path("date").asText(""),
                        item.path("mediaName").asText("")));
            }
        } catch (Exception e) {
            log.warn("解析搜索结果失败 err={}", e.toString());
        }
        return hits;
    }

    /**
     * 抓取网页正文。
     *
     * <p>顺序：JSON-LD 的 {@code articleBody} → {@code <article>/<main>} → 去掉脚本样式后的整页。
     * 不追求完美，只要模型能读到「谁在什么时候说了什么」就够。</p>
     */
    public FetchResult fetch(String rawUrl) {
        URI uri = safeUri(rawUrl);
        String html = fetchText(uri);
        String title = extractTag(html, "title");
        String content = extractContent(html);
        if (content.length() < MIN_BODY_CHARS) {
            throw new AiLlmClient.AiException("该页面正文抓不到（可能需要登录或由脚本动态渲染）");
        }
        return new FetchResult(uri.toString(), stripTags(title), content);
    }

    private String fetchText(URI uri) {
        // 复用行情客户端的 GET（它会跟随重定向）；跨主机重定向在 DNS 校验里已被拦下
        Charset charset = StandardCharsets.UTF_8;
        String raw = http.getText(uri.toString(), null, charset);
        String lower = raw.toLowerCase();
        if (lower.contains("charset=gbk") || lower.contains("charset=gb2312")
                || lower.contains("charset=gb18030")) {
            // 已经用 UTF-8 解错过一次，重新按 GB18030 取回
            raw = new String(http.getBytes(uri.toString(), null), StockHttpClient.GBK);
        }
        return raw;
    }

    /** 校验协议、主机与解析结果，防止模型给出内网地址。 */
    private static URI safeUri(String rawUrl) {
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (Exception e) {
            throw new AiLlmClient.AiException("链接格式不正确：" + rawUrl);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new AiLlmClient.AiException("只支持抓取 http/https 链接");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new AiLlmClient.AiException("链接缺少域名：" + rawUrl);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            throw new AiLlmClient.AiException("域名解析失败：" + host);
        }
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                    || address.isMulticastAddress()) {
                // 这条拦截是安全底线，不是可选项：模型可能被网页内容诱导去读内网
                throw new AiLlmClient.AiException("出于安全考虑，不抓取内网地址");
            }
        }
        return uri;
    }

    /** 从整页 HTML 里抠出正文。 */
    private static String extractContent(String html) {
        String body = jsonLdArticleBody(html);
        if (body != null && body.length() >= MIN_BODY_CHARS) {
            return trim(body);
        }
        String container = firstBlock(html, "article");
        if (container == null) {
            container = firstBlock(html, "main");
        }
        String text = stripTags(container == null ? html : container);
        return trim(text);
    }

    /** 找 JSON-LD 里的 articleBody（很多新闻站都有，质量最高）。 */
    private static String jsonLdArticleBody(String html) {
        int idx = html.indexOf("\"articleBody\"");
        if (idx < 0) {
            return null;
        }
        int colon = html.indexOf(':', idx);
        if (colon < 0) {
            return null;
        }
        return readJsonString(html, colon + 1);
    }

    /** 读一个 JSON 字符串字面量（从 pos 开始跳过空白找首个引号）。 */
    private static String readJsonString(String text, int pos) {
        int i = pos;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        if (i >= text.length() || text.charAt(i) != '"') {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        i++;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'u' -> {
                        if (i + 5 < text.length()) {
                            try {
                                sb.append((char) Integer.parseInt(text.substring(i + 2, i + 6), 16));
                            } catch (NumberFormatException ignored) {
                                // 转义不合法就原样跳过，宁可少一个字也别中断
                            }
                            i += 4;
                        }
                    }
                    default -> sb.append(next);
                }
                i += 2;
                continue;
            }
            if (c == '"') {
                break;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String firstBlock(String html, String tag) {
        int start = html.toLowerCase().indexOf("<" + tag);
        if (start < 0) {
            return null;
        }
        int end = html.toLowerCase().indexOf("</" + tag + ">", start);
        return end > start ? html.substring(start, end) : html.substring(start);
    }

    private static String extractTag(String html, String tag) {
        return firstBlock(html, tag);
    }

    /**
     * 去标签 + 合并空白。
     *
     * <p>先删 script/style/noscript 再删标签：否则内联 JS 里的比较符号
     * （{@code if (a < b)}）会被当成标签开头，把后半篇正文一起吃掉。</p>
     */
    static String stripTags(String html) {
        if (html == null) {
            return "";
        }
        String text = html.replaceAll("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>", " ");
        text = text.replaceAll("(?s)<!--.*?-->", " ");
        text = text.replaceAll("(?s)<[^>]+>", " ");
        text = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        return text.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static String trim(String text) {
        if (text == null) {
            return "";
        }
        String clean = text.trim();
        return clean.length() <= MAX_BODY_CHARS ? clean : clean.substring(0, MAX_BODY_CHARS) + "\n…（内容过长已截断）";
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
