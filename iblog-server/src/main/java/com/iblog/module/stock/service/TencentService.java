package com.iblog.module.stock.service;

import com.iblog.module.stock.model.IntradayData;
import com.iblog.module.stock.model.IntradayItem;
import com.iblog.module.stock.model.KlineItem;
import com.iblog.module.stock.model.MarketIndex;
import com.iblog.module.stock.model.OrderBook;
import com.iblog.module.stock.model.SearchResult;
import com.iblog.module.stock.model.StockQuote;
import com.iblog.module.stock.support.StockCode;
import com.iblog.module.stock.support.StockHttpClient;
import com.iblog.module.stock.support.StockHttpClient.StockDataException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 腾讯财经数据源。
 *
 * <p><b>口径唯一真源</b>：本类与桌面版 {@code src-tauri/src/api/tencent.rs} 一一对应，
 * 修改字段下标前务必核对 Rust 端注释，否则会出现「涨了显示跌」这类静默错误。</p>
 *
 * <h3>关键 quirk</h3>
 * <ul>
 *   <li>响应为 <b>GBK</b> 编码且无 charset 头，必须按 GB18030 解码</li>
 *   <li>行情字段以 {@code ~} 分隔，<b>0-based</b> 下标：
 *       [1]名称 [3]现价 [4]昨收 [5]今开 [6]成交量(手) [31]涨跌额 [32]涨跌幅
 *       [33]最高 [34]最低 [37]成交额(万) [38]换手率 [39]市盈率 <b>[43]振幅</b> [46]市净率</li>
 *   <li>港股字段布局不同：[43] 同为振幅、[46] 是英文名、<b>[59] 才是换手率</b>；
 *       量额需归一化（成交量 股→手 ÷100，成交额 元→万元 ÷10000）</li>
 *   <li>日/周/月 K 走 fqkline（前复权 120 根，仅 6 字段），分钟 K 走 mkline（320 根，港股不支持）</li>
 *   <li>分时接口累计成交量：A 股/北交所为「手」，港股为「股」</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TencentService {

    private final StockHttpClient http;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 单批最多 50 只，避免 URL 过长被截断 */
    private static final int BATCH_SIZE = 50;

    /** 匹配 v_sh600519="..." 形式的行情行 */
    private static final Pattern QUOTE_LINE = Pattern.compile("v_([a-z0-9]+)=\"([^\"]*)\"");

    // ==================== 实时行情 ====================

    /**
     * 获取个股实时行情。
     *
     * <p>港股走分时接口（qt.gtimg.cn 的港股数据约延迟 15 分钟且价格会跳），
     * 与分时图同源，保证「图上最后一点 == 现价」。</p>
     */
    public StockQuote fetchQuote(String rawCode) {
        String code = StockCode.normalize(rawCode);
        return StockCode.isHk(code) ? fetchHkQuote(code) : fetchAQuote(code);
    }

    private StockQuote fetchAQuote(String code) {
        String tCode = StockCode.toTencent(code);
        String url = "https://qt.gtimg.cn/q=" + tCode;
        String text = http.getText(url, StockHttpClient.REFERER_QT, StockHttpClient.GBK);

        String[] fields = text.split("~", -1);
        if (fields.length < 47) {
            throw new StockDataException("行情接口返回字段过少（" + fields.length + "），代码可能不存在：" + code);
        }
        return parseAQuoteFields(code, fields);
    }

    /**
     * 港股：走分时接口的 qt 字段（实时），字段布局与 qt.gtimg.cn 一致但换手率下标不同。
     */
    private StockQuote fetchHkQuote(String code) {
        String tCode = StockCode.toTencent(code);
        String url = "https://web.ifzq.gtimg.cn/appstock/app/minute/query?code=" + tCode;
        JsonNode root = getJson(url, StockHttpClient.REFERER_IFZQ);

        JsonNode arr = root.path("data").path(tCode).path("qt").path(tCode);
        if (!arr.isArray() || arr.size() < 60) {
            throw new StockDataException("未找到港股行情字段：" + code);
        }
        String[] fields = new String[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            fields[i] = arr.get(i).asText("");
        }

        StockQuote q = new StockQuote();
        q.setCode(code);
        q.setName(at(fields, 1));
        q.setPrice(num(at(fields, 3)));
        q.setPrevClose(num(at(fields, 4)));
        q.setOpen(num(at(fields, 5)));
        q.setChange(num(at(fields, 31)));
        q.setChangePct(num(at(fields, 32)));
        q.setHigh(num(at(fields, 33)));
        q.setLow(num(at(fields, 34)));
        // 港股单位归一化：成交量 股→手（/100），成交额 元→万元（/10000）
        q.setVolume(num(at(fields, 6)) / 100.0);
        q.setTurnover(num(at(fields, 37)) / 10000.0);
        // 港股换手率在 [59]，[38] 恒为 0
        q.setTurnoverRate(num(at(fields, 59)));
        q.setPe(num(at(fields, 39)));
        q.setAmplitude(num(at(fields, 43)));
        q.setMarket("HK");
        q.setCurrency("HK$");
        q.setQuoteTime(fixQuoteTime(at(fields, 30)));
        return q;
    }

    /**
     * A 股行情字段解析（腾讯 qt.gtimg.cn 布局）。
     */
    private StockQuote parseAQuoteFields(String code, String[] f) {
        StockQuote q = new StockQuote();
        q.setCode(code);
        q.setName(at(f, 1));
        q.setPrice(num(at(f, 3)));
        q.setPrevClose(num(at(f, 4)));
        q.setOpen(num(at(f, 5)));
        q.setVolume(num(at(f, 6)));
        q.setChange(num(at(f, 31)));
        q.setChangePct(num(at(f, 32)));
        q.setHigh(num(at(f, 33)));
        q.setLow(num(at(f, 34)));
        q.setTurnover(num(at(f, 37)));
        q.setTurnoverRate(num(at(f, 38)));
        q.setPe(num(at(f, 39)));
        // 振幅是 [43]；[46] 是市净率 —— 这两个极易混淆
        q.setAmplitude(num(at(f, 43)));
        q.setMarket(StockCode.marketOf(code));
        q.setCurrency("¥");
        q.setQuoteTime(fixQuoteTime(at(f, 30)));
        return q;
    }

    // ==================== 五档盘口 ====================

    /**
     * 获取五档盘口。
     *
     * <p>A 股盘口就藏在行情字段里，不需要额外请求 —— 这也是当初选腾讯作为主数据源的原因之一：
     * 一次 {@code q=} 请求同时拿到现价、量额、五档。</p>
     *
     * <p>字段布局（0-based，价格与量交替）：
     * [9..10] 买一 … [17..18] 买五，[19..20] 卖一 … [27..28] 卖五。</p>
     *
     * <p>港股走分时接口，该段恒为空串，返回 {@link OrderBook#unavailable()}。</p>
     */
    public OrderBook fetchOrderBook(String rawCode) {
        String code = StockCode.normalize(rawCode);
        if (StockCode.isHk(code)) {
            return OrderBook.unavailable();
        }
        String tCode = StockCode.toTencent(code);
        String url = "https://qt.gtimg.cn/q=" + tCode;
        String text = http.getText(url, StockHttpClient.REFERER_QT, StockHttpClient.GBK);

        String[] f = text.split("~", -1);
        if (f.length < 47) {
            throw new StockDataException("行情接口返回字段过少（" + f.length + "），代码可能不存在：" + code);
        }

        List<OrderBook.Level> bids = new ArrayList<>(5);
        List<OrderBook.Level> asks = new ArrayList<>(5);
        for (int i = 0; i < 5; i++) {
            double bidPrice = num(at(f, 9 + i * 2));
            double bidVol = num(at(f, 10 + i * 2));
            if (bidPrice > 0) {
                bids.add(new OrderBook.Level(bidPrice, bidVol));
            }
            double askPrice = num(at(f, 19 + i * 2));
            double askVol = num(at(f, 20 + i * 2));
            if (askPrice > 0) {
                asks.add(new OrderBook.Level(askPrice, askVol));
            }
        }

        OrderBook book = new OrderBook();
        book.setAvailable(!bids.isEmpty() || !asks.isEmpty());
        book.setBids(bids);
        // 腾讯给的是卖一到卖五（价格递增），这里反转成「卖五到卖一」，与行情软件自上而下一致
        // 注意：List.reversed() 是 Java 21 的 API，本项目 JDK 17 用不了
        java.util.Collections.reverse(asks);
        book.setAsks(asks);
        book.setQuoteTime(fixQuoteTime(at(f, 30)));
        return book;
    }

    /**
     * 批量行情：港股逐只（延迟接口无法批量），A 股每 50 只一批。
     *
     * <p>单只失败只跳过并记日志，不影响整批——前端拿到的是「能拿到的部分」。</p>
     */
    public List<StockQuote> fetchQuotesBatch(List<String> rawCodes) {
        if (rawCodes == null || rawCodes.isEmpty()) {
            return List.of();
        }
        // 去重保持顺序
        Set<String> codes = new LinkedHashSet<>();
        for (String c : rawCodes) {
            String n = StockCode.normalize(c);
            if (!n.isEmpty()) {
                codes.add(n);
            }
        }

        List<StockQuote> results = new ArrayList<>();
        List<String> aCodes = new ArrayList<>();

        for (String code : codes) {
            if (StockCode.isHk(code)) {
                try {
                    results.add(fetchHkQuote(code));
                } catch (Exception e) {
                    log.warn("批量行情：港股 {} 失败：{}", code, e.getMessage());
                }
            } else {
                aCodes.add(code);
            }
        }

        for (int i = 0; i < aCodes.size(); i += BATCH_SIZE) {
            List<String> chunk = aCodes.subList(i, Math.min(i + BATCH_SIZE, aCodes.size()));
            String param = chunk.stream().map(StockCode::toTencent)
                    .reduce((a, b) -> a + "," + b).orElse("");
            String url = "https://qt.gtimg.cn/q=" + param;
            try {
                String text = http.getText(url, StockHttpClient.REFERER_QT, StockHttpClient.GBK);
                // 每行: v_sh600519="1~贵州茅台~600519~...";
                Matcher m = QUOTE_LINE.matcher(text);
                while (m.find()) {
                    String[] f = m.group(2).split("~", -1);
                    // 失败行（v_pv_none_match）字段极少，跳过
                    if (f.length < 47) {
                        continue;
                    }
                    // [2] 是纯数字代码，与前端请求格式一致
                    String code = at(f, 2);
                    if (code.isEmpty()) {
                        continue;
                    }
                    results.add(parseAQuoteFields(code, f));
                }
            } catch (Exception e) {
                log.warn("批量行情失败 chunk={} err={}", param, e.getMessage());
            }
        }
        return results;
    }

    // ==================== 搜索 ====================

    /**
     * 搜索股票（腾讯智能搜索）。
     *
     * <p>返回体形如 {@code v_hint="sh~600519~贵州茅台^sz~000001~平安银行"}，
     * 名称可能含 Unicode 转义序列（反斜杠 + u + 4 位十六进制），需要还原。</p>
     */
    public List<SearchResult> search(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return List.of();
        }
        String url = "https://smartbox.gtimg.cn/s3/?q=" + urlEncode(keyword.trim()) + "&t=all";
        String text = http.getText(url, StockHttpClient.REFERER_SMARTBOX, StockHttpClient.GBK);

        List<SearchResult> results = new ArrayList<>();
        int eq = text.indexOf('=');
        if (eq < 0) {
            return results;
        }
        String value = text.substring(eq + 1).trim();
        value = value.replaceAll("^\"", "").replaceAll("\"$", "").trim();
        if (value.endsWith(";")) {
            value = value.substring(0, value.length() - 1);
        }

        for (String item : value.split("\\^")) {
            String[] f = item.split("~", -1);
            if (f.length < 3) {
                continue;
            }
            String marketRaw = f[0].trim();
            String rawCode = f[1].trim();
            String name = unescapeUnicode(f[2].trim());

            String market;
            String code;
            switch (marketRaw) {
                case "sh" -> {
                    market = "SH";
                    code = rawCode;
                }
                case "sz" -> {
                    market = "SZ";
                    code = rawCode;
                }
                case "hk" -> {
                    market = "HK";
                    // 港股补足 5 位（700 → 00700）
                    code = String.format("%5s", rawCode).replace(' ', '0');
                }
                default -> {
                    // 北交所等其他市场暂不纳入搜索
                    continue;
                }
            }
            results.add(new SearchResult(code, name, market, market + code));
        }
        return results;
    }

    // ==================== K 线 ====================

    /**
     * 获取 K 线。
     *
     * @param period day / week / month / m5 / m15 / m30 / m60
     */
    public List<KlineItem> fetchKline(String rawCode, String period) {
        String code = StockCode.normalize(rawCode);
        String p = period == null ? "day" : period.trim();
        boolean isMinute = "m5".equals(p) || "m15".equals(p) || "m30".equals(p) || "m60".equals(p);

        if (isMinute && StockCode.isHk(code)) {
            // 腾讯 mkline 实测对港股返回 param error
            throw new StockDataException("港股暂不支持分钟 K 线");
        }

        String tCode = StockCode.toTencent(code);
        String url;
        String dataKey;
        if (isMinute) {
            // 分钟 K：mkline，单次约 320 根（5 分≈6.7 交易日 / 60 分≈2.5 个月）
            url = "https://ifzq.gtimg.cn/appstock/app/kline/mkline?param=" + tCode + "," + p + ",,320";
            dataKey = p;
        } else {
            // 日/周/月：fqkline 前复权，120 根
            url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=" + tCode + "," + p + ",,,120,qfq";
            dataKey = switch (p) {
                case "week" -> "qfqweek";
                case "month" -> "qfqmonth";
                default -> "qfqday";
            };
        }

        JsonNode root = getJson(url, StockHttpClient.REFERER_IFZQ);
        JsonNode node = root.path("data").path(tCode);

        JsonNode klines = node.path(dataKey);
        if ((klines.isMissingNode() || !klines.isArray()) && !isMinute) {
            // 兜底：未复权字段（week/month/day）
            klines = node.path(p);
        }
        if (!klines.isArray() || klines.isEmpty()) {
            throw new StockDataException("K 线数据为空：" + code + " " + p);
        }

        List<KlineItem> items = new ArrayList<>(klines.size());
        for (JsonNode k : klines) {
            if (!k.isArray() || k.size() < 6) {
                continue;
            }
            KlineItem item = new KlineItem();
            item.setDate(k.get(0).asText(""));
            item.setOpen(jsonNum(k.get(1)));
            item.setClose(jsonNum(k.get(2)));
            item.setHigh(jsonNum(k.get(3)));
            item.setLow(jsonNum(k.get(4)));
            item.setVolume(jsonNum(k.get(5)));
            // 日/周/月只有 6 个字段、无成交额，用 量×收盘 兜底（单位：手×元）
            double turnover = k.size() > 6 ? jsonNum(k.get(6)) : item.getVolume() * item.getClose();
            item.setTurnover(turnover);
            items.add(item);
        }
        if (items.isEmpty()) {
            throw new StockDataException("K 线数据为空：" + code + " " + p);
        }
        return items;
    }

    // ==================== 分时 ====================

    /**
     * 获取当日分时。
     *
     * <p>三个必须点（对齐桌面版实现）：</p>
     * <ol>
     *   <li><b>差分</b>：接口给的是累计量，本分钟量 = 本分钟累计 − 上一分钟累计</li>
     *   <li><b>剔除时段外分钟</b>：收盘后会附带 15:06-15:30 的零星僵尸成交，不剔除会污染尾盘信号</li>
     *   <li><b>单位归一</b>：A 股累计量单位是「手」，港股是「股」（对外统一换成手）</li>
     * </ol>
     */
    public IntradayData fetchIntraday(String rawCode) {
        String code = StockCode.normalize(rawCode);
        String tCode = StockCode.toTencent(code);
        String url = "https://web.ifzq.gtimg.cn/appstock/app/minute/query?code=" + tCode;
        JsonNode root = getJson(url, StockHttpClient.REFERER_IFZQ);

        JsonNode stockData = root.path("data").path(tCode);
        if (stockData.isMissingNode()) {
            throw new StockDataException("未找到分时数据：" + code);
        }

        // 昨收：[4]
        double preClose = jsonNum(stockData.path("qt").path(tCode).path(4));
        String date = stockData.path("data").path("date").asText("");

        JsonNode points = stockData.path("data").path("data");
        if (!points.isArray() || points.isEmpty()) {
            throw new StockDataException("分时数据点为空（可能停牌或非交易日）：" + code);
        }

        boolean isHk = StockCode.isHk(code);
        // A 股/北交所 1 手 = 100 股；港股累计量本身就是股
        double sharesPerUnit = isHk ? 1.0 : 100.0;

        List<IntradayItem> items = new ArrayList<>();
        double cumPv = 0;          // ∑(price × 分钟量)，算 VWAP
        double cumVol = 0;
        double prevVol = 0;        // 上一分钟累计量（差分基准）
        double prevTurnover = 0;   // 上一分钟累计额（差分基准）

        for (JsonNode p : points) {
            String s = p.asText("");
            if (s.isEmpty()) {
                continue;
            }
            String[] parts = s.split(" ");
            if (parts.length < 3) {
                continue;
            }
            String rawTime = parts[0];
            String time = formatTime(rawTime);

            if (!isSessionMinute(time, isHk)) {
                // 被剔除的分钟同样要推进累计基准，否则后续差分会算错
                prevVol = numOr0(parts, 2);
                prevTurnover = numOr0(parts, 3);
                continue;
            }

            double price = numOr0(parts, 1);
            double cumVolume = numOr0(parts, 2);      // 累计量
            double cumTurnover = numOr0(parts, 3);    // 累计额（元）

            // 差分：防御性处理跨天重置
            double volume = cumVolume >= prevVol ? cumVolume - prevVol : cumVolume;
            double turnover = cumTurnover >= prevTurnover ? cumTurnover - prevTurnover : cumTurnover;
            if (isHk) {
                volume /= 100.0;   // 港股股数 → 手，与其他接口口径一致
            }
            prevVol = cumVolume;
            prevTurnover = cumTurnover;

            cumPv += price * volume;
            cumVol += volume;
            double vwap = cumVol > 0 ? round2(cumPv / cumVol) : 0;

            // 均价优先用接口累计值（避免差分再累加的精度损失）
            double avgPrice;
            if (cumVolume > 0 && cumTurnover > 0) {
                avgPrice = round2(cumTurnover / (cumVolume * sharesPerUnit));
            } else if (cumVol > 0) {
                avgPrice = round2(cumPv / cumVol);
            } else {
                avgPrice = 0;
            }

            IntradayItem item = new IntradayItem();
            item.setTime(time);
            item.setPrice(price);
            item.setAvgPrice(avgPrice);
            item.setVolume(volume);
            item.setTurnover(turnover);
            item.setVwap(vwap);
            items.add(item);
        }

        if (items.isEmpty()) {
            throw new StockDataException("分时数据为空：" + code);
        }

        IntradayData data = new IntradayData();
        data.setItems(items);
        data.setPreClose(preClose);
        data.setDate(date);
        data.setCode(code);
        data.setQuoteTime(fixQuoteTime(stockData.path("qt").path(tCode).path(30).asText("")));
        return data;
    }

    // ==================== 指数 ====================

    /**
     * 拉取单个指数。
     */
    public MarketIndex fetchIndex(String indexCode) {
        String tCode = indexCode.startsWith("sh") || indexCode.startsWith("sz")
                ? indexCode
                : (indexCode.startsWith("000") || indexCode.startsWith("6") ? "sh" + indexCode : "sz" + indexCode);

        String url = "https://qt.gtimg.cn/q=" + tCode;
        String text = http.getText(url, StockHttpClient.REFERER_QT, StockHttpClient.GBK);
        String[] f = text.split("~", -1);
        if (f.length < 40) {
            throw new StockDataException("指数行情字段过少：" + indexCode);
        }

        MarketIndex mi = new MarketIndex();
        mi.setCode(indexCode.length() > 2 ? indexCode.replaceFirst("^(sh|sz)", "") : indexCode);
        mi.setName(at(f, 1));
        mi.setPrice(num(at(f, 3)));
        mi.setChange(num(at(f, 31)));
        mi.setChangePct(num(at(f, 32)));
        mi.setQuoteTime(fixQuoteTime(at(f, 30)));
        return mi;
    }

    // ==================== 内部工具 ====================

    private JsonNode getJson(String url, String referer) {
        String text = http.getText(url, referer, java.nio.charset.StandardCharsets.UTF_8);
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new StockDataException("解析 JSON 失败：" + e.getMessage());
        }
    }

    /**
     * 交易时段判定（用于剔除盘后僵尸数据）。
     * A 股 9:30-11:30 / 13:00-15:00；港股 9:30-12:00 / 13:00-16:00。
     */
    private boolean isSessionMinute(String time, boolean isHk) {
        String[] hm = time.split(":");
        if (hm.length != 2) {
            return false;
        }
        int h;
        int m;
        try {
            h = Integer.parseInt(hm[0]);
            m = Integer.parseInt(hm[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        int t = h * 60 + m;
        if (isHk) {
            return (t >= 570 && t <= 720) || (t >= 780 && t <= 960);
        }
        return (t >= 570 && t <= 690) || (t >= 780 && t <= 900);
    }

    /** "0930" → "09:30"；非常规格式原样返回 */
    private String formatTime(String raw) {
        if (raw.length() == 4 && raw.chars().allMatch(Character::isDigit)) {
            return raw.substring(0, 2) + ":" + raw.substring(2);
        }
        return raw;
    }

    /**
     * 腾讯报价时间字段 [30] 归一化为 {@code yyyy-MM-dd HH:mm:ss}。
     *
     * <p>实测同一接口下两种格式并存：
     * A 股是 {@code 20261019092730}（14 位纯数字），港股是 {@code 2026/10/16 16:08:22}（斜杠分隔、
     * 月日不补零）。归一化后前端才不必为每个市场写一套解析。</p>
     *
     * <p>解析不了就返回空串而不是抛错 —— 时间戳只是「锦上添花」，
     * 缺它不该让整个行情请求失败。</p>
     */
    private String fixQuoteTime(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.length() == 14 && s.chars().allMatch(Character::isDigit)) {
            return s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8)
                    + " " + s.substring(8, 10) + ":" + s.substring(10, 12) + ":" + s.substring(12, 14);
        }
        Matcher m = HK_TIME.matcher(s);
        if (m.matches()) {
            return String.format("%s-%02d-%02d %02d:%02d:%02d",
                    m.group(1),
                    Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
        }
        // 已经是目标格式就原样透传
        if (s.length() >= 19 && s.charAt(4) == '-' && s.charAt(7) == '-') {
            return s;
        }
        return "";
    }

    /** 港股回报时间 {@code yyyy/M/d H:mm:ss}：月、日、时不补零 */
    private static final Pattern HK_TIME =
            Pattern.compile("(\\d{4})/(\\d{1,2})/(\\d{1,2})[ T](\\d{1,2}):(\\d{2}):(\\d{2})");

    private String at(String[] arr, int i) {
        return i < arr.length && arr[i] != null ? arr[i] : "";
    }

    private double num(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double numOr0(String[] parts, int idx) {
        return idx < parts.length ? num(parts[idx]) : 0;
    }

    /** 兼容 JSON 数字与字符串两种形态 */
    private double jsonNum(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return 0;
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        return num(n.asText(""));
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 还原 Unicode 转义序列（腾讯搜索返回的名称常见） */
    private String unescapeUnicode(String s) {
        if (s == null || s.indexOf("\\u") < 0) {
            return s == null ? "" : s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 5 < s.length() && s.charAt(i + 1) == 'u') {
                String hex = s.substring(i + 2, i + 6);
                try {
                    sb.append((char) Integer.parseInt(hex, 16));
                    i += 6;
                    continue;
                } catch (NumberFormatException ignored) {
                    // 落到下面原样输出
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
