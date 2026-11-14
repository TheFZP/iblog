package com.iblog.module.tool.weather;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.iblog.module.stock.ai.AiConfigService;
import com.iblog.module.stock.ai.AiLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 主循环测试：模型第一轮调工具、第二轮给正文，工具结果要能回到模型，
 * 轨迹要能回给前端。这里不测真实网络，只验证编排。
 */
class WeatherAgentServiceTest {

    private final WeatherClient weatherClient = mock(WeatherClient.class);
    private final AiConfigService configService = mock(AiConfigService.class);
    private final AiLlmClient llmClient = mock(AiLlmClient.class);
    private final WeatherAgentService service =
            new WeatherAgentService(weatherClient, configService, llmClient);

    private static AiConfigService.Config configured() {
        return new AiConfigService.Config(true, "key", "deepseek-v4-flash", false, "high", false);
    }

    private static WeatherClient.Forecast sampleForecast() {
        WeatherClient.Location location = new WeatherClient.Location("101280101", "广州市", "广东");
        WeatherClient.Now now = new WeatherClient.Now(23.2, "多云", 26, 20, "93%",
                6.0, "优", 35, "北风", "2级", "06:39", "17:42");
        WeatherClient.Day day = new WeatherClient.Day("2026-11-16", "星期一", 27, 22,
                "小雨", "东南风", "2级", 47, "雨虽小", "06:40", "17:42");
        return new WeatherClient.Forecast(location, "2026-11-14", "11:48", now, List.of(day));
    }

    @Test
    void agentCallsToolThenAnswers() {
        when(configService.load()).thenReturn(configured());
        when(weatherClient.forecast(anyString(), anyInt())).thenReturn(sampleForecast());

        AtomicInteger round = new AtomicInteger();
        doAnswer(invocation -> {
            AiLlmClient.Listener listener = invocation.getArgument(7);
            if (round.getAndIncrement() == 0) {
                listener.onToolCalls(List.of(new AiLlmClient.ToolCall(
                        "call_1", "get_weather", "{\"city\":\"广州\",\"days\":7}")));
            } else {
                listener.onContent("明天有小雨，出门记得带伞。");
                listener.onToolCalls(List.of());
            }
            return null;
        }).when(llmClient).stream(anyString(), anyString(), any(ArrayNode.class), any(ArrayNode.class),
                anyBoolean(), isNull(), anyInt(), any(AiLlmClient.Listener.class));

        WeatherAgentService.Result result = service.analyze("广州", "明天适合跑步吗");

        assertTrue(result.available());
        assertEquals("明天有小雨，出门记得带伞。", result.answer());
        assertEquals(1, result.traces().size());
        assertEquals("get_weather", result.traces().get(0).name());
        assertEquals("查询天气", result.traces().get(0).label());
        assertTrue(result.traces().get(0).ok());
        verify(weatherClient).forecast("广州", 7);
    }

    @Test
    void toolFailureIsHandedBackToModel() {
        when(configService.load()).thenReturn(configured());
        when(weatherClient.forecast(anyString(), anyInt()))
                .thenThrow(new RuntimeException("天气数据暂时取不到"));

        AtomicInteger round = new AtomicInteger();
        doAnswer(invocation -> {
            AiLlmClient.Listener listener = invocation.getArgument(7);
            if (round.getAndIncrement() == 0) {
                listener.onToolCalls(List.of(new AiLlmClient.ToolCall(
                        "call_1", "get_weather", "{\"city\":\"火星\"}")));
            } else {
                listener.onContent("抱歉，暂时查不到这个城市的天气。");
                listener.onToolCalls(List.of());
            }
            return null;
        }).when(llmClient).stream(anyString(), anyString(), any(ArrayNode.class), any(ArrayNode.class),
                anyBoolean(), isNull(), anyInt(), any(AiLlmClient.Listener.class));

        WeatherAgentService.Result result = service.analyze("火星", "");

        assertEquals("抱歉，暂时查不到这个城市的天气。", result.answer());
        assertEquals(1, result.traces().size());
        assertFalse(result.traces().get(0).ok());
        assertTrue(result.traces().get(0).result().startsWith("天气数据暂时取不到"));
    }

    @Test
    void availableFollowsConfiguredKey() {
        when(configService.load()).thenReturn(configured());
        assertTrue(service.available());

        when(configService.load()).thenReturn(new AiConfigService.Config(
                false, null, "deepseek-v4-flash", true, "high", true));
        assertFalse(service.available());
    }

    @Test
    void missingKeyDoesNotCallModel() {
        when(configService.load()).thenReturn(new AiConfigService.Config(
                false, null, "deepseek-v4-flash", true, "high", true));

        WeatherAgentService.Result result = service.analyze("广州", "");

        assertFalse(result.available());
        assertTrue(result.message().contains("AI 未配置"));
        verify(llmClient, never()).stream(anyString(), anyString(), any(ArrayNode.class), any(),
                anyBoolean(), any(), anyInt(), any(AiLlmClient.Listener.class));
    }
}
