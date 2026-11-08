package com.iblog.module.stock.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * AI 对话的 Agent 循环。
 *
 * <p>一轮对话 = 模型流式输出 →（可能）要求调工具 → 本地执行 → 带着工具结果再问一次，
 * 直到模型不再调工具或达到轮数上限。所有工具都在服务端执行，浏览器只负责渲染，
 * 这样 API Key、上游请求头和问财 Cookie 都不会离开服务器。</p>
 *
 * <p>关键取舍：</p>
 * <ul>
 *   <li><b>不用异步</b>：Servlet 同步阻塞一个 Tomcat 线程直到对话结束。同一用户同时只开
 *       一个面板，线程占用可接受，换来的是不用管 SseEmitter 的超时/重连/内存泄漏。</li>
 *   <li><b>工具结果按需截断</b>：K 线、问财表这类输出动辄上万字符，全塞进上下文会让
 *       后面几轮贵到不可用，所以既按单条截断，又按总预算把最早的结果打成占位符。</li>
 *   <li><b>达到轮数上限用 user 消息兜底</b>：中途插 system 消息在 OpenAI 兼容实现里会被
 *       拒绝，只能以用户口吻要求模型收尾（桌面版踩过这个坑）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 最多来回几轮；再多基本是模型在兜圈子。 */
    private static final int MAX_ROUNDS = 10;
    /** 工具结果总字符预算，超出后把最早的替换成占位符。 */
    private static final int CONTEXT_CHAR_BUDGET = 48_000;
    /** 单条工具结果上限。 */
    private static final int TOOL_RESULT_MAX = 8_000;
    /** 工具结果省略后的占位文案（与桌面版一致，模型能看懂这是被裁掉了）。 */
    private static final String STUB = "[已省略：较早的工具结果，超出上下文预算]";
    /** 选股卡片的内部标记，见 {@link AiToolExecutors}。 */
    private static final String PICKS_MARKER = "__STOCK_PICKS__";

    private final AiConfigService configService;
    private final AiLlmClient llmClient;
    private final AiToolExecutors toolExecutors;
    private final AiContextBuilder contextBuilder;
    private final AiChatHistoryService historyService;

    /**
     * 事件出口。
     *
     * <p>抽象成接口而不是直接传 {@code HttpServletResponse}：写盘测试和以后换 SSE 实现
     * 都不用动这里的循环逻辑。</p>
     */
    public interface Sink {
        /** 推一个 SSE 事件；{@code data} 会被序列化成 JSON。 */
        void send(String event, Object data);
    }

    /**
     * 请求体。
     *
     * @param messages   历史消息（前端只回传已定稿的，正在输出的那条不传）
     * @param stockCode  当前股票代码，可空
     * @param stockName  当前股票名称，可空
     * @param iwencaiV   浏览器生成的问财凭证，可空（空时问财工具会提示刷新页面）
     * @param godMode    是否开启股神模式（四段式深度分析 + 逐步解释逻辑）
     */
    public record Request(List<Message> messages, String stockCode, String stockName, String iwencaiV,
                          Boolean godMode) {
    }

    /** 单条历史消息。 */
    public record Message(String role, String content) {
    }

    /** 浏览器关掉了面板/断开连接：写响应流失败时抛这个，用来把「正常结束」和真异常分开。 */
    public static class ClientGone extends RuntimeException {
        public ClientGone(Throwable cause) {
            super(cause);
        }
    }

    /**
     * 跑完一整轮对话，边跑边把事件推给前端。
     *
     * <p>异常一律转成 {@code error} 事件而不是抛出去：SSE 已经写了一半响应头，
     * 这里抛异常只会让前端收到一个断掉的流，没有可读的错误信息。</p>
     *
     * <p>{@code userId} 由控制器从登录态取，不从请求体读 —— 否则客户端可以伪造，
     * 把自己的对话写进别人的历史里。为 {@code null} 时跳过落库（单测直接调用的情况）。</p>
     */
    public void chat(Request request, Long userId, Sink sink) {
        AiConfigService.Config config = configService.load();
        if (!config.available()) {
            sink.send("error", event("error").put("message", "AI 未配置：请到管理后台 → AI 配置 填写 DeepSeek API Key"));
            return;
        }
        ArrayNode messages = buildMessages(request, config);
        ArrayNode tools = buildTools(config);
        AiToolExecutors.Context toolContext = new AiToolExecutors.Context(blankToNull(request.iwencaiV()));
        List<AiPickOut> picks = new ArrayList<>();
        // 落库用：模型可能分多轮输出，前端是把所有增量拼成一条消息，这里同样累积
        StringBuilder answer = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        List<ToolTrace> toolTraces = new ArrayList<>();
        Long sessionId = openSession(userId, request);
        // 本轮回答是否已落库：成功路径写完 done 后才断开的 ClientGone 不能重复保存
        boolean turnSaved = false;

        try {
            for (int round = 0; round < MAX_ROUNDS; round++) {
                if (round == MAX_ROUNDS - 1) {
                    messages.addObject()
                            .put("role", "user")
                            .put("content", "[系统提示] 已达到最大工具调用轮数(" + MAX_ROUNDS
                                    + ")，请立即停止调用工具，直接基于已有信息给出最终回答。");
                }
                RoundResult result = runRound(config, messages, tools, sink, answer, thinking);
                if (result.toolCalls.isEmpty()) {
                    finishTurn(sessionId, answer, thinking, toolTraces, picks);
                    turnSaved = true;
                    sink.send("done", event("done").set("picks", picksJson(picks)));
                    return;
                }
                appendAssistantTurn(messages, result);
                for (AiLlmClient.ToolCall call : result.toolCalls) {
                    String name = call.name() == null ? "" : call.name();
                    JsonNode args = parseArgs(call.arguments());
                    toolTraces.add(new ToolTrace(name, args));
                    sink.send("tool_call", event("tool_call")
                            .put("name", name)
                            .set("args", args));
                    String output = toolExecutors.execute(name, call.arguments(), toolContext);
                    if (output != null && output.startsWith(PICKS_MARKER)) {
                        // 选股卡片不进模型上下文：模型只需要知道「已展示」，否则会在正文里复读一遍
                        int count = collectPicks(output.substring(PICKS_MARKER.length()), picks);
                        output = "[已向用户展示选股卡片，共 " + count + " 只，无需在正文重复列表]";
                    }
                    output = truncate(output, TOOL_RESULT_MAX);
                    sink.send("tool_result", event("tool_result").put("name", name).put("result", output));
                    ObjectNode toolMessage = messages.addObject();
                    toolMessage.put("role", "tool");
                    toolMessage.put("tool_call_id", call.id() == null ? "" : call.id());
                    toolMessage.put("content", output);
                }
                compactOldToolResults(messages);
            }
            // 循环跑满仍未收敛：把已产出的内容当作最终结果收尾
            finishTurn(sessionId, answer, thinking, toolTraces, picks);
            turnSaved = true;
            sink.send("done", event("done").set("picks", picksJson(picks)));
        } catch (ClientGone e) {
            // 用户关掉面板或刷新页面：不是错误，也不需要再写事件；但已经吐出的部分要留下来。
            // 成功路径写完 done 才断开时 turnSaved 已置位，不能重复插一条回答。
            if (!turnSaved) {
                finishTurn(sessionId, answer, thinking, toolTraces, picks);
            }
            log.debug("AI 对话客户端已断开");
        } catch (AiLlmClient.AiException e) {
            log.warn("AI 对话失败 err={}", e.getMessage());
            if (!turnSaved) {
                finishTurn(sessionId, answer, thinking, toolTraces, picks);
            }
            sink.send("error", event("error").put("message", e.getMessage()));
        } catch (Exception e) {
            log.warn("AI 对话异常 err={}", e.toString());
            if (!turnSaved) {
                finishTurn(sessionId, answer, thinking, toolTraces, picks);
            }
            sink.send("error", event("error").put("message", "AI 分析失败：" + e.getMessage()));
        }
    }

    /**
     * 建立会话并落本轮提问。
     *
     * <p>失败只告警：历史是锦上添花，数据库抖动不该让用户连问题都问不了。</p>
     */
    private Long openSession(Long userId, Request request) {
        if (userId == null) {
            return null;
        }
        try {
            Long sessionId = historyService.touchSession(userId, request.stockCode(), request.stockName());
            historyService.saveUser(sessionId, lastUserMessage(request.messages()));
            return sessionId;
        } catch (Exception e) {
            log.warn("AI 对话历史初始化失败 err={}", e.toString());
            return null;
        }
    }

    /** 前端每次只把「已经定稿」的历史发上来，最后一条 user 就是本轮问题。 */
    private static String lastUserMessage(List<Message> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message != null && "user".equals(message.role())
                    && message.content() != null && !message.content().isBlank()) {
                return message.content();
            }
        }
        return null;
    }

    /** 保存本轮回答；工具轨迹与选股卡片按 JSON 存，刷新后前端据此还原界面。 */
    private void finishTurn(Long sessionId, StringBuilder answer, StringBuilder thinking,
                            List<ToolTrace> toolTraces, List<AiPickOut> picks) {
        if (sessionId == null) {
            return;
        }
        try {
            String toolsJson = toolTraces.isEmpty() ? "[]" : MAPPER.writeValueAsString(toolTraces);
            historyService.saveAssistant(sessionId, answer.toString(), thinking.toString(),
                    toolsJson, picksJson(picks).toString());
        } catch (Exception e) {
            log.warn("AI 对话历史保存失败 err={}", e.toString());
        }
    }

    /** 工具轨迹（落库用）：只留名字与参数，中文名由前端映射。 */
    private record ToolTrace(String name, JsonNode args) {
    }

    /** 一轮流式输出的结果。 */
    private static final class RoundResult {
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final List<AiLlmClient.ToolCall> toolCalls = new ArrayList<>();
    }

    /**
     * 跑一轮，把增量实时推给前端。
     *
     * <p>同时往 {@code answer} / {@code thinking} 里累积：客户端中途断开时
     * {@code runRound} 会抛异常、拿不到返回值，只有这里先累积，已经推出去的半截回答才落得下来。</p>
     */
    private RoundResult runRound(AiConfigService.Config config, ArrayNode messages, ArrayNode tools, Sink sink,
                                 StringBuilder answer, StringBuilder thinking) {
        RoundResult result = new RoundResult();
        llmClient.stream(config.apiKey(), config.model(), messages, tools,
                config.thinkingEnabled(), config.reasoningEffort(),
                new AiLlmClient.Listener() {
                    @Override
                    public void onReasoning(String delta) {
                        if (delta == null || delta.isEmpty()) {
                            return;
                        }
                        result.reasoning.append(delta);
                        thinking.append(delta);
                        sink.send("reasoning", event("reasoning").put("text", delta));
                    }

                    @Override
                    public void onContent(String delta) {
                        if (delta == null || delta.isEmpty()) {
                            return;
                        }
                        result.content.append(delta);
                        answer.append(delta);
                        sink.send("content", event("content").put("text", delta));
                    }

                    @Override
                    public void onToolCalls(List<AiLlmClient.ToolCall> calls) {
                        if (calls != null) {
                            result.toolCalls.addAll(calls);
                        }
                    }
                });
        return result;
    }

    /** 把本轮 assistant 输出（含 tool_calls）追加进历史，下一轮模型才能看到自己调了什么。 */
    private void appendAssistantTurn(ArrayNode messages, RoundResult result) {
        ObjectNode assistant = messages.addObject();
        assistant.put("role", "assistant");
        assistant.put("content", result.content.toString());
        // reasoning_content 不回传：DeepSeek 明确要求 reasoning 不要塞回历史
        ArrayNode calls = assistant.putArray("tool_calls");
        for (AiLlmClient.ToolCall call : result.toolCalls) {
            ObjectNode item = calls.addObject();
            item.put("id", call.id() == null ? "" : call.id());
            item.put("type", "function");
            ObjectNode function = item.putObject("function");
            function.put("name", call.name() == null ? "" : call.name());
            function.put("arguments", call.arguments() == null ? "{}" : call.arguments());
        }
    }

    /** system + 历史。 */
    private ArrayNode buildMessages(Request request, AiConfigService.Config config) {
        ArrayNode messages = MAPPER.createArrayNode();
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", contextBuilder.build(request.stockCode(), request.stockName(),
                config.webSearchEnabled(), skillsPrompt(config),
                Boolean.TRUE.equals(request.godMode())));
        if (request.messages() != null) {
            for (Message message : request.messages()) {
                if (message == null || message.content() == null || message.content().isBlank()) {
                    continue;
                }
                String role = message.role() == null ? "user" : message.role();
                if (!"user".equals(role) && !"assistant".equals(role)) {
                    continue;
                }
                ObjectNode node = messages.addObject();
                node.put("role", role);
                node.put("content", message.content());
            }
        }
        return messages;
    }

    /** 各 skill 的能力说明（同一 skill 的多个工具文本相同，去重后拼接）。 */
    private String skillsPrompt(AiConfigService.Config config) {
        Set<String> prompts = new LinkedHashSet<>();
        for (AiToolSchemas.ToolDef def : AiToolSchemas.TOOLS) {
            if (!config.webSearchEnabled() && isWebTool(def.name())) {
                continue;
            }
            if (def.systemPrompt() != null && !def.systemPrompt().isBlank()) {
                prompts.add(def.systemPrompt().trim());
            }
        }
        return String.join("\n\n", prompts);
    }

    /** OpenAI function-calling 格式的工具数组。 */
    private ArrayNode buildTools(AiConfigService.Config config) {
        ArrayNode tools = MAPPER.createArrayNode();
        for (AiToolSchemas.ToolDef def : AiToolSchemas.TOOLS) {
            if (!config.webSearchEnabled() && isWebTool(def.name())) {
                continue;
            }
            ObjectNode tool = tools.addObject();
            tool.put("type", "function");
            ObjectNode function = tool.putObject("function");
            function.put("name", def.name());
            function.put("description", def.description());
            function.set("parameters", parseArgs(def.parametersJson()));
        }
        return tools;
    }

    private static boolean isWebTool(String name) {
        return "web_search".equals(name) || "web_fetch".equals(name);
    }

    /**
     * 上下文预算控制：从最早的工具消息开始替换成占位符，直到总量回到预算内。
     *
     * <p>只裁 tool 消息，不动 user/assistant：用户的问题和模型自己的回答是对话主线，
     * 裁掉会让模型忘记刚才聊到哪。</p>
     */
    private void compactOldToolResults(ArrayNode messages) {
        int total = 0;
        for (JsonNode node : messages) {
            total += node.path("content").asText("").length();
        }
        if (total <= CONTEXT_CHAR_BUDGET) {
            return;
        }
        for (JsonNode node : messages) {
            if (total <= CONTEXT_CHAR_BUDGET) {
                return;
            }
            if (!"tool".equals(node.path("role").asText(""))) {
                continue;
            }
            ObjectNode tool = (ObjectNode) node;
            String content = tool.path("content").asText("");
            if (content.equals(STUB)) {
                continue;
            }
            total -= content.length();
            total += STUB.length();
            tool.put("content", STUB);
        }
    }

    /** 选股卡片累计（同一轮多次调用只保留最后一次，避免重复卡片）。 */
    private int collectPicks(String payload, List<AiPickOut> picks) {
        picks.clear();
        int count = 0;
        try {
            JsonNode array = MAPPER.readTree(payload);
            if (array != null && array.isArray()) {
                for (JsonNode item : array) {
                    picks.add(new AiPickOut(
                            item.path("code").asText(""),
                            item.path("name").asText(""),
                            item.hasNonNull("price") ? item.path("price").asDouble() : null,
                            item.hasNonNull("changePct") ? item.path("changePct").asDouble() : null,
                            item.path("reason").asText("")));
                    count++;
                }
            }
        } catch (Exception e) {
            log.warn("选股卡片解析失败 err={}", e.toString());
        }
        return count;
    }

    private ArrayNode picksJson(List<AiPickOut> picks) {
        ArrayNode array = MAPPER.createArrayNode();
        for (AiPickOut pick : picks) {
            ObjectNode node = array.addObject();
            node.put("code", pick.code());
            node.put("name", pick.name());
            if (pick.price() != null) {
                node.put("price", pick.price());
            }
            if (pick.changePct() != null) {
                node.put("changePct", pick.changePct());
            }
            node.put("reason", pick.reason());
        }
        return array;
    }

    /** 选股卡片（与前端 AiPick 形状一致，price/changePct 可空）。 */
    private record AiPickOut(String code, String name, Double price, Double changePct, String reason) {
    }

    private static ObjectNode event(String type) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", type);
        return node;
    }

    private static JsonNode parseArgs(String json) {
        if (json == null || json.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return node;
        } catch (Exception e) {
            // 工具参数是模型拼的，偶尔截断；原样给出去，执行器会当空参数处理
            return MAPPER.getNodeFactory().textNode(json);
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…[已截断]";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
