package com.iblog.module.stock.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DeepSeek（OpenAI 兼容协议）流式对话客户端。
 *
 * <p><b>为什么不用官方 SDK / Spring AI</b>：本模块只需要「POST 一个 JSON、按 SSE 读回来」
 * 这一件事，引入 SDK 会带来一堆自动配置和版本约束，而项目已经是 Servlet 栈、
 * 没有 WebFlux，SDK 的响应式入口反而不合用。</p>
 *
 * <p><b>SSE 解码为什么要按行读而不是按 chunk 切</b>：网络分片会把一个汉字劈成两半
 * （UTF-8 三字节跨 chunk），按 chunk 直接 {@code new String(bytes)} 会得到乱码 U+FFFD。
 * 用 {@link InputStreamReader} 包着按行读，解码器自己会处理跨片的多字节字符。</p>
 */
@Slf4j
@Service
public class AiLlmClient {

    /** DeepSeek 的 OpenAI 兼容端点。 */
    static final String ENDPOINT = "https://api.deepseek.com/chat/completions";

    /** 单次请求的输出上限。 */
    static final int MAX_TOKENS = 8192;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** 读超时：流式下这是「两次数据之间」的最大间隔，不是总时长。 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(120);

    private final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    /**
     * 流式调用回调。
     *
     * <p>为什么是回调而不是返回一个 Flux：Servlet 栈里最终要把事件直接写进
     * {@code HttpServletResponse}，边读边写才能做到「模型吐一个字前端就显示一个字」；
     * 返回集合再处理等于把流式退化成非流式。</p>
     */
    public interface Listener {

        /** 思维链增量（deepseek reasoning 模型才有）。 */
        void onReasoning(String delta);

        /** 正文增量。 */
        void onContent(String delta);

        /** 一轮结束时累积好的工具调用（可能为空列表）。 */
        void onToolCalls(List<ToolCall> calls);
    }

    /** 模型发起的一次工具调用。 */
    public record ToolCall(String id, String name, String arguments) {
    }

    /**
     * 发起一次流式补全。
     *
     * @param apiKey   模型 Key（只从数据库读，绝不来自请求体）
     * @param model    模型名
     * @param messages OpenAI 格式的消息数组
     * @param tools    工具定义（可为空表示不要工具）
     * @param thinking 是否开启思考
     * @param effort   推理强度 low/medium/high
     * @param listener 增量回调
     */
    public void stream(String apiKey, String model, ArrayNode messages, ArrayNode tools,
                       boolean thinking, String effort, Listener listener) throws AiException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.set("messages", messages);
        body.put("stream", true);
        body.put("max_tokens", MAX_TOKENS);
        if (thinking) {
            body.put("reasoning_effort", effort == null ? AiConfigService.DEFAULT_EFFORT : effort);
        } else {
            // 关闭思考时给低温度，回答更稳（与桌面版口径一致）
            body.put("temperature", 0.7);
        }
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", tools);
            body.put("tool_choice", "auto");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .timeout(READ_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<java.io.InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("AI 请求被中断");
        } catch (Exception e) {
            log.warn("调用模型失败 err={}", e.toString());
            throw new AiException("无法连接模型服务：" + e.getMessage());
        }

        if (response.statusCode() != 200) {
            String detail = readQuietly(response.body());
            log.warn("模型返回非 200 status={} body={}", response.statusCode(), truncate(detail, 500));
            throw new AiException(explainStatus(response.statusCode(), detail));
        }

        parseStream(response.body(), listener);
    }

    /** 解析 SSE 流并回调。 */
    private void parseStream(java.io.InputStream stream, Listener listener) throws AiException {
        // 工具调用是按 index 增量下发的：id/name 只在第一片出现，arguments 是逐片拼接的字符串
        Map<Integer, StringBuilder> argBuffers = new java.util.TreeMap<>();
        Map<Integer, String> idMap = new java.util.TreeMap<>();
        Map<Integer, String> nameMap = new java.util.TreeMap<>();
        AtomicBoolean finished = new AtomicBoolean(false);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        if (handleEvent(data.toString(), listener, argBuffers, idMap, nameMap)) {
                            finished.set(true);
                            break;
                        }
                        data.setLength(0);
                    }
                    continue;
                }
                if (line.startsWith("data:")) {
                    String value = line.substring(5);
                    if (value.startsWith(" ")) {
                        value = value.substring(1);
                    }
                    data.append(value);
                }
                // event: / id: / : heartbeat 三类行对本协议无意义，忽略
            }
            // 流意外结束时把残留数据也处理掉，避免丢掉最后一小段正文
            if (!finished.get() && data.length() > 0) {
                handleEvent(data.toString(), listener, argBuffers, idMap, nameMap);
            }
        } catch (java.io.IOException e) {
            throw new AiException("读取模型响应失败：" + e.getMessage());
        }

        List<ToolCall> calls = new java.util.ArrayList<>();
        for (Map.Entry<Integer, StringBuilder> entry : argBuffers.entrySet()) {
            int index = entry.getKey();
            calls.add(new ToolCall(
                    idMap.getOrDefault(index, "call_" + index),
                    nameMap.getOrDefault(index, ""),
                    entry.getValue().toString()));
        }
        listener.onToolCalls(calls);
    }

    /**
     * 处理一条 SSE data。
     *
     * @return true 表示收到 [DONE]，应停止读取
     */
    private boolean handleEvent(String data, Listener listener,
                                Map<Integer, StringBuilder> argBuffers,
                                Map<Integer, String> idMap,
                                Map<Integer, String> nameMap) throws AiException {
        String payload = data.trim();
        if (payload.isEmpty()) {
            return false;
        }
        if ("[DONE]".equals(payload)) {
            return true;
        }
        JsonNode root;
        try {
            root = mapper.readTree(payload);
        } catch (Exception e) {
            // 单个坏片不该把整轮对话打死
            log.warn("跳过无法解析的流事件 {}", truncate(payload, 200));
            return false;
        }
        if (root.hasNonNull("error")) {
            JsonNode error = root.get("error");
            String message = error.path("message").asText("模型服务返回错误");
            throw new AiException(message);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return false;
        }
        JsonNode delta = choices.get(0).path("delta");
        if (delta.hasNonNull("reasoning_content")) {
            String reasoning = delta.get("reasoning_content").asText();
            if (!reasoning.isEmpty()) {
                listener.onReasoning(reasoning);
            }
        }
        if (delta.hasNonNull("content")) {
            String content = delta.get("content").asText();
            if (!content.isEmpty()) {
                listener.onContent(content);
            }
        }
        JsonNode toolCalls = delta.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode call : toolCalls) {
                int index = call.path("index").asInt(0);
                if (call.hasNonNull("id")) {
                    idMap.put(index, call.get("id").asText());
                }
                JsonNode function = call.path("function");
                if (function.hasNonNull("name")) {
                    nameMap.put(index, function.get("name").asText());
                }
                if (function.hasNonNull("arguments")) {
                    argBuffers.computeIfAbsent(index, k -> new StringBuilder())
                            .append(function.get("arguments").asText());
                }
            }
        }
        return false;
    }

    private static String explainStatus(int status, String detail) {
        if (status == 401 || status == 403) {
            return "AI Key 无效或已过期，请到后台重新填写（HTTP " + status + "）";
        }
        if (status == 429) {
            return "模型服务限流或余额不足，请稍后再试（HTTP 429）";
        }
        if (status >= 500) {
            return "模型服务暂时不可用（HTTP " + status + "），请稍后重试";
        }
        String snippet = truncate(detail == null ? "" : detail.replaceAll("\\s+", " "), 200);
        return "模型返回 HTTP " + status + (snippet.isEmpty() ? "" : "：" + snippet);
    }

    private static String readQuietly(java.io.InputStream stream) {
        try (java.io.InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** AI 调用异常，由上层转成对前端可见的中文提示。 */
    public static class AiException extends RuntimeException {
        public AiException(String message) {
            super(message);
        }
    }
}
