package com.iblog.security;

import com.iblog.common.exception.BizException;
import com.iblog.config.IblogProperties;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 后台接口登录校验：JWT 校验 + Redis 会话比对（支持服务端主动注销）。
 *
 * <p>令牌来源：默认只认请求头（后台接口，避免 Cookie 带来的 CSRF）；
 * 官网行情接口 /api/stock/** 额外允许从 HttpOnly Cookie 读取，
 * 这样后台登录后官网无需再登录一次。</p>
 *
 * <p>会话是「每账号多个令牌」的结构（见 {@link SessionStore}）：
 * 手机、电脑可以同时在线，退出登录只注销当前设备的令牌。</p>
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final IblogProperties properties;
    private final SessionStore sessionStore;
    private final AuthCookie authCookie;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String token = jwtUtil.resolveToken(request.getHeader(properties.getJwt().getHeader()));
        if (token == null && allowsCookie(request)) {
            token = authCookie.read(request);
        }
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
        // 多设备登录：只要求该令牌仍在会话集合里，后登录不会挤掉先登录的设备
        if (!sessionStore.isValid(userId, token)) {
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

    /**
     * 只有官网行情接口接受 Cookie 令牌；后台接口仍强制走请求头。
     */
    private boolean allowsCookie(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/stock/");
    }
}
