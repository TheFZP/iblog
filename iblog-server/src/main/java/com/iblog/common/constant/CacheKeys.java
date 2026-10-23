package com.iblog.common.constant;

/**
 * Redis 缓存 key 常量。
 */
public interface CacheKeys {

    /** 登录令牌（旧版单值结构，升级后仅用于迁移兼容）：iblog:login:token:{userId} */
    String LOGIN_TOKEN = "iblog:login:token:";

    /** 登录会话集合：iblog:login:sessions:{userId} -> hash{令牌: 过期时间戳}，支持同一账号多设备在线 */
    String LOGIN_SESSIONS = "iblog:login:sessions:";

    /** 官网行情跨域登录票据：iblog:portal:ticket:{ticket} -> 后台登录令牌 */
    String PORTAL_TICKET = "iblog:portal:ticket:";

    /** 站点配置 */
    String SITE_CONFIG = "iblog:site:config";
}
