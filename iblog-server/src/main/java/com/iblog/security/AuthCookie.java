package com.iblog.security;

import com.iblog.config.IblogProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 官网行情登录态 Cookie 的读写。
 *
 * <p>Cookie 与后台登录令牌同值，但只是 HttpOnly 的「官网可见」副本：
 * - 后台接口仍然只认 Authorization 请求头，避免 Cookie 带来 CSRF 面；
 * - 官网 /api/stock/** 允许从该 Cookie 取令牌，浏览器请求同源接口时会自动带上；</p>
 */
@Component
@RequiredArgsConstructor
public class AuthCookie {

    private final IblogProperties properties;

    /**
     * 从请求 Cookie 中取令牌；没有则返回 null。
     */
    public String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String name = properties.getAuth().getCookieName();
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * 下发登录态 Cookie（有效期与 JWT 一致）。
     */
    public void write(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(token, Duration.ofSeconds(properties.getJwt().getExpire())).toString());
    }

    /**
     * 清除登录态 Cookie。
     */
    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        IblogProperties.Auth auth = properties.getAuth();
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(auth.getCookieName(), value)
                .httpOnly(true)
                .secure(auth.isCookieSecure())
                .path("/")
                .sameSite("Lax")
                .maxAge(maxAge);
        String domain = auth.getCookieDomain();
        if (domain != null && !domain.isBlank()) {
            // 现代浏览器不再要求前导点，去掉它可避免个别实现把 ".example.com" 当成非法域名
            builder.domain(domain.startsWith(".") ? domain.substring(1) : domain.trim());
        }
        return builder.build();
    }
}
