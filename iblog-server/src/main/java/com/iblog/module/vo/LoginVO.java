package com.iblog.module.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 登录成功返回。
 */
@Data
@Builder
public class LoginVO {

    private String token;

    private Long userId;

    private String username;

    private String nickname;

    private String avatar;
}
