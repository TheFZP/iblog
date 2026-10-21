package com.iblog.common.constant;

/**
 * Redis 缓存 key 常量。
 */
public interface CacheKeys {

    /** 登录令牌：iblog:login:token:{userId} */
    String LOGIN_TOKEN = "iblog:login:token:";

    /** 官网行情跨域登录票据：iblog:portal:ticket:{ticket} -> 后台登录令牌 */
    String PORTAL_TICKET = "iblog:portal:ticket:";

    /** 站点配置 */
    String SITE_CONFIG = "iblog:site:config";
}
