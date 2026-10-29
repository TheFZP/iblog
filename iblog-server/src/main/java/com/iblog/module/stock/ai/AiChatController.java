package com.iblog.module.stock.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.iblog.common.exception.BizException;
import com.iblog.common.result.R;
import com.iblog.module.stock.entity.StockSetting;
import com.iblog.module.stock.service.StockSettingService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * AI 分析接口。
 *
 * <p>挂在 {@code /api/stock/} 下面是有意的：{@code AuthInterceptor.allowsCookie}
 * 只对这个前缀放行 Cookie，官网页面的 fetch 才能靠后台登录态直接调用，
 * 不必把 JWT 暴露给浏览器脚本。</p>
 *
 * <p>对话接口手写 SSE 而不是用 {@code SseEmitter}：项目是 Servlet 栈、没有 WebFlux，
 * 而这里需要「模型吐一个字就立刻写出去」，直接拿 {@code response.getWriter()}
 * 是最短路径；也正因为没走 MVC 的返回值通道，全局的 {@code R} 包装不会介入。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/stock/ai")
@RequiredArgsConstructor
public class AiChatController {

    /** 画像在 stock_setting 里的键，与 AI 工具 {@code save_user_profile} 用的是同一个。 */
    private static final String KEY_USER_PROFILE = "ai_user_profile";
    /** 画像长度上限，防止一次贴进整本书把库里的 TEXT 列和上下文一起撑爆。 */
    private static final int PROFILE_MAX_LENGTH = 20000;

    private final AiConfigService configService;
    private final AiChatService chatService;
    private final IwencaiClient iwencaiClient;
    private final StockSettingService settingService;

    /** 面板初始化：模型名、是否配好 Key、是否开联网搜索。/**/
    @GetMapping("/config")
    public R<Map<String, Object>> config() {
        return R.ok(configService.publicView());
    }

    /**
     * 流式对话。
     *
     * <p>事件格式：{@code event: <type>\ndata: <json>\n\n}，type 取
     * reasoning/content/tool_call/tool_result/done/error。前端据此逐块渲染。</p>
     */
    @PostMapping("/chat")
    public void chat(@RequestBody AiChatService.Request request, HttpServletResponse response) {
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache, no-transform");
        // 关掉 nginx 缓冲，否则事件会被攒成一批才下发，打字机效果就没了
        response.setHeader("X-Accel-Buffering", "no");
        PrintWriter writer;
        try {
            writer = response.getWriter();
        } catch (IOException e) {
            log.warn("AI 对话无法获取响应输出流 err={}", e.toString());
            return;
        }
        chatService.chat(request, (event, data) -> {
            try {
                writer.write("event: " + event + "\n");
                writer.write("data: " + toJson(data) + "\n\n");
                writer.flush();
            } catch (Exception e) {
                // 浏览器关掉面板时写会失败，这属于正常结束，不该把循环打断成异常日志
                throw new AiChatService.ClientGone(e);
            }
        });
    }

    /** 问财选股代理：浏览器负责生成 v，服务端负责带齐反爬头转发。 */
    @PostMapping("/iwencai")
    public R<IwencaiClient.IwencaiResult> iwencai(@RequestBody IwencaiRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new BizException(400, "请输入选股条件");
        }
        try {
            return R.ok(iwencaiClient.query(request.question().trim(),
                    request.page() == null ? 1 : request.page(),
                    request.perPage() == null ? 50 : request.perPage(),
                    request.v()));
        } catch (IwencaiClient.IwencaiException e) {
            // 限流要让前端能认出「换 v 重试」，所以结构化回一个 429 而不是普通 400
            throw new BizException(e.rateLimited() ? 429 : 400, e.getMessage());
        }
    }

    /** 问财请求体。 */
    public record IwencaiRequest(String question, Integer page, Integer perPage, String v) {
    }

    /**
     * 读取用户投资画像。
     *
     * <p>没填过时返回空串而不是 404：页面拿到就能直接渲染空白编辑器，少一个分支。
     * 键与 AI 工具 {@code save_user_profile} 用的是同一个，所以 AI 自己写的画像
     * 和用户在页面上存的画像是同一行数据，互相可见。</p>
     */
    @GetMapping("/profile")
    public R<ProfileResponse> profile() {
        // 一次查询拿整行：content 与 updatedAt 本就在同一行，分两次查只是多一次往返
        StockSetting row = settingService.getSetting(KEY_USER_PROFILE);
        if (row == null || row.getConfigValue() == null || row.getConfigValue().isBlank()) {
            return R.ok(new ProfileResponse("", null));
        }
        return R.ok(new ProfileResponse(row.getConfigValue(), row.getUpdateTime()));
    }

    /**
     * 保存用户投资画像。
     *
     * <p>{@code content} 传空白等于清除画像（{@code StockSettingService.put} 对空值走真删），
     * 这与「清空编辑器再保存」的直觉一致。长度上限防的是一次贴进整本书把
     * {@code config_value} 的 TEXT 列和后续 prompt 一起撑爆。</p>
     */
    @PutMapping("/profile")
    public R<Void> saveProfile(@RequestBody ProfileRequest request) {
        String content = request == null || request.content() == null ? "" : request.content();
        if (content.length() > PROFILE_MAX_LENGTH) {
            throw new BizException(400, "画像内容过长（最多 20000 字）");
        }
        settingService.put(KEY_USER_PROFILE, content, false, "AI 用户投资画像");
        return R.ok();
    }

    /** 画像响应体：{@code content} 永不为 null；没存过时 {@code updatedAt} 为 null。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ProfileResponse(String content, LocalDateTime updatedAt) {
    }

    /** 画像保存请求体。 */
    public record ProfileRequest(String content) {
    }

    private static String toJson(Object data) {
        try {
            return AiJson.MAPPER.writeValueAsString(data);
        } catch (Exception e) {
            return "{\"type\":\"error\",\"message\":\"事件序列化失败\"}";
        }
    }
}
