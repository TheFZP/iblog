package com.iblog.module.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iblog.common.constant.CacheKeys;
import com.iblog.module.entity.SiteConfig;
import com.iblog.module.mapper.SiteConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站点配置服务，读取走 Redis 缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiteConfigService extends ServiceImpl<SiteConfigMapper, SiteConfig> {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 获取全部配置（Map 结构），优先读缓存。
     */
    public Map<String, String> getAll() {
        String cached = stringRedisTemplate.opsForValue().get(CacheKeys.SITE_CONFIG);
        if (StringUtils.hasText(cached)) {
            try {
                return objectMapper.readValue(cached, new TypeReference<LinkedHashMap<String, String>>() {
                });
            } catch (Exception e) {
                log.warn("站点配置缓存解析失败，回源数据库", e);
            }
        }

        Map<String, String> config = new LinkedHashMap<>();
        List<SiteConfig> list = list(new LambdaQueryWrapper<SiteConfig>().orderByAsc(SiteConfig::getId));
        for (SiteConfig item : list) {
            config.put(item.getConfigKey(), item.getConfigValue() == null ? "" : item.getConfigValue());
        }

        try {
            stringRedisTemplate.opsForValue()
                    .set(CacheKeys.SITE_CONFIG, objectMapper.writeValueAsString(config), Duration.ofHours(2));
        } catch (Exception e) {
            log.warn("站点配置缓存写入失败", e);
        }
        return config;
    }

    /**
     * 批量保存配置（存在则更新，不存在则新增），并清空缓存。
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveAll(Map<String, String> config) {
        if (config == null || config.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : config.entrySet()) {
            SiteConfig existing = getOne(new LambdaQueryWrapper<SiteConfig>()
                    .eq(SiteConfig::getConfigKey, entry.getKey()), false);
            if (existing == null) {
                SiteConfig item = new SiteConfig();
                item.setConfigKey(entry.getKey());
                item.setConfigValue(entry.getValue());
                save(item);
            } else {
                existing.setConfigValue(entry.getValue());
                updateById(existing);
            }
        }
        stringRedisTemplate.delete(CacheKeys.SITE_CONFIG);
    }
}
