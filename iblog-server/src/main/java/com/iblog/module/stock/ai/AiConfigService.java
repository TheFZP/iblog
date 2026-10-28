package com.iblog.module.stock.ai;

import com.iblog.module.stock.service.StockSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 配置读取。
 *
 * <p>配置统一存在 {@code stock_setting} 表（由 {@link StockSettingService} 读写），
 * 后台通过 {@code AdminStockConfigController} 维护。本类只负责把「字符串配置」
 * 转成「有默认值的运行参数」，并保证 <b>API Key 永远不会出现在返回给前端的数据里</b>。</p>
 *
 * <p>为什么单独一层而不在调用处直接 {@code getPlain("ai_model")}：默认值、空串归一、
 * 开关解析这些规则散落到各处后，改一处就会漏一处（比如把 {@code "false"} 当真是历史的坑）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiConfigService {

    /** 默认模型：与桌面版保持一致。 */
    public static final String DEFAULT_MODEL = "deepseek-v4-flash";
    /** 默认推理强度。 */
    public static final String DEFAULT_EFFORT = "high";
    /** 允许的推理强度取值。 */
    public static final java.util.List<String> EFFORTS = java.util.List.of("low", "medium", "high");

    private final StockSettingService settingService;

    /** 运行参数快照。 */
    public record Config(boolean available, String apiKey, String model, boolean thinkingEnabled,
                         String reasoningEffort, boolean webSearchEnabled) {
    }

    /**
     * 读取当前配置。
     *
     * <p>{@code available} 只看 Key 有没有配：没有 Key 时前端应禁用输入框并提示去后台配置，
     * 而不是让用户点发送后收到一个 500。</p>
     */
    public Config load() {
        String key = trim(settingService.getPlain("ai_api_key"));
        String model = trim(settingService.getPlain("ai_model"));
        String effort = trim(settingService.getPlain("ai_reasoning_effort"));
        String thinking = trim(settingService.getPlain("ai_thinking_enabled"));
        String webSearch = trim(settingService.getPlain("ai_web_search_enabled"));
        return new Config(
                StringUtils.hasText(key),
                key,
                StringUtils.hasText(model) ? model : DEFAULT_MODEL,
                parseBoolean(thinking, true),
                // List.of 的 contains(null) 会直接抛 NPE，所以必须先判空再查表
                StringUtils.hasText(effort) && EFFORTS.contains(effort) ? effort : DEFAULT_EFFORT,
                parseBoolean(webSearch, true));
    }

    /** 只回传非敏感项，供前端展示（模型名、开关等）。 */
    public java.util.Map<String, Object> publicView() {
        Config config = load();
        java.util.Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("available", config.available());
        view.put("model", config.model());
        view.put("thinkingEnabled", config.thinkingEnabled());
        view.put("reasoningEffort", config.reasoningEffort());
        view.put("webSearchEnabled", config.webSearchEnabled());
        return view;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    /**
     * 解析布尔开关。
     *
     * <p>写成 {@code "0"/"1"} 也算，因为历史上前端 checkbox 存过数字串；
     * 无法识别时用默认值而不是抛异常——一个脏配置不该让整个 AI 面板不可用。</p>
     */
    private static boolean parseBoolean(String value, boolean fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        String v = value.trim().toLowerCase();
        if ("true".equals(v) || "1".equals(v) || "on".equals(v) || "yes".equals(v)) {
            return true;
        }
        if ("false".equals(v) || "0".equals(v) || "off".equals(v) || "no".equals(v)) {
            return false;
        }
        return fallback;
    }
}
