package com.iblog.module.stock.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iblog.module.paper.support.Indicators;
import com.iblog.module.stock.model.HotStockItem;
import com.iblog.module.stock.model.IndustryData;
import com.iblog.module.stock.model.IntradayData;
import com.iblog.module.stock.model.IntradayItem;
import com.iblog.module.stock.model.KlineItem;
import com.iblog.module.stock.model.MarketIndex;
import com.iblog.module.stock.model.MoneyFlow;
import com.iblog.module.stock.model.MoneyFlowHistoryItem;
import com.iblog.module.stock.model.SearchResult;
import com.iblog.module.stock.model.StockQuote;
import com.iblog.module.stock.service.EastmoneyService;
import com.iblog.module.stock.service.HotListService;
import com.iblog.module.stock.service.IndustryService;
import com.iblog.module.stock.service.StockSettingService;
import com.iblog.module.stock.service.TencentService;
import com.iblog.module.stock.support.StockHttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 工具的<b>服务端实现</b>。
 *
 * <p>桌面版这些工具都跑在浏览器/WebView 里（通过 Tauri 的 invoke 打本地 Rust），
 * 迁到官网后如果照搬，浏览器就得直连腾讯/东财/问财——跨域、CORS、防爬头全在用户浏览器里
 * 裸奔，而且问财还要页面里塞第三方脚本。放服务端只多一跳内网，这些问题一次性消失。</p>
 *
 * <p>每个工具都<b>自己吞掉异常</b>并返回 {@code [错误] ...} 文本：工具出错不该中断整轮对话，
 * 模型看到错误文本会自己降级或换工具，比抛异常让用户看到「系统繁忙」有用得多。
 * 这一点和桌面版的行为一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiToolExecutors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** K 线工具只回最近这么多根，再多就是把 token 扔进模型嘴里。 */
    private static final int KLINE_BARS = 60;
    /** 分时最多回这么多点。 */
    private static final int INTRADAY_POINTS = 60;
    /** 热榜最多回这么多只。 */
    private static final int HOT_LIMIT = 30;
    /** 批量行情上限。 */
    private static final int BATCH_LIMIT = 50;
    /** 选股结果最多回这么多行（压缩后）。 */
    private static final int SCREENER_ROWS = 100;

    private final TencentService tencentService;
    private final HotListService hotListService;
    private final EastmoneyService eastmoneyService;
    private final IndustryService industryService;
    private final AiWebSearchService webSearchService;
    private final IwencaiClient iwencaiClient;
    private final StockHttpClient stockHttpClient;
    private final StockSettingService settingService;

    /**
     * 工具执行上下文。
     *
     * @param iwencaiV 浏览器生成的问财凭证；为空时问财工具会提示用户刷新页面
     */
    public record Context(String iwencaiV) {
    }

    /**
     * 执行一个工具调用，返回给模型的文本。
     *
     * @param name      工具名
     * @param arguments JSON 参数
     * @param context   上下文
     */
    public String execute(String name, String arguments, Context context) {
        JsonNode args = parseArgs(arguments);
        try {
            return switch (name) {
                case "get_stock_quote" -> json(tencentService.fetchQuote(text(args, "code")));
                case "get_stock_quotes_batch" -> quotesBatch(args);
                case "get_stock_kline" -> kline(args);
                case "get_stock_money_flow" -> json(eastmoneyService.fetchMoneyFlow(text(args, "code")));
                case "get_stock_money_flow_history" -> moneyFlowHistory(args);
                case "get_stock_industry" -> json(industryService.fetchIndustry(text(args, "code")));
                case "get_market_indices" -> indices();
                case "get_stock_intraday" -> intraday(args);
                case "get_hot_list" -> hotList();
                case "search_stocks" -> search(args);
                case "read_user_profile" -> readProfile();
                case "save_user_profile" -> saveProfile(args);
                case "get_fx_rate" -> fxRate();
                case "web_search" -> webSearch(args);
                case "web_fetch" -> webFetch(args);
                case "stock_screener" -> screener(args, context);
                case "render_stock_picks" -> picks(args);
                default -> "[错误] 未知工具：" + name;
            };
        } catch (AiLlmClient.AiException e) {
            return "[错误] " + e.getMessage();
        } catch (IwencaiClient.IwencaiException e) {
            return "[错误] " + e.getMessage();
        } catch (StockHttpClient.StockDataException e) {
            return "[错误] 行情数据获取失败：" + e.getMessage();
        } catch (Exception e) {
            log.warn("AI 工具执行异常 name={} err={}", name, e.toString());
            return "[错误] 工具执行失败：" + e.getMessage();
        }
    }

    // ───────────────────────── 行情 ─────────────────────────

    private String quotesBatch(JsonNode args) {
        JsonNode codes = args.path("codes");
        if (!codes.isArray() || codes.isEmpty()) {
            return "[错误] 未提供股票代码";
        }
        List<String> list = new ArrayList<>();
        for (JsonNode node : codes) {
            String code = node.asText("").trim();
            if (!code.isEmpty()) {
                list.add(code);
            }
            if (list.size() >= BATCH_LIMIT) {
                break;
            }
        }
        return json(tencentService.fetchQuotesBatch(list));
    }

    /**
     * K 线 + 均线。
     *
     * <p>为什么只回 60 根：模型算指标要看趋势，但把 480 根全塞进去没有增量信息，
     * 只会挤占上下文（桌面版同样取 60 根）。</p>
     */
    private String kline(JsonNode args) {
        String code = text(args, "code");
        String period = text(args, "period");
        if (period.isEmpty()) {
            period = "day";
        }
        List<KlineItem> bars = tencentService.fetchKline(code, period);
        if (bars == null || bars.isEmpty()) {
            return "[错误] 未获取到 K 线数据：" + code;
        }
        double[] closes = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            closes[i] = bars.get(i).getClose();
        }
        double last = closes[closes.length - 1];
        double ma5 = Indicators.sma(closes, 5);
        double ma10 = Indicators.sma(closes, 10);
        double ma20 = Indicators.sma(closes, 20);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("code", code);
        result.put("period", period);
        result.put("close", last);
        ObjectNode ma = result.putObject("ma");
        putNum(ma, "MA5", ma5);
        putNum(ma, "MA10", ma10);
        putNum(ma, "MA20", ma20);
        if (Double.isFinite(ma5) && ma5 != 0) {
            result.put("vsMA5Pct", round2((last - ma5) / ma5 * 100));
        }
        Indicators.Macd macd = Indicators.macd(closes);
        ObjectNode macdNode = result.putObject("macd");
        putNum(macdNode, "DIF", macd.dif());
        putNum(macdNode, "DEA", macd.dea());
        putNum(macdNode, "hist", macd.hist());
        putNum(result, "rsi14", Indicators.rsi(closes, 14));

        int from = Math.max(0, bars.size() - KLINE_BARS);
        ArrayNode barArray = result.putArray("bars");
        for (int i = from; i < bars.size(); i++) {
            KlineItem bar = bars.get(i);
            ObjectNode item = barArray.addObject();
            item.put("date", bar.getDate());
            item.put("open", bar.getOpen());
            item.put("close", bar.getClose());
            item.put("high", bar.getHigh());
            item.put("low", bar.getLow());
            item.put("volume", bar.getVolume());
        }
        return result.toString();
    }

    private String moneyFlowHistory(JsonNode args) {
        int limit = args.path("limit").asInt(30);
        limit = Math.max(5, Math.min(60, limit));
        List<MoneyFlowHistoryItem> items = eastmoneyService.fetchMoneyFlowHistory(text(args, "code"), limit);
        return json(items);
    }

    private String indices() {
        List<String> codes = List.of("sh000001", "sz399001", "sz399006", "sh000300", "sh000688", "sh000905");
        List<MarketIndex> list = new ArrayList<>();
        for (String code : codes) {
            try {
                list.add(tencentService.fetchIndex(code));
            } catch (Exception e) {
                // 单个指数失败不影响其它，指数是最常见的「先看一眼」数据
                log.warn("AI 工具取指数失败 code={} err={}", code, e.toString());
            }
        }
        return json(list);
    }

    private String intraday(JsonNode args) {
        IntradayData data = tencentService.fetchIntraday(text(args, "code"));
        if (data == null || data.getItems() == null) {
            return json(data);
        }
        List<IntradayItem> items = data.getItems();
        if (items.size() > INTRADAY_POINTS) {
            // 分时动辄 240 点，模型只需要看收盘前一段和整体形状，抽稀成 60 点
            List<IntradayItem> sampled = new ArrayList<>();
            int step = (int) Math.ceil(items.size() / (double) INTRADAY_POINTS);
            for (int i = 0; i < items.size(); i += step) {
                sampled.add(items.get(i));
            }
            if (sampled.size() < 2 || !sampled.get(sampled.size() - 1).equals(items.get(items.size() - 1))) {
                sampled.add(items.get(items.size() - 1));
            }
            data.setItems(sampled);
        }
        return json(data);
    }

    private String hotList() {
        List<HotStockItem> list = hotListService.fetchHotList();
        if (list == null) {
            return "[]";
        }
        return json(list.size() > HOT_LIMIT ? list.subList(0, HOT_LIMIT) : list);
    }

    private String search(JsonNode args) {
        List<SearchResult> list = tencentService.search(text(args, "keyword"));
        if (list == null) {
            return "[]";
        }
        List<SearchResult> limited = list.size() > 10 ? list.subList(0, 10) : list;
        return json(limited);
    }

    // ───────────────────────── 用户上下文 ─────────────────────────

    /** 画像存在 stock_setting 里（官网只有一个博主用户，没必要单独建表）。 */
    private String readProfile() {
        String profile = settingService.getPlain("ai_user_profile");
        if (profile == null || profile.isBlank()) {
            return "{\"profile\":null,\"hint\":\"用户尚未填写投资画像\"}";
        }
        return profile;
    }

    private String saveProfile(JsonNode args) {
        JsonNode profile = args.path("profile");
        if (profile.isMissingNode() || profile.isNull()) {
            return "[错误] 缺少 profile 参数";
        }
        settingService.put("ai_user_profile", profile.toString(), false, "AI 用户投资画像");
        return "{\"saved\":true}";
    }

    /**
     * 港元兑人民币汇率。
     *
     * <p>用 frankfurter（欧洲央行数据，免 Key）。失败不抛异常——汇率取不到只影响港股换算的精度，
     * 让模型回退到「约 0.9」比整轮对话报错好。</p>
     */
    private String fxRate() {
        try {
            String body = stockHttpClient.getText("https://api.frankfurter.app/latest?from=HKD&to=CNY",
                    null, java.nio.charset.StandardCharsets.UTF_8);
            JsonNode root = MAPPER.readTree(body);
            double rate = root.path("rates").path("CNY").asDouble(0);
            if (rate > 0) {
                ObjectNode result = MAPPER.createObjectNode();
                result.put("from", "HKD");
                result.put("to", "CNY");
                result.put("rate", rate);
                return result.toString();
            }
        } catch (Exception e) {
            log.warn("取汇率失败 err={}", e.toString());
        }
        return "[错误] 汇率服务不可用，港股金额请按约 0.9 估算并说明是估算";
    }

    // ───────────────────────── 联网 ─────────────────────────

    private String webSearch(JsonNode args) {
        String query = text(args, "query");
        if (query.isEmpty()) {
            return "[错误] 缺少 query 参数";
        }
        int max = args.path("max_results").asInt(10);
        max = Math.max(1, Math.min(15, max));
        List<AiWebSearchService.SearchHit> hits = webSearchService.search(query, max);
        if (hits.isEmpty()) {
            return "{\"hits\":[],\"hint\":\"没有搜到结果，可以换个关键词再试\"}";
        }
        return json(hits);
    }

    private String webFetch(JsonNode args) {
        String url = text(args, "url");
        if (url.isEmpty()) {
            return "[错误] 缺少 url 参数";
        }
        AiWebSearchService.FetchResult result = webSearchService.fetch(url);
        ObjectNode node = MAPPER.createObjectNode();
        node.put("url", result.url());
        node.put("title", result.title());
        node.put("content", result.content());
        return node.toString();
    }

    // ───────────────────────── 问财选股 ─────────────────────────

    /**
     * 问财自然语言选股。
     *
     * <p>凭证来自前端（后端生成不了 v，见 {@link IwencaiClient} 的说明）。
     * 结果要做列裁剪：问财一次能回几十列，模型读不完也用不上。</p>
     */
    private String screener(JsonNode args, Context context) {
        String question = text(args, "question");
        if (question.isEmpty()) {
            return "[错误] 缺少选股条件";
        }
        String v = context == null ? null : context.iwencaiV();
        if (v == null || v.isBlank()) {
            return "[错误] 当前页面还没拿到问财凭证，请刷新页面后重试（问财需要浏览器生成凭证）";
        }
        IwencaiClient.IwencaiResult result = iwencaiClient.query(question, 1, 50, v);
        if (result.rows().isEmpty()) {
            return "{\"condition\":\"" + escape(result.condition()) + "\",\"rows\":[],\"hint\":\"问财没有符合条件的股票\"}";
        }
        List<String> columns = pickColumns(result.columns());
        List<Integer> indexes = new ArrayList<>();
        for (String column : columns) {
            indexes.add(result.columns().indexOf(column));
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put("condition", result.condition());
        node.put("rowCount", result.rowCount());
        node.put("columns", MAPPER.valueToTree(columns));
        ArrayNode rows = node.putArray("rows");
        int limit = Math.min(result.rows().size(), SCREENER_ROWS);
        for (int i = 0; i < limit; i++) {
            List<String> row = result.rows().get(i);
            ArrayNode cells = rows.addArray();
            for (int index : indexes) {
                cells.add(index >= 0 && index < row.size() ? formatCell(row.get(index)) : "");
            }
        }
        return node.toString();
    }

    /** 列白名单：代码/名称/现价必留，其余按关键词挑，最多 6 列。 */
    private static final List<String> ALWAYS_COL = List.of("股票代码", "股票简称", "代码", "名称", "现价", "最新价");
    private static final List<String> KEYWORD_COL = List.of(
            "市值", "市盈率", "市净率", "净资产收益率", "roe", "净利润", "营收", "涨幅", "涨跌幅",
            "换手", "量比", "股息", "负债", "毛利", "自由流通", "均线", "成交额", "每股收益", "每股净资产");

    private static List<String> pickColumns(List<String> all) {
        List<String> picked = new ArrayList<>();
        for (String column : all) {
            if (ALWAYS_COL.contains(column)) {
                picked.add(column);
            }
        }
        for (String column : all) {
            if (picked.size() >= 6) {
                break;
            }
            if (picked.contains(column)) {
                continue;
            }
            for (String keyword : KEYWORD_COL) {
                if (column.toLowerCase().contains(keyword.toLowerCase())) {
                    picked.add(column);
                    break;
                }
            }
        }
        // 一列都没匹配上时至少给前三列，否则模型看到只有代码没有数据
        if (picked.isEmpty()) {
            picked.addAll(all.subList(0, Math.min(3, all.size())));
        }
        return picked.size() > 6 ? picked.subList(0, 6) : picked;
    }

    /** 金额类数字按万/亿压缩，长数字会白烧 token 且模型更容易算错。 */
    private static String formatCell(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String value = raw.replace(",", "").trim();
        try {
            double num = Double.parseDouble(value);
            double abs = Math.abs(num);
            if (abs >= 1e8) {
                return round2(num / 1e8) + "亿";
            }
            if (abs >= 1e4) {
                return round2(num / 1e4) + "万";
            }
            if (value.contains(".") && value.length() > 8) {
                return round2(num) + "";
            }
            return value;
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    /**
     * 选股卡片。
     *
     * <p>这个工具<b>不给模型看数据</b>：返回一个标记，由控制器截住转成前端卡片，
     * 模型只收到「已展示」。否则模型会把同一批股票在正文里再念一遍，浪费输出。</p>
     */
    private String picks(JsonNode args) {
        JsonNode list = args.path("picks");
        if (!list.isArray() || list.isEmpty()) {
            return "[错误] picks 必须是非空数组";
        }
        ArrayNode cleaned = MAPPER.createArrayNode();
        for (JsonNode item : list) {
            if (cleaned.size() >= 20) {
                break;
            }
            ObjectNode pick = cleaned.addObject();
            pick.put("code", normalizeCode(item.path("code").asText("")));
            pick.put("name", item.path("name").asText(""));
            if (item.hasNonNull("price")) {
                pick.put("price", item.path("price").asDouble(0));
            }
            if (item.hasNonNull("changePct")) {
                pick.put("changePct", item.path("changePct").asDouble(0));
            }
            pick.put("reason", item.path("reason").asText(""));
        }
        return "__STOCK_PICKS__" + cleaned;
    }

    private static String normalizeCode(String code) {
        return code.replace(".SH", "").replace(".SZ", "").replace(".BJ", "").replace(".HK", "").trim();
    }

    // ───────────────────────── 工具方法 ─────────────────────────

    private static JsonNode parseArgs(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode node = MAPPER.readTree(arguments);
            return node.isObject() ? node : MAPPER.createObjectNode();
        } catch (Exception e) {
            // 模型偶尔给出截断的 JSON，当成空参数比整轮失败好
            log.warn("工具参数不是合法 JSON，按空参数处理 arg={}", arguments);
            return MAPPER.createObjectNode();
        }
    }

    private static String text(JsonNode args, String field) {
        JsonNode node = args.path(field);
        return node.isMissingNode() || node.isNull() ? "" : node.asText("").trim();
    }

    private static void putNum(ObjectNode node, String field, double value) {
        if (Double.isFinite(value)) {
            node.put(field, round2(value));
        }
    }

    private static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"序列化失败\"}";
        }
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("\"", "\\\"");
    }
}
