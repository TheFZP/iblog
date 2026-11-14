package com.iblog.module.tool.weather;

import com.iblog.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 每日花费上限测试：额度是全站共享的，换 IP 也不能绕过。
 *
 * <p>每次调用用一个不同的 IP，避免先撞上「单 IP 每分钟 12 次」，
 * 这样断言到的就是自然日的次数上限本身。</p>
 */
class WeatherAgentControllerTest {

    private final WeatherAgentService agentService = mock(WeatherAgentService.class);
    private final WeatherAgentController controller =
            new WeatherAgentController(mock(WeatherClient.class), agentService);

    private static HttpServletRequest requestFrom(String ip) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(ip);
        return request;
    }

    private static WeatherAgentService.Result answer() {
        return new WeatherAgentService.Result(true, "明天有小雨", "", List.of(), "deepseek-v4-flash", 1200);
    }

    @Test
    void dailyQuotaIsSharedAcrossIps() {
        when(agentService.available()).thenReturn(true);
        when(agentService.analyze(anyString(), anyString())).thenReturn(answer());

        for (int i = 0; i < 20; i++) {
            WeatherAgentService.Result result = controller
                    .agent(new WeatherAgentController.AgentRequest("广州", ""), requestFrom("10.0.0." + i))
                    .getData();
            assertEquals("明天有小雨", result.answer());
        }

        BizException error = assertThrows(BizException.class, () -> controller
                .agent(new WeatherAgentController.AgentRequest("广州", ""), requestFrom("10.0.0.99")));
        assertEquals(429, error.getCode());
        assertTrue(error.getMessage().contains("每天 20 次"));
    }

    @Test
    void unconfiguredAiDoesNotConsumeQuota() {
        when(agentService.available()).thenReturn(false);
        when(agentService.analyze(anyString(), anyString())).thenReturn(
                new WeatherAgentService.Result(false, "", "AI 未配置", List.of(), "", 0));

        // 没配 Key 不会花钱，跑满 20 次之后仍然应该能继续响应提示
        for (int i = 0; i < 25; i++) {
            WeatherAgentService.Result result = controller
                    .agent(new WeatherAgentController.AgentRequest("广州", ""), requestFrom("10.0.1." + i))
                    .getData();
            assertEquals("AI 未配置", result.message());
        }
    }

    @Test
    void blankCityIsRejectedBeforeQuota() {
        BizException error = assertThrows(BizException.class, () -> controller
                .agent(new WeatherAgentController.AgentRequest(" ", ""), requestFrom("10.0.2.1")));
        assertEquals(400, error.getCode());
    }
}
