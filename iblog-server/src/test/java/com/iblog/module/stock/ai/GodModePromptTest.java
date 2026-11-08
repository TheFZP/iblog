package com.iblog.module.stock.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 股神模式提示词契约测试。
 *
 * <p>股神模式的价值全在这段提示词上：它必须把四段式结构、每段的判读口径，
 * 以及「每一步都要解释逻辑」这条硬要求都写清楚。文案一旦被误删或截断，
 * 模型就会退回普通问答，功能看起来「能用」但完全没有效果 —— 这种退化最难被发现，
 * 所以用测试把关键约定钉住。</p>
 */
class GodModePromptTest {

    @Test
    void promptCoversAllFourSections() {
        String prompt = AiToolPrompts.GOD_MODE_PROMPT;
        assertNotNull(prompt);

        // 四段式结构：生意本质 → 交易活跃度 → 成长空间 → 综合结论
        assertTrue(prompt.contains("盈利模式"), "缺少第一段「盈利模式」");
        assertTrue(prompt.contains("现金流"), "缺少第一段「现金流」");
        assertTrue(prompt.contains("交易活跃度"), "缺少第二段「交易活跃度」");
        assertTrue(prompt.contains("资金动向"), "缺少第二段「资金动向」");
        assertTrue(prompt.contains("量比"), "缺少第二段「量比」");
        assertTrue(prompt.contains("换手率"), "缺少第二段「换手率」");
        assertTrue(prompt.contains("行业"), "缺少第三段「行业」");
        assertTrue(prompt.contains("政策"), "缺少第三段「政策」");
        assertTrue(prompt.contains("风险"), "缺少第四段「风险」");
    }

    @Test
    void promptDemandsReasoningForEveryStep() {
        String prompt = AiToolPrompts.GOD_MODE_PROMPT;
        // 这条是股神模式与普通回答的唯一区别，不能丢
        assertTrue(prompt.contains("因为") || prompt.contains("依据是"), "缺少「解释逻辑」的硬要求");
        assertTrue(prompt.contains("解释"), "缺少「解释每一步逻辑」的说明");
    }

    @Test
    void promptNamesConcreteTools() {
        String prompt = AiToolPrompts.GOD_MODE_PROMPT;
        // 提示词里指错工具名，模型就会调不存在的工具然后编数据
        assertTrue(prompt.contains("get_stock_fundamental"), "缺少基本面工具指引");
        assertTrue(prompt.contains("get_stock_money_flow_history"), "缺少资金流历史工具指引");
        assertTrue(prompt.contains("get_stock_industry"), "缺少行业工具指引");
        assertTrue(prompt.contains("get_stock_quote"), "缺少行情工具指引");
    }

    @Test
    void promptForbidsFabrication() {
        String prompt = AiToolPrompts.GOD_MODE_PROMPT;
        assertTrue(prompt.contains("不要编") || prompt.contains("不要用虚构")
                        || prompt.contains("不要编造"),
                "股神模式篇幅更长，更容易编数字，必须显式禁止");
    }

    @Test
    void promptOnlyInjectedWhenEnabled() {
        // 默认模板里不能出现股神模式字样，否则普通问答也会按四段式写长文
        assertFalse(AiToolPrompts.SYSTEM_PROMPT_TEMPLATE.contains("股神模式"),
                "股神模式必须按开关注入，不能常住系统提示词");
    }
}
