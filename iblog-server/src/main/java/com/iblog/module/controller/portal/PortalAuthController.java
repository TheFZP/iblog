package com.iblog.module.controller.portal;

import com.iblog.common.constant.CacheKeys;
import com.iblog.common.exception.BizException;
import com.iblog.common.result.R;
import com.iblog.config.IblogProperties;
import com.iblog.module.dto.TicketLoginDTO;
import com.iblog.module.entity.User;
import com.iblog.module.service.UserService;
import com.iblog.module.vo.PortalAuthVO;
import com.iblog.security.AuthCookie;
import com.iblog.security.JwtUtil;
import com.iblog.security.SessionStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 官网登录态接口。
 *
 * <p>官网的「行情」只对登录过后台（iblog-admin）的用户开放：
 * 1. 后台与官网同主机不同端口、或同一主域的不同子域时，后台登录下发的 Cookie 会被官网自动带上，
 *    用 {@link #state} 即可判断；
 * 2. 两者完全是不同地址（无法共享 Cookie）时，从后台点「行情」会先拿到一次性票据，
 *    官网用 {@link #ticketLogin} 换成自己域名下的 Cookie，后台会话不受影响。</p>
 */
@Tag(name = "官网-认证")
@RestController
@RequestMapping("/api/portal/auth")
@RequiredArgsConstructor
public class PortalAuthController {

    private final JwtUtil jwtUtil;
    private final IblogProperties properties;
    private final StringRedisTemplate stringRedisTemplate;
    private final SessionStore sessionStore;
    private final UserService userService;
    private final AuthCookie authCookie;

    @Operation(summary = "用一次性票据换取官网登录态")
    @PostMapping("/ticket-login")
    public R<PortalAuthVO> ticketLogin(@Valid @RequestBody TicketLoginDTO dto, HttpServletResponse response) {
        // 一次性票据：取出的同时删除，避免同一条链接被重复使用
        String token = stringRedisTemplate.opsForValue().getAndDelete(CacheKeys.PORTAL_TICKET + dto.getTicket().trim());
        if (token == null) {
            throw new BizException(401, "登录票据已失效，请从管理后台重新进入");
        }

        Long userId;
        try {
            userId = Long.valueOf(jwtUtil.parseToken(token).getSubject());
        } catch (Exception e) {
            throw new BizException(401, "登录票据已失效，请从管理后台重新进入");
        }

        // 后台可能已经退出登录：此时票据对应的会话已作废，不能放行
        if (!sessionStore.isValid(userId, token)) {
            throw new BizException(401, "后台登录已失效，请重新登录管理后台");
        }

        User user = userService.getById(userId);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            throw new BizException(403, "账号不可用");
        }

        authCookie.write(response, token);
        return R.ok(toVO(user));
    }

    @Operation(summary = "当前官网登录态")
    @GetMapping("/state")
    public R<PortalAuthVO> state(HttpServletRequest request) {
        String token = authCookie.read(request);
        if (token == null) {
            token = jwtUtil.resolveToken(request.getHeader(properties.getJwt().getHeader()));
        }
        if (token != null) {
            try {
                Long userId = Long.valueOf(jwtUtil.parseToken(token).getSubject());
                if (sessionStore.isValid(userId, token)) {
                    User user = userService.getById(userId);
                    if (user != null && Integer.valueOf(1).equals(user.getStatus())) {
                        return R.ok(toVO(user));
                    }
                }
            } catch (Exception ignored) {
                // 令牌非法 / 过期，按未登录处理
            }
        }
        return R.ok(PortalAuthVO.builder().authenticated(false).build());
    }

    private PortalAuthVO toVO(User user) {
        return PortalAuthVO.builder()
                .authenticated(true)
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .build();
    }
}
