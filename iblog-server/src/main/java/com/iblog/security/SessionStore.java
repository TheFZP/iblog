package com.iblog.security;

import com.iblog.common.constant.CacheKeys;
import com.iblog.config.IblogProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 登录会话存储：同一账号可同时持有多个有效令牌，即多设备 / 多浏览器同时在线。
 *
 * <p>Redis 结构：hash，key = {@code iblog:login:sessions:{userId}}，
 * field = 令牌，value = 该令牌的过期时间戳（毫秒）。校验只要求「令牌存在且未过期」，
 * 不再要求「等于最新令牌」，所以后登录不会把先登录的设备挤下线。</p>
 *
 * <p>兼容旧版单值结构 {@code iblog:login:token:{userId}}：命中旧值时自动迁移到新结构，
 * 升级发布时已经登录的浏览器不会被强制退出。旧结构在下次登录/退出后自然清理。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionStore {

    private final StringRedisTemplate stringRedisTemplate;
    private final IblogProperties properties;

    /**
     * 登记一个新会话（登录成功时调用）。
     */
    public void register(Long userId, String token) {
        long expireSeconds = properties.getJwt().getExpire();
        long expireAt = System.currentTimeMillis() + expireSeconds * 1000;
        String key = key(userId);
        pruneExpired(userId);
        stringRedisTemplate.opsForHash().put(key, token, String.valueOf(expireAt));
        // hash 整体过期时间取最新令牌的有效期；更早的令牌靠字段级时间戳判断，过期后由 pruneExpired 清掉
        stringRedisTemplate.expire(key, Duration.ofSeconds(expireSeconds));
    }

    /**
     * 令牌是否仍是被登记过的有效会话。
     */
    public boolean isValid(Long userId, String token) {
        if (userId == null || token == null || token.isBlank()) {
            return false;
        }
        Object value = stringRedisTemplate.opsForHash().get(key(userId), token);
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value)) > System.currentTimeMillis();
            } catch (NumberFormatException e) {
                // 脏数据：交给 JWT 自身过期时间兜底
                return true;
            }
        }
        return adoptLegacy(userId, token);
    }

    /**
     * 注销单个会话（退出登录只影响当前设备），其它设备的令牌保持有效。
     */
    public void revoke(Long userId, String token) {
        if (userId == null || token == null || token.isBlank()) {
            return;
        }
        stringRedisTemplate.opsForHash().delete(key(userId), token);
        String legacyKey = CacheKeys.LOGIN_TOKEN + userId;
        String legacy = stringRedisTemplate.opsForValue().get(legacyKey);
        if (legacy != null && legacy.equals(token)) {
            stringRedisTemplate.delete(legacyKey);
        }
    }

    /**
     * 注销该账号全部会话（改密码、封禁等场景预留；当前未接入接口）。
     */
    public void revokeAll(Long userId) {
        if (userId == null) {
            return;
        }
        stringRedisTemplate.delete(key(userId));
        stringRedisTemplate.delete(CacheKeys.LOGIN_TOKEN + userId);
    }

    /**
     * 当前在线会话数，给页面展示「已登录设备数」用。
     */
    public long activeCount(Long userId) {
        if (userId == null) {
            return 0;
        }
        pruneExpired(userId);
        Long size = stringRedisTemplate.opsForHash().size(key(userId));
        return size == null ? 0 : size;
    }

    /**
     * 命中旧版单值会话时，把它迁移成多会话结构，避免发布瞬间全体掉线。
     */
    private boolean adoptLegacy(Long userId, String token) {
        String legacyKey = CacheKeys.LOGIN_TOKEN + userId;
        String legacy = stringRedisTemplate.opsForValue().get(legacyKey);
        if (legacy == null || !legacy.equals(token)) {
            return false;
        }
        stringRedisTemplate.delete(legacyKey);
        register(userId, token);
        log.info("登录会话已迁移为多设备结构 userId={}", userId);
        return true;
    }

    /**
     * 清理该账号已过期的会话字段，防止 hash 无限增长。
     */
    private void pruneExpired(Long userId) {
        String key = key(userId);
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
        if (entries.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Object> dead = new ArrayList<>();
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            try {
                if (Long.parseLong(String.valueOf(entry.getValue())) <= now) {
                    dead.add(entry.getKey());
                }
            } catch (NumberFormatException ignored) {
                // 无法解析的字段留着，由 JWT 过期兜底
            }
        }
        if (!dead.isEmpty()) {
            stringRedisTemplate.opsForHash().delete(key, dead.toArray());
        }
    }

    private String key(Long userId) {
        return CacheKeys.LOGIN_SESSIONS + userId;
    }
}
