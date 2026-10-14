package com.iblog.common.constant;

/**
 * Redis 缓存 key 常量。
 */
public interface CacheKeys {

    /** 登录令牌：iblog:login:token:{userId} */
    String LOGIN_TOKEN = "iblog:login:token:";

    /** 站点配置 */
    String SITE_CONFIG = "iblog:site:config";
}
