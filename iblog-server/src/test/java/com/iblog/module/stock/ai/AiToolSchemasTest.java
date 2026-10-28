package com.iblog.module.stock.ai;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验桌面版提示词与工具 schema 常量的搬运完整性。
 *
 * <p>这些常量是「逐字搬运」产物，任何一条缺失或被误改都会直接影响模型行为，
 * 因此用测试把关键不变量固定下来：工具数量、名称唯一性、description 可读性、
 * parameters 是否为合法 JSON 对象、系统提示词是否保留全部占位符。
 */
class AiToolSchemasTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 系统提示词模板里必须原样保留的 7 个占位符 */
    private static final List<String> PLACEHOLDERS = List.of(
            "{{BEIJING_TIME}}",
            "{{MARKET_RULES}}",
            "{{PRELOAD_SECTION}}",
            "{{SEARCH_POLICY}}",
            "{{SKILL_PROMPTS}}",
            "{{USER_PROFILE}}",
            "{{STOCK_CONTEXT}}");

    @Test
    void toolCountIsSeventeen() {
        assertEquals(17, AiToolSchemas.TOOLS.size(), "桌面版共 17 个 function，搬运后数量必须一致");
    }

    @Test
    void toolNamesAreUnique() {
        Set<String> names = new HashSet<>();
        for (AiToolSchemas.ToolDef def : AiToolSchemas.TOOLS) {
            assertNotNull(def.name(), "工具名不能为 null");
            assertFalse(def.name().isBlank(), "工具名不能为空");
            assertTrue(names.add(def.name()), "工具名重复：" + def.name());
        }
        assertEquals(AiToolSchemas.TOOLS.size(), names.size());
    }

    @Test
    void descriptionIsPresentAndMeaningful() {
        for (AiToolSchemas.ToolDef def : AiToolSchemas.TOOLS) {
            String desc = def.description();
            assertNotNull(desc, "description 不能为 null：" + def.name());
            assertFalse(desc.isBlank(), "description 不能为空：" + def.name());
            assertTrue(desc.length() > 10, "description 过短，疑似搬运丢失：" + def.name() + " -> " + desc);
            assertEquals(desc.trim(), desc, "description 不应带首尾空白：" + def.name());
        }
    }

    @Test
    void parametersJsonIsParseableObject() throws Exception {
        for (AiToolSchemas.ToolDef def : AiToolSchemas.TOOLS) {
            String json = def.parametersJson();
            assertNotNull(json, "parametersJson 不能为 null：" + def.name());
            assertFalse(json.contains("\n"), "parametersJson 必须是紧凑 JSON（不能换行）：" + def.name());
            JsonNode node = objectMapper.readTree(json);
            assertTrue(node.isObject(), "parametersJson 必须解析为 JSON 对象：" + def.name());
            assertEquals("object", node.path("type").asText(), "parameters.type 必须是 object：" + def.name());
        }
    }

    @Test
    void systemPromptTemplateKeepsAllPlaceholders() {
        String template = AiToolPrompts.SYSTEM_PROMPT_TEMPLATE;
        assertNotNull(template);
        for (String placeholder : PLACEHOLDERS) {
            assertTrue(template.contains(placeholder), "系统提示词模板缺少占位符：" + placeholder);
        }
    }

    @Test
    void everyToolHasSystemPrompt() {
        for (AiToolSchemas.ToolDef def : AiToolSchemas.TOOLS) {
            assertNotNull(def.systemPrompt(), "systemPrompt 不能为 null：" + def.name());
            assertFalse(def.systemPrompt().isBlank(), "systemPrompt 不能为空：" + def.name());
        }
    }
}
