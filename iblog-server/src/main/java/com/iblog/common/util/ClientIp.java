package com.iblog.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * 客户端 IP 解析：与 PortalArticleController.getClientIp 同口径，抽出来共用。
 *
 * <p>nginx 反代后 request.getRemoteAddr() 是容器内网 IP，
 * 真实客户端 IP 在 X-Forwarded-For 首段。</p>
 */
public final class ClientIp {

    private ClientIp() {
    }

    private static final String[] HEADERS = {
            "X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"
    };

    public static String of(HttpServletRequest request) {
        for (String header : HEADERS) {
            String value = request.getHeader(header);
            if (StringUtils.hasText(value) && !"unknown".equalsIgnoreCase(value)) {
                int index = value.indexOf(',');
                return index > 0 ? value.substring(0, index).trim() : value.trim();
            }
        }
        return request.getRemoteAddr();
    }
}
