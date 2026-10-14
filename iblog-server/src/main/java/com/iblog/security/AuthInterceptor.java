package com.iblog.security;

import com.iblog.common.constant.CacheKeys;
import com.iblog.common.exception.BizException;
import com.iblog.config.IblogProperties;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 后台接口登录校验：JWT 校验 + Redis 会话比对（支持服务端主动注销）。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final IblogProperties properties;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String token = jwtUtil.resolveToken(request.getHeader(properties.getJwt().getHeader()));
        if (token == null) {
            throw new BizException(401, "未登录，请先登录");
        }

        Claims claims;
        try {
            claims = jwtUtil.parseToken(token);
        } catch (Exception e) {
            throw new BizException(401, "登录状态已失效，请重新登录");
        }

        Long userId = Long.valueOf(claims.getSubject());
        String cached = stringRedisTemplate.opsForValue().get(CacheKeys.LOGIN_TOKEN + userId);
        if (cached == null || !cached.equals(token)) {
            throw new BizException(401, "登录已过期或被注销，请重新登录");
        }

        UserContext.set(userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }
}
