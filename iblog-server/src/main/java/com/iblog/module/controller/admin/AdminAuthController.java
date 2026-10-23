package com.iblog.module.controller.admin;

import com.iblog.common.constant.CacheKeys;
import com.iblog.common.exception.BizException;
import com.iblog.common.result.R;
import com.iblog.config.IblogProperties;
import com.iblog.module.dto.LoginDTO;
import com.iblog.module.entity.User;
import com.iblog.module.service.UserService;
import com.iblog.module.vo.LoginVO;
import com.iblog.security.AuthCookie;
import com.iblog.security.JwtUtil;
import com.iblog.security.SessionStore;
import com.iblog.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 后台认证接口。
 */
@Tag(name = "后台-认证")
@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final SessionStore sessionStore;
    private final StringRedisTemplate stringRedisTemplate;
    private final IblogProperties properties;
    private final AuthCookie authCookie;

    @Operation(summary = "登录")
    @PostMapping("/login")
    public R<LoginVO> login(@Valid @RequestBody LoginDTO dto, HttpServletResponse response) {
        User user = userService.getByUsername(dto.getUsername());
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BizException(400, "用户名或密码错误");
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new BizException(403, "账号已被停用");
        }

        String token = jwtUtil.createToken(user.getId(), user.getUsername());
        // 多设备在线：登记为新会话，不覆盖其它设备已持有的令牌
        sessionStore.register(user.getId(), token);
        // 同一台机器 / 同一主域下，官网 https://host/ 与后台 https://host:81/ 共享这份登录态
        authCookie.write(response, token);

        return R.ok(LoginVO.builder()
                .token(token)
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .build());
    }

    @Operation(summary = "退出登录（仅注销当前设备，其它设备保持在线）")
    @PostMapping("/logout")
    public R<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        Long userId = UserContext.get();
        if (userId != null) {
            // 优先用请求头令牌；后台没带 header 时退回 Cookie，尽量把当前会话注销干净
            String token = jwtUtil.resolveToken(request.getHeader(properties.getJwt().getHeader()));
            if (token == null) {
                token = authCookie.read(request);
            }
            sessionStore.revoke(userId, token);
        }
        authCookie.clear(response);
        return R.ok();
    }

    @Operation(summary = "签发官网行情一次性登录票据（官网与后台网址不同、Cookie 无法共享时使用）")
    @PostMapping("/ticket")
    public R<Map<String, Object>> ticket(HttpServletRequest request) {
        String token = jwtUtil.resolveToken(request.getHeader(properties.getJwt().getHeader()));
        if (token == null) {
            throw new BizException(401, "未登录，请先登录");
        }
        String ticket = UUID.randomUUID().toString().replace("-", "");
        long ticketExpire = properties.getAuth().getTicketExpire();
        stringRedisTemplate.opsForValue().set(
                CacheKeys.PORTAL_TICKET + ticket,
                token,
                Duration.ofSeconds(ticketExpire));
        return R.ok(Map.of("ticket", ticket, "expiresIn", ticketExpire));
    }

    @Operation(summary = "当前登录用户信息")
    @GetMapping("/info")
    public R<User> info() {
        User user = userService.getById(UserContext.get());
        if (user == null) {
            throw new BizException(401, "登录已失效");
        }
        user.setPassword(null);
        return R.ok(user);
    }
}
