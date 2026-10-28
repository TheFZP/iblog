package com.iblog.config;

import com.iblog.security.AuthInterceptor;
import com.iblog.security.VisitLogInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：登录拦截器 + 访问日志拦截器 + 跨域。
 *
 * <p>生产环境由 Nginx 反向代理 /api，一般不会触发跨域；此处配置主要方便本地直连调试。</p>
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final VisitLogInterceptor visitLogInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                // 后台接口：只认 Authorization 请求头
                // 行情接口：请求头或后台登录时下发的 HttpOnly Cookie 均可（官网免二次登录）
                .addPathPatterns("/api/admin/**", "/api/stock/**")
                .excludePathPatterns("/api/admin/auth/login");
        registry.addInterceptor(visitLogInterceptor)
                // 只记官网流量：后台是博主自己、行情是轮询型接口，记了只有噪声
                .addPathPatterns("/api/portal/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                // 必须把实际用到的动词都列出来：nginx 反向代理后 Spring 看到的是内网
                // http://iblog-server:8080，与浏览器 Origin 的 https://<域名> 不同源，
                // 于是带 Origin 头的同源请求也会走 CORS 校验；未列出的动词（例如改名用的
                // PATCH）会被 CORS 直接拒成 HTTP 403「Invalid CORS request」。
                .allowedMethods("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
