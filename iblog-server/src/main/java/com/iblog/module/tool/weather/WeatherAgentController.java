package com.iblog.module.tool.weather;

import com.iblog.common.exception.BizException;
import com.iblog.common.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 工具箱「AI 天气」接口（公开访问）。
 *
 * <p>预报接口只读上游、不花模型的钱，限额放宽；Agent 接口每次都会调用模型，
 * 按 IP 每分钟 12 次防刷，全站每天另有「1 元预算」折算出的次数上限。放在 {@code /api/tools} 而不是 {@code /api/stock} 下，
 * 是因为后者被 {@code AuthInterceptor} 整体要求登录，而工具箱是公开页面。</p>
 */
@Tag(name = "工具箱-天气")
@RestController
@RequestMapping("/api/tools/weather")
@RequiredArgsConstructor
public class WeatherAgentController {

    private static final int FORECAST_PER_MINUTE = 60;
    private static final int AGENT_PER_MINUTE = 12;

    /**
     * 全站每天的 AI 建议上限：按「一天最多花 1 块钱」折算成次数。
     *
     * <p>为什么用次数而不是直接算钱：单次调用的 token 用量拿不到可靠的模型单价，
     * 算出来的金额只是估数，反而不好解释。一次完整调用最多 3 轮、每轮输出上限 700 token，
     * 按常见 DeepSeek 价格（输入约 2 元/百万、输出约 8 元/百万）估算单次不超过 0.03 元，
     * 20 次/天约 0.6 元，稳定压在 1 元以内。想放宽也别超过 30 次/天。</p>
     *
     * <p>额度按自然日重置（key 里带日期），并作用于所有 IP——这是钱的上限，
     * 不能因为换 IP 就重来；单 IP 的防刷由 {@link #AGENT_PER_MINUTE} 负责。</p>
     */
    private static final int AGENT_PER_DAY = 20;
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final WeatherClient weatherClient;
    private final WeatherAgentService agentService;
    private final SimpleRateLimiter limiter = new SimpleRateLimiter();

    /** 结构化预报 + 一段规则摘要（不调用模型）。 */
    @Operation(summary = "城市天气预报")
    @GetMapping("/forecast")
    public R<ForecastResponse> forecast(
            @RequestParam(defaultValue = "广州") String city,
            @RequestParam(defaultValue = "7") int days,
            HttpServletRequest request) {
        checkLimit(request, "forecast", FORECAST_PER_MINUTE);
        WeatherClient.Forecast forecast = weatherClient.forecast(city, days);
        return R.ok(new ForecastResponse(forecast, WeatherClient.summarize(forecast)));
    }

    /** 让天气 Agent 调用工具查数据并给出建议。 */
    @Operation(summary = "AI 天气建议")
    @PostMapping("/agent")
    public R<WeatherAgentService.Result> agent(@RequestBody(required = false) AgentRequest body,
                                               HttpServletRequest request) {
        String city = body == null || body.city() == null ? "" : body.city().trim();
        String question = body == null || body.question() == null ? "" : body.question().trim();
        if (city.isEmpty()) {
            throw new BizException(400, "请输入城市名");
        }
        checkLimit(request, "agent", AGENT_PER_MINUTE);
        // 没配 Key 时根本不会花钱，不该白白占掉当天的额度。
        if (agentService.available() && !limiter.allow("agent:day:" + LocalDate.now(ZONE), AGENT_PER_DAY, DAY_MS)) {
            throw new BizException(429, "今天的 AI 建议次数已用完（每天 " + AGENT_PER_DAY + " 次），明天再来");
        }
        return R.ok(agentService.analyze(city, question));
    }

    private void checkLimit(HttpServletRequest request, String scene, int maxPerMinute) {
        String ip = clientIp(request);
        if (!limiter.allow(scene + ":" + ip, maxPerMinute, 60_000)) {
            throw new BizException(429, "请求太频繁了，休息一分钟再试");
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String real = request.getHeader("X-Real-IP");
        return real != null && !real.isBlank() ? real.trim() : request.getRemoteAddr();
    }

    public record AgentRequest(String city, String question) {
    }

    public record ForecastResponse(WeatherClient.Forecast forecast, String summary) {
    }
}
