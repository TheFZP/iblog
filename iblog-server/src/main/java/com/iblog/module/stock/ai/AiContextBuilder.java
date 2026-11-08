package com.iblog.module.stock.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iblog.module.paper.support.Indicators;
import com.iblog.module.stock.model.KlineItem;
import com.iblog.module.stock.model.StockQuote;
import com.iblog.module.stock.service.StockSettingService;
import com.iblog.module.stock.service.TencentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 组装发给模型的 system prompt。
 *
 * <p>「预加载」是这套 AI 的关键设计：模型第一次说话时手里已经有当前股票的行情、
 * 近 30 根 K 线、均线、MACD、RSI、行业，所以用户问「这股怎么样」时它不必先花两轮
 * 调工具——首字延迟从 3~5 秒降到 1 秒内。工具仍然保留，用于追问细节。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiContextBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 预加载只放最近这么多根 K 线，再多的边际信息很低。 */
    private static final int PRELOAD_BARS = 30;
    /** 画像在 stock_setting 里的键，与 AI 工具 {@code save_user_profile} 用同一个。 */
    private static final String KEY_USER_PROFILE = "ai_user_profile";

    private final TencentService tencentService;
    private final StockSettingService settingService;

    /**
     * 构建 system prompt。
     *
     * @param stockCode   当前股票代码，可为空（全局对话）
     * @param stockName   当前股票名称，可为空
     * @param webSearch   是否启用联网搜索（决定是否注入搜索工具与搜索方法论）
     * @param skillsPrompt 各 skill 的能力说明（由 {@link AiToolSchemas} 拼好）
     * @param godMode     是否开启股神模式（注入四段式深度分析契约）
     */
    public String build(String stockCode, String stockName, boolean webSearch, String skillsPrompt,
                        boolean godMode) {
        String skills = skillsPrompt == null ? "" : skillsPrompt;
        if (godMode) {
            // 股神模式放在 skill 说明的最前面：它是「怎么组织回答」的总纲，
            // 排在具体工具用法之后容易被当成又一个可选技巧而忽略。
            skills = AiToolPrompts.GOD_MODE_PROMPT + (skills.isEmpty() ? "" : "\n\n" + skills);
        }
        String prompt = AiToolPrompts.SYSTEM_PROMPT_TEMPLATE
                .replace("{{BEIJING_TIME}}", LocalDateTime.now().format(TIME_FMT))
                .replace("{{MARKET_RULES}}", AiToolPrompts.MARKET_RULES)
                .replace("{{SEARCH_POLICY}}", webSearch ? AiToolPrompts.SEARCH_POLICY_ON : AiToolPrompts.SEARCH_POLICY_OFF)
                .replace("{{SKILL_PROMPTS}}", skills)
                .replace("{{USER_PROFILE}}", userProfileSection())
                .replace("{{PRELOAD_SECTION}}", preloadSection(stockCode, stockName))
                .replace("{{STOCK_CONTEXT}}", stockContextSection(stockCode, stockName));
        if (prompt.contains("{{")) {
            // 占位符没替换干净说明模板和代码脱节了，至少留一条日志，别让模型读到 "{{XXX}}"
            log.warn("system prompt 仍有未替换占位符，请检查模板");
        }
        return prompt;
    }

    /** 当前股票上下文（哪只股票、今天几号）。 */
    private String stockContextSection(String stockCode, String stockName) {
        if (stockCode == null || stockCode.isBlank()) {
            return "当前没有打开某只个股，用户可能会直接问大盘或让你选股。";
        }
        String name = stockName == null || stockName.isBlank() ? stockCode : stockName;
        return "用户当前正在查看：「" + name + "（" + stockCode + "）」。默认围绕这只股票回答，"
                + "除非用户明确问别的标的。";
    }

    /**
     * 用户画像。
     *
     * <p>画像有两个写入方：用户在前端「投资画像」弹窗里手写的 Markdown，
     * 以及 AI 自己调 {@code save_user_profile} 写进去的 JSON。两者共用
     * {@code stock_setting.ai_user_profile} 这一个键，所以这里要判断一下形状再决定怎么呈现——
     * 直接把 JSON 原样塞进 prompt 也能用，但模型会把注意力放在花括号上。</p>
     */
    private String userProfileSection() {
        String profile;
        try {
            profile = settingService.getPlain(KEY_USER_PROFILE);
        } catch (Exception e) {
            // 读库失败不该让整轮对话开不起来，降级成「未填写」让模型按中性处理
            log.warn("AI 读取用户画像失败 err={}", e.toString());
            profile = null;
        }
        if (profile == null || profile.isBlank()) {
            return "用户尚未填写投资画像，按中性偏稳健处理，并在需要时提醒用户可以在行情页的「投资画像」里补充。";
        }
        String readable = prettyProfile(profile);
        return "以下是用户填写的投资画像，回答时请贴合其风格与约束：\n" + readable;
    }

    /**
     * 把画像内容转成人看/模型看的文本。
     *
     * <p>手写的是 Markdown，原样用；AI 写的是 JSON，转成 {@code 字段: 值} 的列表更好读。
     * 解析失败就当作普通文本返回，绝不能因为格式不合预期就丢掉用户填的内容。</p>
     */
    private static String prettyProfile(String raw) {
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return trimmed;
        }
        try {
            JsonNode node = MAPPER.readTree(trimmed);
            StringBuilder sb = new StringBuilder();
            flattenProfile(node, "", sb);
            return sb.isEmpty() ? trimmed : sb.toString().trim();
        } catch (Exception e) {
            return trimmed;
        }
    }

    /** 递归把 JSON 摊平成 {@code 路径: 值} 行，嵌套层级用点号表示。 */
    private static void flattenProfile(JsonNode node, String prefix, StringBuilder sb) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry ->
                    flattenProfile(entry.getValue(), prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey(), sb));
            return;
        }
        if (node.isArray()) {
            StringBuilder line = new StringBuilder();
            for (JsonNode item : node) {
                if (!line.isEmpty()) {
                    line.append("、");
                }
                line.append(item.isValueNode() ? item.asText() : item.toString());
            }
            if (!line.isEmpty()) {
                sb.append(prefix).append(": ").append(line).append('\n');
            }
            return;
        }
        if (node.isValueNode()) {
            String text = node.asText();
            if (!text.isBlank()) {
                sb.append(prefix).append(": ").append(text).append('\n');
            }
        }
    }

    /**
     * 预加载行情快照。
     *
     * <p>整段 try/catch：预加载失败不该让对话开不起来，模型可以自己调工具补数据。</p>
     */
    private String preloadSection(String stockCode, String stockName) {
        if (stockCode == null || stockCode.isBlank()) {
            return "";
        }
        ObjectNode snapshot = MAPPER.createObjectNode();
        boolean any = false;
        try {
            StockQuote quote = tencentService.fetchQuote(stockCode);
            if (quote != null) {
                ObjectNode node = snapshot.putObject("quote");
                node.put("code", quote.getCode());
                node.put("name", quote.getName());
                node.put("price", quote.getPrice());
                node.put("prevClose", quote.getPrevClose());
                node.put("open", quote.getOpen());
                node.put("high", quote.getHigh());
                node.put("low", quote.getLow());
                node.put("change", quote.getChange());
                node.put("changePct", round2(quote.getChangePct()));
                node.put("volume", quote.getVolume());
                node.put("turnover", quote.getTurnover());
                node.put("turnoverRate", round2(quote.getTurnoverRate()));
                node.put("pe", round2(quote.getPe()));
                node.put("amplitude", round2(quote.getAmplitude()));
                node.put("floatMarketCap", quote.getFloatMarketCap());
                node.put("totalMarketCap", quote.getTotalMarketCap());
                node.put("quoteTime", quote.getQuoteTime());
                any = true;
            }
        } catch (Exception e) {
            log.warn("AI 预加载行情失败 code={} err={}", stockCode, e.toString());
        }
        try {
            List<KlineItem> bars = tencentService.fetchKline(stockCode, "day");
            if (bars != null && !bars.isEmpty()) {
                appendKline(snapshot, bars);
                any = true;
            }
        } catch (Exception e) {
            log.warn("AI 预加载 K 线失败 code={} err={}", stockCode, e.toString());
        }
        if (!any) {
            return "";
        }
        return "以下数据已在本次对话开始时预加载（行情为调用时刻的实时值，收盘后即为当日收盘数据）：\n"
                + "```json\n" + snapshot.toPrettyString() + "\n```\n";
    }

    /** 追加 K 线与算好的指标。 */
    private void appendKline(ObjectNode snapshot, List<KlineItem> bars) {
        ObjectNode kline = snapshot.putObject("kline");
        kline.put("period", "day");
        kline.put("today", LocalDate.now().toString());
        kline.put("totalBars", bars.size());

        double[] closes = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            closes[i] = bars.get(i).getClose();
        }
        ObjectNode ma = kline.putObject("ma");
        putNum(ma, "MA5", Indicators.sma(closes, 5));
        putNum(ma, "MA10", Indicators.sma(closes, 10));
        putNum(ma, "MA20", Indicators.sma(closes, 20));
        putNum(ma, "MA30", Indicators.sma(closes, 30));
        ObjectNode macd = kline.putObject("macd");
        Indicators.Macd m = Indicators.macd(closes);
        putNum(macd, "DIF", m.dif());
        putNum(macd, "DEA", m.dea());
        putNum(macd, "hist", m.hist());
        putNum(kline, "rsi14", Indicators.rsi(closes, 14));

        var recent = kline.putArray("recentBars");
        int from = Math.max(0, bars.size() - PRELOAD_BARS);
        for (int i = from; i < bars.size(); i++) {
            KlineItem bar = bars.get(i);
            ObjectNode item = recent.addObject();
            item.put("date", bar.getDate());
            item.put("open", bar.getOpen());
            item.put("close", bar.getClose());
            item.put("high", bar.getHigh());
            item.put("low", bar.getLow());
            item.put("volume", bar.getVolume());
            item.put("turnover", bar.getTurnover());
        }
    }

    private static void putNum(ObjectNode node, String field, double value) {
        if (Double.isFinite(value)) {
            node.put(field, round2(value));
        }
    }

    private static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }

    /**
     * 把一句话压缩成模型可读的单行摘要，用于工具状态条。
     *
     * <p>和 {@link #build} 分开放：控制器要把「调了哪个工具、参数是什么」推给前端，
     * 那是给人看的，不需要完整 JSON。</p>
     */
    public static String briefArgs(JsonNode args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        args.fields().forEachRemaining(entry -> {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append('=');
            String text = entry.getValue().asText("");
            sb.append(text.length() > 40 ? text.substring(0, 40) + "…" : text);
        });
        return sb.toString();
    }
}
