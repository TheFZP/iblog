package com.iblog.module.stock.controller;

import com.iblog.common.result.R;
import com.iblog.module.stock.service.StockSettingService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 后台行情配置（AI Key、模型、刷新间隔等）。
 *
 * <p><b>为什么挂在 {@code /api/admin/} 而不是 {@code /api/stock/}</b>：
 * 官网的 nginx 只放行 {@code /api/portal/} 与 {@code /api/stock/}，
 * {@code /api/admin/**} 一律 404 —— 这是刻意设计的，防止后台接口从公网暴露。
 * 而本接口能写 AI Key，属于「只有站长能碰」的凭证，放进 /api/stock/ 等于把
 * 密钥的写入权开放给公网。故放在 admin 前缀下，由 {@code AuthInterceptor} 校验 JWT。</p>
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "后台-行情配置")
@RestController
@RequestMapping("/api/admin/stock/config")
@RequiredArgsConstructor
public class AdminStockConfigController {

    /** AI Key 的配置键。 */
    private static final String KEY_AI_API_KEY = "ai_api_key";
    /** 其余可写配置的键与是否敏感。 */
    private static final Map<String, Boolean> WRITABLE_KEYS = new LinkedHashMap<>();

    static {
        WRITABLE_KEYS.put(KEY_AI_API_KEY, true);
        WRITABLE_KEYS.put("ai_model", false);
        WRITABLE_KEYS.put("ai_thinking_enabled", false);
        WRITABLE_KEYS.put("ai_reasoning_effort", false);
        WRITABLE_KEYS.put("ai_web_search_enabled", false);
        WRITABLE_KEYS.put("quotes_refresh_ms", false);
        WRITABLE_KEYS.put("kline_refresh_ms", false);
    }

    private final StockSettingService settingService;

    @Operation(summary = "读取行情配置（敏感项打码）")
    @GetMapping
    public R<Map<String, String>> read() {
        Map<String, String> stored = settingService.readAllMasked();
        Map<String, String> result = new LinkedHashMap<>();
        // 只回传白名单内的键，避免以后表里多出内部项时被顺带暴露
        for (String key : WRITABLE_KEYS.keySet()) {
            result.put(key, stored.getOrDefault(key, ""));
        }
        return R.ok(result);
    }

    @Operation(summary = "写入行情配置（空值表示删除该项）")
    @PutMapping
    public R<Void> write(@RequestBody Map<String, String> body) {
        for (Map.Entry<String, String> entry : body.entrySet()) {
            String key = entry.getKey();
            Boolean secret = WRITABLE_KEYS.get(key);
            if (secret == null) {
                // 白名单外的键直接忽略而不是报错：前端可能带着一整份设置对象提交，
                // 因为一个未来新增的键就把整次保存打回，体验更差。
                continue;
            }
            String value = entry.getValue();
            // 打码值原样提交回来（用户没动过 Key 那一栏）时不要覆盖真实 Key
            if (Boolean.TRUE.equals(secret) && StringUtils.hasText(value) && value.contains("****")) {
                continue;
            }
            settingService.put(key, value, Boolean.TRUE.equals(secret), "行情模块配置");
        }
        return R.ok();
    }
}
