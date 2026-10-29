package com.iblog.module.stock.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.iblog.common.exception.BizException;
import com.iblog.module.stock.service.StockSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 问财查询语句的 AI 改写。
 *
 * <p>用户在「问财选股」里写的是口语（「找点便宜的白马股」），问财要的是带单位、带比较符的
 * 结构化条件（「总市值大于100亿; 市盈率小于20」）。这里用一次短补全把前者翻译成后者，
 * 顺便结合用户画像补上换手率、量比、PE 这类他习惯看的维度。</p>
 *
 * <p>单独一层而不塞进 {@link AiChatService}：那个类是「多轮 Agent 循环」，这里是
 * 「一问一答、256 token 封顶」的一次性调用，混在一起会让两边都难读。</p>
 *
 * <p>清洗（{@link #cleanOptimizedQuery}）是独立静态方法且不抛业务异常的原因：
 * 模型输出千奇百怪（带围栏、带「优化后的查询：」前缀、多行、结尾中文分号），
 * 这段纯文本处理值得单独测试，不该被 HTTP 与配置判断挡住。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiQueryOptimizer {

    /** 画像在 stock_setting 里的键，与 AI 工具 {@code save_user_profile} 用的是同一个。 */
    private static final String KEY_USER_PROFILE = "ai_user_profile";
    /** 输入上限：再长就不是「选股条件」而是研报了，也挡不住 token 成本。 */
    private static final int MAX_INPUT_LENGTH = 500;
    /** 改写只要一句话，256 足够；给 8192 只是把最坏等待时间和费用一起放大。 */
    private static final int MAX_TOKENS = 256;
    /** 没填画像时的占位，让提示词里的「用户画像：」后面始终有内容。 */
    private static final String EMPTY_PROFILE = "（未设置）";

    /** 从模型输出里捞 JSON 对象。贪婪版优先：正常只会有一个对象。 */
    private static final Pattern JSON_GREEDY = Pattern.compile("\\{[\\s\\S]*\"query\"[\\s\\S]*\\}");
    /** 贪婪版把多余文本吞进来导致解析失败时，用非贪婪版再试一次。 */
    private static final Pattern JSON_LAZY = Pattern.compile("\\{[\\s\\S]*?\"query\"[\\s\\S]*?\\}");
    /**
     * 常见前缀标签（忽略大小写）。合成一条交替正则，剥完还要循环再剥：
     * 「问财 查询语句：xxx」会先掉「问财」再掉「查询语句：」。
     */
    private static final Pattern PREFIX_LABEL = Pattern.compile(
            "^(?:(?:优化后(?:的)?(?:问财)?(?:查询|语句)?[:：]?)|(?:查询语句[:：]?)|(?:问财[:：]?)|(?:建议(?:查询|语句)[:：]?))",
            Pattern.CASE_INSENSITIVE);
    /** 换行（含两侧空白）统一变成条件分隔符。 */
    private static final Pattern LINE_BREAK = Pattern.compile("\\s*[\\r\\n]+\\s*");

    private final AiConfigService configService;
    private final AiLlmClient llmClient;
    private final StockSettingService settingService;

    /**
     * 把口语化选股条件改写成问财认的查询语句。
     *
     * @param raw 用户原始输入
     * @return 清洗后的查询语句（保证非空，否则抛 {@link BizException}）
     */
    public String optimizeIwencaiQuery(String raw) {
        AiConfigService.Config config = configService.load();
        if (!config.available()) {
            throw new BizException(400, "AI 未配置：请到管理后台 → AI 配置 填写 DeepSeek API Key");
        }
        if (raw == null || raw.isBlank()) {
            throw new BizException(400, "请输入选股条件");
        }
        String input = raw.trim();
        if (input.length() > MAX_INPUT_LENGTH) {
            throw new BizException(400, "选股条件过长（最多 500 字）");
        }

        String profile = readProfile();
        ArrayNode messages = AiJson.MAPPER.createArrayNode();
        messages.addObject()
                .put("role", "user")
                .put("content", buildPrompt(profile, input));

        String text;
        try {
            text = llmClient.complete(config.apiKey(), config.model(), messages, MAX_TOKENS);
        } catch (AiLlmClient.AiException e) {
            // 上游的堆栈对用户没意义，只把可读的中文提示转成业务异常
            log.warn("问财查询改写失败 err={}", e.getMessage());
            throw new BizException(400, e.getMessage());
        }

        String cleaned = cleanOptimizedQuery(text);
        if (cleaned.isEmpty()) {
            throw new BizException(400, "AI 未能生成有效查询，请手动调整或重试");
        }
        return cleaned;
    }

    /** 拼提示词：模板里的占位符按顺序替换，避免 String.format 被 JSON 花括号绊住。 */
    private static String buildPrompt(String profile, String input) {
        return PROMPT_TEMPLATE
                .replace("<画像>", profile)
                .replace("<原始输入>", input);
    }

    /** 读画像；读库失败或没填过都降级成「未设置」，不让一次查询因为画像挂掉。 */
    private String readProfile() {
        try {
            String profile = settingService.getPlain(KEY_USER_PROFILE);
            return profile == null || profile.isBlank() ? EMPTY_PROFILE : profile.trim();
        } catch (Exception e) {
            log.warn("问财查询改写读取用户画像失败 err={}", e.toString());
            return EMPTY_PROFILE;
        }
    }

    /**
     * 清洗模型输出。
     *
     * <p>顺序是有讲究的：先去围栏（否则围栏里的 JSON 匹配不到），再取 JSON（JSON 里的 query
     * 是最可信的结果），最后才去前缀和换行——三者都会改动文本，顺序反了会把标签留在结果里。</p>
     *
     * @return 清洗后的文本；无法清洗时返回空串，由调用方决定报什么错
     */
    static String cleanOptimizedQuery(String text) {
        if (text == null) {
            return "";
        }
        String body = stripFence(text);

        String fromJson = extractQuery(body);
        if (fromJson != null) {
            // JSON 里的 query 已经是「一句话」，但仍要过一遍换行/结尾分号，防止模型自己在值里换行
            return normalize(fromJson);
        }
        return normalize(body);
    }

    /** 剥掉 markdown 代码围栏：开头的 ``` 加语言名、结尾的 ```。 */
    private static String stripFence(String text) {
        String s = text.trim();
        if (s.startsWith("```")) {
            int firstLineEnd = s.indexOf('\n');
            if (firstLineEnd < 0) {
                // 光有开标记没有内容，等价于空
                return "";
            }
            s = s.substring(firstLineEnd + 1);
        }
        s = s.trim();
        if (s.endsWith("```")) {
            s = s.substring(0, s.length() - 3);
        }
        return s.trim();
    }

    /** 从文本里找 {@code {"query": "..."}} 并取出 query；找不到或解析失败返回 null。 */
    private static String extractQuery(String text) {
        String fromGreedy = findQuery(JSON_GREEDY, text);
        if (fromGreedy != null) {
            return fromGreedy;
        }
        String fromLazy = findQuery(JSON_LAZY, text);
        if (fromLazy != null) {
            return fromLazy;
        }
        // 兜底：整段就是一个 JSON 对象（正则没命中时也还能解析）
        return parseQuery(text);
    }

    private static String findQuery(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String found = parseQuery(matcher.group());
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String parseQuery(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            JsonNode node = AiJson.MAPPER.readTree(candidate);
            if (node == null || !node.isObject()) {
                return null;
            }
            JsonNode query = node.get("query");
            if (query == null || !query.isValueNode()) {
                return null;
            }
            String value = query.asText("").trim();
            return value.isEmpty() ? null : value;
        } catch (Exception e) {
            // 模型输出截断/多逗号都算正常，静默回退到纯文本清洗
            return null;
        }
    }

    /** 去前缀标签、换行转分隔符、去结尾分号。 */
    private static String normalize(String text) {
        String s = stripPrefixLabels(text);
        if (s.isEmpty()) {
            return "";
        }
        s = LINE_BREAK.matcher(s).replaceAll("; ").trim();
        // 中文分号也要去，模型很爱在末尾留一个
        while (s.endsWith(";") || s.endsWith("；")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    /** 反复剥前缀标签，直到剥不动为止（「问财 查询语句：」这种复合前缀要剥两次）。 */
    private static String stripPrefixLabels(String text) {
        String s = text == null ? "" : text.trim();
        while (!s.isEmpty()) {
            Matcher matcher = PREFIX_LABEL.matcher(s);
            if (!matcher.find() || matcher.end() == 0) {
                break;
            }
            String rest = s.substring(matcher.end()).trim();
            // 整段就是一个标签（没有实际条件）时停手，交给调用方判空
            if (rest.isEmpty()) {
                break;
            }
            s = rest;
        }
        return s;
    }

    /** 逐字照抄的提示词模板（占位符由 {@link #buildPrompt} 替换）。 */
    private static final String PROMPT_TEMPLATE = """
            你是问财选股查询优化助手。用户输入了选股条件，请理解他的意图，结合用户画像中的投资风格，把条件改写为更专业、更完整的问财查询语句。

            规则：
            1. 只输出 JSON：{"query": "优化后的查询语句"}，不要输出任何其他内容、解释或 Markdown。
            2. 条件之间用分号「;」分隔；用「大于/小于/不低于/不高于/等于」等明确表述。
            3. 市值/成交额等带单位（亿/万），如「总市值大于100亿」。
            4. 保留用户明确指定的条件；可结合画像风格补充常见维度（如换手率、量比、涨幅、市值区间、市盈率等），但不要偏离用户意图，不要添加用户明确排除的内容。

            用户画像：
            <画像>

            用户输入：
            <原始输入>
            """;
}
