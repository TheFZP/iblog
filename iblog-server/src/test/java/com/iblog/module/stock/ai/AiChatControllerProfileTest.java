package com.iblog.module.stock.ai;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iblog.common.exception.BizException;
import com.iblog.common.result.R;
import com.iblog.module.stock.entity.StockSetting;
import com.iblog.module.stock.service.StockSettingService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 画像读写端点的边界校验。
 *
 * <p>只测控制器这一层的约定，不拉 Spring 上下文：真正要紧的是三件事——
 * 没填过时 {@code content} 必须是空串而不是 null、超长必须挡在写库之前、
 * 键名必须和 AI 工具 {@code save_user_profile} 用同一个 {@code ai_user_profile}。</p>
 */
class AiChatControllerProfileTest {

    private final StockSettingService settingService = mock(StockSettingService.class);

    private final AiChatController controller = new AiChatController(
            mock(AiConfigService.class), mock(AiChatService.class), mock(IwencaiClient.class), settingService);

    @Test
    void emptyProfileReturnsBlankContent() {
        when(settingService.getSetting("ai_user_profile")).thenReturn(null);

        R<AiChatController.ProfileResponse> result = controller.profile();

        assertEquals("", result.getData().content(), "没记录时 content 必须是空串，前端才能直接渲染编辑器");
        assertNull(result.getData().updatedAt());
    }

    @Test
    void savedProfileReturnsContentAndUpdateTime() {
        LocalDateTime updatedAt = LocalDateTime.of(2026, 10, 29, 9, 30);
        StockSetting row = new StockSetting();
        row.setConfigKey("ai_user_profile");
        row.setConfigValue("## 我\n- 只做红利");
        row.setUpdateTime(updatedAt);
        when(settingService.getSetting("ai_user_profile")).thenReturn(row);

        R<AiChatController.ProfileResponse> result = controller.profile();

        assertEquals("## 我\n- 只做红利", result.getData().content());
        assertEquals(updatedAt, result.getData().updatedAt());
    }

    @Test
    void blankSavedValueIsTreatedAsEmpty() {
        StockSetting row = new StockSetting();
        row.setConfigKey("ai_user_profile");
        row.setConfigValue("   ");
        when(settingService.getSetting("ai_user_profile")).thenReturn(row);

        assertEquals("", controller.profile().getData().content());
    }

    @Test
    void overlongContentIsRejectedBeforeWriting() {
        String tooLong = "字".repeat(20001);

        BizException e = assertThrows(BizException.class,
                () -> controller.saveProfile(new AiChatController.ProfileRequest(tooLong)));

        assertEquals(400, e.getCode());
        assertEquals("画像内容过长（最多 20000 字）", e.getMessage());
        verify(settingService, never()).put(anyString(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void exactlyMaxLengthContentIsAccepted() {
        String max = "字".repeat(20000);

        R<Void> result = controller.saveProfile(new AiChatController.ProfileRequest(max));

        assertEquals(200, result.getCode());
        verify(settingService).put(eq("ai_user_profile"), eq(max), eq(false), anyString());
    }

    /**
     * 全局 {@code default-property-inclusion: non_null} 会把 null 字段整个省掉，
     * 但契约要求响应里至少有两个键，所以 {@code ProfileResponse} 上单独标了 ALWAYS。
     */
    @Test
    void updatedAtFieldSurvivesGlobalNonNullPolicy() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        String json = mapper.writeValueAsString(new AiChatController.ProfileResponse("", null));

        assertTrue(json.contains("\"content\":\"\""), "content 必须出现：" + json);
        assertTrue(json.contains("\"updatedAt\":null"), "updatedAt 必须出现（哪怕为 null）：" + json);
    }

    @Test
    void nullContentIsNormalizedToEmptyString() {
        R<Void> result = controller.saveProfile(new AiChatController.ProfileRequest(null));

        assertNotNull(result);
        // 空串交给 Service 会走真删分支，等价于「清空画像」
        verify(settingService).put(eq("ai_user_profile"), eq(""), eq(false), anyString());
    }
}
