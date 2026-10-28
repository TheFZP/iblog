package com.iblog.module.stock.ai;

import com.iblog.common.exception.BizException;
import com.iblog.common.result.R;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
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

    private final AiConfigService configService;
    private final AiChatService chatService;
    private final IwencaiClient iwencaiClient;

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

    private static String toJson(Object data) {
        try {
            return AiJson.MAPPER.writeValueAsString(data);
        } catch (Exception e) {
            return "{\"type\":\"error\",\"message\":\"事件序列化失败\"}";
        }
    }
}
