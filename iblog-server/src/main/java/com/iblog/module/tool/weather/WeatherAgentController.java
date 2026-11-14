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

/**
 * 工具箱「AI 天气」接口（公开访问）。
 *
 * <p>预报接口只读上游、不花模型的钱，限额放宽；Agent 接口每次都会调用模型，
 * 按 IP 每分钟 12 次兜底。放在 {@code /api/tools} 而不是 {@code /api/stock} 下，
 * 是因为后者被 {@code AuthInterceptor} 整体要求登录，而工具箱是公开页面。</p>
 */
@Tag(name = "工具箱-天气")
@RestController
@RequestMapping("/api/tools/weather")
@RequiredArgsConstructor
public class WeatherAgentController {

    private static final int FORECAST_PER_MINUTE = 60;
    private static final int AGENT_PER_MINUTE = 12;
    /** 公开页面兜底：就算有人换 IP 刷，也不至于把模型额度一天烧光。 */
    private static final int AGENT_PER_DAY = 300;

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
        if (!limiter.allow("agent:global", AGENT_PER_DAY, 24 * 60 * 60 * 1000L)) {
            throw new BizException(429, "今天的 AI 建议次数已用完，明天再来");
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
