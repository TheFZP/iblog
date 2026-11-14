package com.iblog.module.tool.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iblog.module.stock.ai.AiConfigService;
import com.iblog.module.stock.ai.AiLlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 简单的天气 Agent：模型自己决定先定位城市还是直接查天气，工具由服务端执行。
 *
 * <p>没有让模型「凭空写一段天气」：天气数据必须来自真实接口。工具调用失败时把错误文本
 * 交给模型，它可以改用另一个城市名重试，这比直接抛异常给用户更有用。</p>
 *
 * <p>只有两三个工具、最多三轮，刻意保持简单：这类单轮任务用深度推理既慢又贵，
 * 所以关闭 thinking 并把输出限制在几百 token。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherAgentService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINA);

    /** 工具调用最多几轮；天气任务两轮足够，留一轮给模型纠错。 */
    static final int MAX_ROUNDS = 3;
    /** 输出上限：一段 100~200 字的建议，给足余量即可。 */
    static final int MAX_TOKENS = 700;
    static final int MAX_QUESTION = 200;

    private static final String SYSTEM_PROMPT = """
你是「天气管家」，一个只负责天气的助手。

规则：
1. 查天气前先调用工具；不要编造温度、降雨等数据。
2. 用户没给城市时，从问题里找；找不到就调用 resolve_city 之前先问一句（最多问一次）。
3. 先给一句结论，再用 2~4 条短句给建议，覆盖穿衣、出行、是否需要带伞或防晒。
4. 中文回答，不超过 180 字，不要使用 Markdown 表格或标题。
5. 工具返回错误时，换一种城市写法重试一次；仍失败就如实说明。
""";

    private final WeatherClient weatherClient;
    private final AiConfigService configService;
    private final AiLlmClient llmClient;

    public record Trace(String name, String label, String args, String result, boolean ok) {
    }

    public record Result(boolean available, String answer, String message,
                         List<Trace> traces, String model, long elapsedMs) {
    }

    public Result analyze(String city, String question) {
        long started = System.currentTimeMillis();
        AiConfigService.Config config = configService.load();
        if (!config.available()) {
            return new Result(false, "", "AI 未配置：请到管理后台 → AI 配置填写 API Key",
                    List.of(), config.model(), 0);
        }

        String cleanCity = StringUtils.hasText(city) ? city.trim() : "";
        String ask = StringUtils.hasText(question) ? question.trim() : "";
        if (ask.length() > MAX_QUESTION) {
            ask = ask.substring(0, MAX_QUESTION);
        }
        if (!cleanCity.isEmpty() && !ask.contains(cleanCity)) {
            ask = "城市：" + cleanCity + "。" + (ask.isEmpty() ? "请根据未来几天的天气给点建议。" : ask);
        }
        if (ask.isEmpty()) {
            ask = "请根据未来几天的天气给点建议（穿衣、出行、是否需要带伞）。";
        }

        ArrayNode messages = MAPPER.createArrayNode();
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", SYSTEM_PROMPT + "\n今天是 " + LocalDate.now(ZONE).format(DATE) + "。");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", ask);

        ArrayNode tools = tools();
        ToolContext context = new ToolContext();
        List<Trace> traces = new ArrayList<>();
        StringBuilder answer = new StringBuilder();

        try {
            for (int round = 0; round < MAX_ROUNDS; round++) {
                RoundResult result = runRound(config, messages, tools, answer);
                if (result.toolCalls.isEmpty()) {
                    break;
                }
                appendAssistantTurn(messages, result);
                for (AiLlmClient.ToolCall call : result.toolCalls) {
                    String name = call.name() == null ? "" : call.name();
                    JsonNode args = parseArgs(call.arguments());
                    String output = execute(name, args, context);
                    boolean ok = !output.startsWith("[错误]");
                    traces.add(new Trace(name, labelOf(name), args.toString(), summarizeTrace(name, output), ok));
                    ObjectNode toolMessage = messages.addObject();
                    toolMessage.put("role", "tool");
                    toolMessage.put("tool_call_id", call.id() == null ? "" : call.id());
                    toolMessage.put("content", output);
                }
            }
        } catch (AiLlmClient.AiException e) {
            log.warn("天气 Agent 调用失败 err={}", e.getMessage());
            if (answer.isEmpty()) {
                return new Result(true, "", e.getMessage(), traces, config.model(),
                        System.currentTimeMillis() - started);
            }
        } catch (Exception e) {
            log.warn("天气 Agent 异常 err={}", e.toString());
            if (answer.isEmpty()) {
                return new Result(true, "", "AI 分析失败，请稍后重试", traces, config.model(),
                        System.currentTimeMillis() - started);
            }
        }

        String text = answer.toString().trim();
        if (text.isEmpty()) {
            return new Result(true, "", "AI 没有返回内容，请重试", traces, config.model(),
                    System.currentTimeMillis() - started);
        }
        return new Result(true, text, "", traces, config.model(),
                System.currentTimeMillis() - started);
    }

    private record RoundResult(StringBuilder content, List<AiLlmClient.ToolCall> toolCalls) {
    }

    private RoundResult runRound(AiConfigService.Config config, ArrayNode messages, ArrayNode tools,
                                 StringBuilder answer) {
        StringBuilder content = new StringBuilder();
        List<AiLlmClient.ToolCall> calls = new ArrayList<>();
        llmClient.stream(config.apiKey(), config.model(), messages, tools,
                false, null, MAX_TOKENS, new AiLlmClient.Listener() {
                    @Override
                    public void onReasoning(String delta) {
                        // 关闭 thinking 后正常不会回调；不展示思维链
                    }

                    @Override
                    public void onContent(String delta) {
                        if (delta != null && !delta.isEmpty()) {
                            content.append(delta);
                            answer.append(delta);
                        }
                    }

                    @Override
                    public void onToolCalls(List<AiLlmClient.ToolCall> values) {
                        if (values != null) {
                            calls.addAll(values);
                        }
                    }
                });
        return new RoundResult(content, calls);
    }

    private static void appendAssistantTurn(ArrayNode messages, RoundResult result) {
        ObjectNode assistant = messages.addObject();
        assistant.put("role", "assistant");
        assistant.put("content", result.content().toString());
        ArrayNode calls = assistant.putArray("tool_calls");
        for (AiLlmClient.ToolCall call : result.toolCalls()) {
            ObjectNode item = calls.addObject();
            item.put("id", call.id() == null ? "" : call.id());
            item.put("type", "function");
            ObjectNode function = item.putObject("function");
            function.put("name", call.name() == null ? "" : call.name());
            function.put("arguments", call.arguments() == null ? "{}" : call.arguments());
        }
    }

    private ArrayNode tools() {
        ArrayNode tools = MAPPER.createArrayNode();
        tools.add(tool("resolve_city",
                "把用户说的城市名解析成中国天气网的城市。查询天气前可先调用，也可直接调用 get_weather。",
                """
{"type":"object","properties":{"city":{"type":"string","description":"城市名，如广州、北京市、杭州"}},"required":["city"]}"""));
        tools.add(tool("get_weather",
                "获取某城市当前天气与未来若干天的预报（温度、天气现象、风向风力、空气质量、日出日落）。",
                """
{"type":"object","properties":{"city":{"type":"string","description":"城市名，如广州"},"days":{"type":"number","description":"预报天数，1~7，默认 7"}},"required":["city"]}"""));
        return tools;
    }

    private ObjectNode tool(String name, String description, String parametersJson) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "function");
        ObjectNode function = node.putObject("function");
        function.put("name", name);
        function.put("description", description);
        try {
            function.set("parameters", MAPPER.readTree(parametersJson));
        } catch (Exception e) {
            function.putObject("parameters").put("type", "object");
        }
        return node;
    }

    /** 一轮运行内的工具上下文：城市定位和预报都缓存，避免模型重复调用把上游打两遍。 */
    private static final class ToolContext {
        private WeatherClient.Location location;
        private String lastCity;
        private WeatherClient.Forecast forecast;
        private String forecastCity;
        private int forecastDays;
    }

    private String execute(String name, JsonNode args, ToolContext context) {
        try {
            if ("resolve_city".equals(name)) {
                String city = args.path("city").asText("");
                WeatherClient.Location location = weatherClient.resolveCity(city);
                context.location = location;
                context.lastCity = location.city();
                return MAPPER.writeValueAsString(location);
            }
            if ("get_weather".equals(name)) {
                String city = args.path("city").asText("");
                if (city.isBlank() && context.lastCity != null) {
                    city = context.lastCity;
                }
                int days = args.path("days").asInt(7);
                days = Math.max(1, Math.min(7, days));
                WeatherClient.Forecast forecast;
                if (context.forecast != null && city.equals(context.forecastCity) && days <= context.forecastDays) {
                    forecast = context.forecast;
                } else {
                    forecast = weatherClient.forecast(city, days);
                    context.forecast = forecast;
                    context.forecastCity = city;
                    context.forecastDays = days;
                    context.location = forecast.location();
                }
                return MAPPER.writeValueAsString(forecast);
            }
            return "[错误] 未知工具：" + name;
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            return "[错误] " + message;
        }
    }

    private static JsonNode parseArgs(String arguments) {
        try {
            return MAPPER.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static String labelOf(String name) {
        return switch (name) {
            case "resolve_city" -> "定位城市";
            case "get_weather" -> "查询天气";
            default -> name;
        };
    }

    /** 轨迹只回给前端展示，截短避免把整段 JSON 塞进响应。 */
    private static String summarizeTrace(String name, String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        if (output.startsWith("[错误]")) {
            return output.substring("[错误] ".length()).trim();
        }
        try {
            JsonNode node = MAPPER.readTree(output);
            if ("resolve_city".equals(name)) {
                return node.path("city").asText("") + " · " + node.path("province").asText("");
            }
            JsonNode now = node.path("now");
            return "当前 " + now.path("temp").asText("") + "℃ · " + now.path("weather").asText("")
                    + " · 未来 " + node.path("days").size() + " 天";
        } catch (Exception e) {
            return output.length() > 120 ? output.substring(0, 120) + "…" : output;
        }
    }
}
