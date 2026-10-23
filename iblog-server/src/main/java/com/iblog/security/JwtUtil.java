package com.iblog.security;

import com.iblog.config.IblogProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 生成与解析。
 */
@Component
@RequiredArgsConstructor
public class JwtUtil {

    private final IblogProperties properties;

    private SecretKey secretKey() {
        return Keys.hmacShaKeyFor(properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成令牌。
     */
    public String createToken(Long userId, String username) {
        long expireSeconds = properties.getJwt().getExpire();
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expireSeconds * 1000);
        return Jwts.builder()
                // 每次登录生成唯一 jti：同一秒内多次登录也会得到不同令牌，
                // 多设备会话（见 SessionStore）才能各自独立注销。
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(secretKey())
                .compact();
    }

    /**
     * 解析令牌，非法或过期会抛出异常。
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 从请求头中截取真正的 token。
     */
    public String resolveToken(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        String prefix = properties.getJwt().getPrefix();
        if (prefix != null && !prefix.isBlank() && headerValue.startsWith(prefix)) {
            return headerValue.substring(prefix.length()).trim();
        }
        return headerValue.trim();
    }
}
