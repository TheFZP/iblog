package com.iblog.module.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 官网登录态。
 *
 * <p>不返回令牌本身：官网不需要（同源 Cookie 会自动携带），
 * 返回令牌只会扩大 XSS 时的暴露面。</p>
 */
@Data
@Builder
public class PortalAuthVO {

    /** 是否已登录过后台 */
    private boolean authenticated;

    private Long userId;

    private String username;

    private String nickname;

    private String avatar;
}
