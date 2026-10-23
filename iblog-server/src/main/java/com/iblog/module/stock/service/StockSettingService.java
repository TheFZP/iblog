package com.iblog.module.stock.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.iblog.module.stock.entity.StockSetting;
import com.iblog.module.stock.mapper.StockSettingMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行情模块配置读写。
 *
 * <p>为什么要有这一层而不是直接用 {@code blog_site_config}：站点配置是「全站可见的公开展示信息」
 * （站名、头像、备案号），而行情配置里有 <b>AI API Key</b> 这类凭证。混在一张表里，
 * 任何一个读站点配置的路径都可能把 Key 带出去。分成独立表后，
 * 「公开读」与「含密钥读」在物理上就是两条路。</p>
 */
@Service
public class StockSettingService extends ServiceImpl<StockSettingMapper, StockSetting> {

    /** 读取全部配置，敏感项以掩码代替明文。 */
    public Map<String, String> readAllMasked() {
        Map<String, String> result = new LinkedHashMap<>();
        for (StockSetting setting : list()) {
            result.put(setting.getConfigKey(), maskIfSecret(setting));
        }
        return result;
    }

    /** 读取全部配置的明文，仅供后端内部使用（如调用 LLM 前取 Key）。 */
    public Map<String, String> readAllPlain() {
        Map<String, String> result = new LinkedHashMap<>();
        for (StockSetting setting : list()) {
            result.put(setting.getConfigKey(), setting.getConfigValue());
        }
        return result;
    }

    /** 取单个配置的明文，不存在返回 {@code null}。 */
    public String getPlain(String key) {
        StockSetting setting = find(key);
        return setting == null ? null : setting.getConfigValue();
    }

    /**
     * 写配置。值为 {@code null} 或空串表示删除该项。
     *
     * <p>为什么删除是真删：留着空值行会让「没配置」与「配了个空字符串」两种状态难以区分，
     * 而前端判断「AI 是否可用」正是看 Key 有没有配。</p>
     */
    public void put(String key, String value, boolean secret, String remark) {
        StockSetting existing = find(key);
        if (!StringUtils.hasText(value)) {
            if (existing != null) {
                removeById(existing.getId());
            }
            return;
        }
        if (existing == null) {
            existing = new StockSetting();
            existing.setConfigKey(key);
        }
        existing.setConfigValue(value);
        existing.setIsSecret(secret ? 1 : 0);
        existing.setRemark(remark);
        saveOrUpdate(existing);
    }

    private StockSetting find(String key) {
        List<StockSetting> found = list(new LambdaQueryWrapper<StockSetting>()
                .eq(StockSetting::getConfigKey, key)
                .last("LIMIT 1"));
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * 敏感值打码：只露首尾各 4 个字符。
     *
     * <p>为什么不直接返回空串：用户需要确认「我填过 Key 了没有」「填的是不是那一把」，
     * 全遮起来等于看不出任何信息，反而让人反复重填。</p>
     */
    static String maskIfSecret(StockSetting setting) {
        String value = setting.getConfigValue();
        if (setting.getIsSecret() == null || setting.getIsSecret() != 1 || value == null) {
            return value;
        }
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }
}
