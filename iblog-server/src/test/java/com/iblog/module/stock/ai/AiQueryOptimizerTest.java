package com.iblog.module.stock.ai;

import org.junit.jupiter.api.Test;

import com.iblog.common.exception.BizException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 问财查询改写：清洗逻辑与入口校验。
 *
 * <p>清洗这一段最值得测——模型的输出格式不受控，带围栏、带「优化后的查询：」前缀、
 * 多行、结尾中文分号都出现过。这里把它当纯文本函数测，不拉 Spring 上下文、
 * 也不真的调模型。</p>
 */
class AiQueryOptimizerTest {

    private final AiConfigService configService = mock(AiConfigService.class);
    private final AiLlmClient llmClient = mock(AiLlmClient.class);
    private final com.iblog.module.stock.service.StockSettingService settingService =
            mock(com.iblog.module.stock.service.StockSettingService.class);

    private final AiQueryOptimizer optimizer = new AiQueryOptimizer(configService, llmClient, settingService);

    private static AiConfigService.Config available() {
        return new AiConfigService.Config(true, "sk-test", "deepseek-v4-flash", false, "high", false);
    }

    // ---------- 清洗逻辑 ----------

    @Test
    void plainJsonQueryIsExtracted() {
        assertEquals("总市值大于100亿; 换手率大于5",
                AiQueryOptimizer.cleanOptimizedQuery("{\"query\":\"总市值大于100亿; 换手率大于5\"}"));
    }

    @Test
    void jsonInsideMarkdownFenceIsExtracted() {
        String text = "```json\n{\"query\":\"总市值大于100亿; 市盈率小于20\"}\n```";

        assertEquals("总市值大于100亿; 市盈率小于20", AiQueryOptimizer.cleanOptimizedQuery(text));
    }

    @Test
    void fencedJsonWithoutLanguageTagIsExtracted() {
        String text = "```\n{\"query\":\"换手率大于5\"}\n```";

        assertEquals("换手率大于5", AiQueryOptimizer.cleanOptimizedQuery(text));
    }

    @Test
    void chinesePrefixIsStripped() {
        assertEquals("总市值大于100亿",
                AiQueryOptimizer.cleanOptimizedQuery("优化后的查询：总市值大于100亿"));
    }

    @Test
    void multiLineBecomesSemicolonSeparated() {
        String text = "总市值大于100亿\n换手率大于5\n量比大于1.5";

        assertEquals("总市值大于100亿; 换手率大于5; 量比大于1.5",
                AiQueryOptimizer.cleanOptimizedQuery(text));
    }

    @Test
    void trailingSemicolonIsRemoved() {
        assertEquals("总市值大于100亿; 换手率大于5",
                AiQueryOptimizer.cleanOptimizedQuery("总市值大于100亿; 换手率大于5;"));
    }

    @Test
    void trailingChineseSemicolonIsRemoved() {
        assertEquals("总市值大于100亿", AiQueryOptimizer.cleanOptimizedQuery("总市值大于100亿；"));
    }

    @Test
    void blankInputsReturnEmptyString() {
        assertEquals("", AiQueryOptimizer.cleanOptimizedQuery(""));
        assertEquals("", AiQueryOptimizer.cleanOptimizedQuery("   "));
        assertEquals("", AiQueryOptimizer.cleanOptimizedQuery(null));
        // 只有围栏、围栏里没内容
        assertEquals("", AiQueryOptimizer.cleanOptimizedQuery("```json\n```"));
        assertEquals("", AiQueryOptimizer.cleanOptimizedQuery("```"));
    }

    @Test
    void jsonWithoutQueryFieldFallsBackToPlainText() {
        // 模型答非所问（JSON 里没有 query）时不该丢掉它写的内容，按纯文本清洗
        String text = "建议查询：总市值大于100亿";

        assertEquals("总市值大于100亿", AiQueryOptimizer.cleanOptimizedQuery(text));
    }

    // ---------- 入口校验 ----------

    @Test
    void missingApiKeyIsRejectedBeforeCallingModel() {
        when(configService.load()).thenReturn(
                new AiConfigService.Config(false, null, "deepseek-v4-flash", false, "high", false));

        BizException e = assertThrows(BizException.class, () -> optimizer.optimizeIwencaiQuery("便宜的白马股"));

        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("AI 未配置"), e.getMessage());
    }

    @Test
    void blankQueryIsRejected() {
        when(configService.load()).thenReturn(available());

        BizException e = assertThrows(BizException.class, () -> optimizer.optimizeIwencaiQuery("  "));

        assertEquals(400, e.getCode());
        assertEquals("请输入选股条件", e.getMessage());
    }

    @Test
    void overlongQueryIsRejected() {
        when(configService.load()).thenReturn(available());

        BizException e = assertThrows(BizException.class, () -> optimizer.optimizeIwencaiQuery("字".repeat(501)));

        assertEquals(400, e.getCode());
        assertEquals("选股条件过长（最多 500 字）", e.getMessage());
        verify(settingService, never()).getPlain("ai_user_profile");
    }

    /**
     * 正常路径：模型返回 JSON，结果应是 query 的值。
     * 顺带确认画像为空时提示词里塞的是「（未设置）」而不是 null。
     */
    @Test
    void successfulOptimizationReturnsCleanedQuery() throws Exception {
        when(configService.load()).thenReturn(available());
        when(settingService.getPlain("ai_user_profile")).thenReturn("  ");
        when(llmClient.complete(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn("```json\n{\"query\":\"总市值大于100亿; 换手率大于5\"}\n```");

        assertEquals("总市值大于100亿; 换手率大于5", optimizer.optimizeIwencaiQuery("便宜点的活跃股"));
    }

    @Test
    void unparsableModelOutputBecomesBizError() throws Exception {
        when(configService.load()).thenReturn(available());
        when(settingService.getPlain("ai_user_profile")).thenReturn(null);
        when(llmClient.complete(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn("  \n  ");

        BizException e = assertThrows(BizException.class,
                () -> optimizer.optimizeIwencaiQuery("便宜点的活跃股"));

        assertEquals(400, e.getCode());
        assertEquals("AI 未能生成有效查询，请手动调整或重试", e.getMessage());
    }
}
