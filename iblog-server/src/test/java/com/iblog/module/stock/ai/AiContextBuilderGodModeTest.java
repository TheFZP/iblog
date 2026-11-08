package com.iblog.module.stock.ai;

import org.junit.jupiter.api.Test;

import com.iblog.module.stock.service.StockSettingService;
import com.iblog.module.stock.service.TencentService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 验证股神模式只按开关注入 system prompt。
 *
 * <p>这里不打上游、不连数据库：{@link AiContextBuilder} 的依赖全部 mock，
 * 「取不到数据」正好走到降级分支，不影响对 prompt 拼接本身的判断。</p>
 */
class AiContextBuilderGodModeTest {

    private final TencentService tencentService = mock(TencentService.class);
    private final StockSettingService settingService = mock(StockSettingService.class);
    private final AiContextBuilder builder = new AiContextBuilder(tencentService, settingService);

    @Test
    void godModeOffKeepsPromptLean() {
        String prompt = builder.build(null, null, false, "SOME_SKILL", false);
        assertFalse(prompt.contains("股神模式"), "未开启时不应出现股神模式");
        assertTrue(prompt.contains("SOME_SKILL"), "普通 skill 说明必须保留");
    }

    @Test
    void godModeOnInjectsFourSectionsBeforeSkills() {
        String prompt = builder.build(null, null, false, "SOME_SKILL", true);
        assertTrue(prompt.contains("股神模式"), "开启后必须注入股神模式");
        assertTrue(prompt.contains("盈利模式"));
        assertTrue(prompt.contains("交易活跃度"));
        assertTrue(prompt.contains("成长空间"));

        // 股神模式是「怎么组织回答」的总纲，必须排在具体工具说明之前
        int god = prompt.indexOf("股神模式");
        int skill = prompt.indexOf("SOME_SKILL");
        assertTrue(god > 0 && skill > 0, "两段都应存在");
        assertTrue(god < skill, "股神模式应排在 skill 说明之前");
    }

    @Test
    void godModeOnToleratesEmptySkills() {
        // 没有 skill 说明时不能拼出两个换行或残留占位符
        String prompt = builder.build(null, null, false, "", true);
        assertTrue(prompt.contains("股神模式"));
        assertFalse(prompt.contains("{{SKILL_PROMPTS}}"), "占位符必须被替换");
    }
}
