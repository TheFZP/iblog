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
import java.text.Normalizer;
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

    /** 个股搜索结果：资讯 + 公司公告。 */
    public record StockSearch(List<SearchHit> news, List<SearchHit> notices) {
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
        return parseHits(body, "cmsArticleWebOld", size);
    }

    /**
     * 个股资讯 + 公司公告一次搜完。
     *
     * <p>为什么要带公告：新闻情绪只搜资讯时，公司自己发的「股票交易异常波动公告」
     * 不会进入打分，除非媒体恰好写了稿子。东财搜索的 {@code notice} 类型能直接拿公告原文。</p>
     *
     * <p>公告是全文检索：搜「宁德时代」会返回睿能科技公告里提到宁德时代的条目，
     * 所以必须用 {@code securityShortName} 过滤成目标公司自己的公告。</p>
     *
     * @param stockName   股票名称（过滤公告归属）
     * @param stockCode   股票代码（拼公告详情链接）
     * @param newsSize    资讯条数
     * @param noticeSize  公告条数（请求上游的条数，调用方再按需截断）
     */
    public StockSearch searchStockWithNotices(String stockName, String stockCode,
                                              int newsSize, int noticeSize) {
        int news = Math.max(1, Math.min(15, newsSize));
        int notices = Math.max(1, Math.min(15, noticeSize));
        String param = buildStockSearchParam(stockName, news, notices);
        String url = SEARCH_URL + "?cb=cb&param=" + encode(param);
        String body;
        try {
            body = http.getText(url, SEARCH_REFERER, StandardCharsets.UTF_8);
        } catch (RuntimeException ex) {
            // 公告类型若被上游临时拒绝，不能让整只票的新闻因子消失：退回纯资讯搜索
            log.warn("个股资讯+公告搜索失败，回退纯资讯 code={} err={}", stockCode, ex.getMessage());
            return new StockSearch(search(stockName + " " + stockCode, news), List.of());
        }
        return new StockSearch(
                parseHits(body, "cmsArticleWebOld", news),
                parseNotices(body, stockName, stockCode, notices));
    }

    private static String buildSearchParam(String query, int size) {
        // 手拼 JSON 而不是用 ObjectMapper，是为了保证字段顺序与东财期望的一致（它对顺序敏感）
        String kw = escape(query);
        return "{\"uid\":\"\",\"keyword\":\"" + kw + "\",\"type\":[\"cmsArticleWebOld\"],"
                + "\"client\":\"web\",\"clientType\":\"web\",\"clientVersion\":\"curr\","
                + "\"param\":{\"cmsArticleWebOld\":{\"searchScope\":\"default\",\"sort\":\"default\","
                + "\"pageIndex\":1,\"pageSize\":" + size + ",\"preTag\":\"<em>\",\"postTag\":\"</em>\"}}}";
    }

    private static String buildStockSearchParam(String query, int newsSize, int noticeSize) {
        String kw = escape(query);
        return "{\"uid\":\"\",\"keyword\":\"" + kw + "\",\"type\":[\"cmsArticleWebOld\",\"notice\"],"
                + "\"client\":\"web\",\"clientType\":\"web\",\"clientVersion\":\"curr\","
                + "\"param\":{"
                + "\"cmsArticleWebOld\":{\"searchScope\":\"default\",\"sort\":\"default\","
                + "\"pageIndex\":1,\"pageSize\":" + newsSize + ",\"preTag\":\"<em>\",\"postTag\":\"</em>\"},"
                + "\"notice\":{\"searchScope\":\"default\",\"sort\":\"default\","
                + "\"pageIndex\":1,\"pageSize\":" + noticeSize + ",\"preTag\":\"<em>\",\"postTag\":\"</em>\"}"
                + "}}";
    }

    private static String escape(String query) {
        return query == null ? "" : query.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 解析资讯列表。 */
    private List<SearchHit> parseHits(String body, String type, int max) {
        List<SearchHit> hits = new ArrayList<>();
        JsonNode root = parseJsonp(body);
        if (root == null) {
            return hits;
        }
        JsonNode list = root.path("result").path(type);
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
            if (hits.size() >= max) {
                break;
            }
        }
        return hits;
    }

    /**
     * 解析公告列表，只保留目标公司自己的公告。
     *
     * <p>公告条目没有股票代码，只有 {@code securityShortName}；先做 NFKC + 去空白归一化，
     * 避免全角字符/空格造成同名误判。标题里的 {@code <em>} 由 {@link #stripTags} 去掉。</p>
     */
    private List<SearchHit> parseNotices(String body, String stockName, String stockCode, int max) {
        JsonNode root = parseJsonp(body);
        if (root == null) {
            return new ArrayList<>();
        }
        return parseNoticeItems(root.path("result").path("notice"), stockName, stockCode, max);
    }

    /** 从公告列表里挑出目标公司自己的公告，供单元测试直接调用。 */
    static List<SearchHit> parseNoticeItems(JsonNode list, String stockName, String stockCode, int max) {
        List<SearchHit> hits = new ArrayList<>();
        if (list == null || !list.isArray()) {
            return hits;
        }
        String name = normalizeName(stockName);
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : list) {
            // <em> 高亮标签被 stripTags 换成空格后，会出现「金马 :公告」这种难看的间隔
            String title = stripTags(item.path("title").asText("")).replaceAll("\\s+([:：])", "$1");
            String noticeCode = item.path("code").asText("");
            String security = normalizeName(item.path("securityShortName").asText(""));
            if (title.isEmpty() || noticeCode.isEmpty() || !belongsTo(title, security, name)) {
                continue;
            }
            if (!seen.add(title.replaceAll("[\\s\\u3000]", ""))) {
                continue;
            }
            String link = "https://data.eastmoney.com/notices/detail/"
                    + stockCode + "/" + noticeCode + ".html";
            hits.add(new SearchHit(title, link,
                    stripTags(item.path("content").asText("")),
                    item.path("date").asText(""),
                    "交易所公告"));
            if (hits.size() >= max) {
                break;
            }
        }
        return hits;
    }

    /** 公告归属判断：优先比对 securityShortName，缺失时退回标题前缀。 */
    private static boolean belongsTo(String title, String security, String name) {
        if (name.isEmpty()) {
            return false;
        }
        if (!security.isEmpty()) {
            return security.equals(name);
        }
        return normalizeName(title).startsWith(name + ":");
    }

    /** NFKC（全角转半角）+ 去掉所有空白，用于公司名比较。 */
    private static String normalizeName(String raw) {
        if (raw == null) {
            return "";
        }
        return Normalizer.normalize(raw, Normalizer.Form.NFKC).replaceAll("[\\s\\u3000]+", "");
    }

    /** 去掉 JSONP 外壳后按 JSON 读；格式异常返回 null。 */
    private JsonNode parseJsonp(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        int start = body.indexOf('(');
        int end = body.lastIndexOf(')');
        if (start < 0 || end <= start) {
            log.warn("搜索结果不是 JSONP 格式，前 200 字符={}", body.substring(0, Math.min(200, body.length())));
            return null;
        }
        try {
            return MAPPER.readTree(body.substring(start + 1, end));
        } catch (Exception e) {
            log.warn("解析搜索结果失败 err={}", e.toString());
            return null;
        }
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
