package com.iblog.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 项目自定义配置，对应 application.yml 中的 iblog.* 节点。
 */
@Data
@Component
@ConfigurationProperties(prefix = "iblog")
public class IblogProperties {

    private Jwt jwt = new Jwt();

    private Auth auth = new Auth();

    @Data
    public static class Jwt {
        /** 签名密钥，长度需 >= 32 字符 */
        private String secret;
        /** 过期时间（秒），默认 1 天 */
        private long expire = 86400L;
        /** 令牌请求头名称 */
        private String header = "Authorization";
        /** 令牌前缀 */
        private String prefix = "Bearer ";
    }

    /**
     * 官网行情登录态。后台登录后除返回 header 令牌外，还会下发一个 HttpOnly Cookie，
     * 供官网（同源 / 同主域的不同地址）识别「是否登录过后台」。
     */
    @Data
    public static class Auth {
        /** Cookie 名称 */
        private String cookieName = "iblog_auth";
        /** 是否仅 HTTPS 发送；线上必须 true，本地 http 开发用 false */
        private boolean cookieSecure = false;
        /** Cookie 作用域；留空表示仅当前主机（不同端口会自动共享） */
        private String cookieDomain = "";
        /** 跨域一次性登录票据有效期（秒） */
        private long ticketExpire = 120L;
    }
}
