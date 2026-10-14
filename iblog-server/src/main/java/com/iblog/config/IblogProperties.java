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
}
