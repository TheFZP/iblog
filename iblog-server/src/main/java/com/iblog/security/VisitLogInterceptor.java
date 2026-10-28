package com.iblog.security;

import com.iblog.common.util.ClientIp;
import com.iblog.module.entity.VisitLog;
import com.iblog.module.service.VisitLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 官网访问日志拦截器：记录 /api/portal/** 的每次调用。
 *
 * <p>只记官网（portal）流量：后台 /api/admin/** 是博主自己，没有统计意义；
 * 行情 /api/stock/** 是轮询型接口（巡查器 15s 一次），记进来只会刷屏。
 * portal 是「页面打开必然触发」的接口层，量级低且最接近真实访问。</p>
 *
 * <p>落库在 afterCompletion（拿到最终业务码与耗时），异步与否：个人博客
 * portal QPS 很低，逐条同步 insert 没有性能问题，保持简单。
 * 落库异常只打日志、绝不影响请求本身。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitLogInterceptor implements HandlerInterceptor {

    private static final int MAX_UA_LENGTH = 255;

    private final VisitLogService visitLogService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 记录开始时间，afterCompletion 里算耗时
        request.setAttribute(VISIT_START_ATTR, System.currentTimeMillis());
        return true;
    }

    /** request attribute 名（公开常量，避免魔法字符串） */
    public static final String VISIT_START_ATTR = "iblog.visit.start";

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return;
        }
        try {
            Object start = request.getAttribute(VISIT_START_ATTR);
            long costMs = start instanceof Long s ? Math.max(0, (int) (System.currentTimeMillis() - s)) : 0;

            VisitLog row = new VisitLog();
            row.setPath(truncate(request.getRequestURI(), 128));
            row.setMethod(request.getMethod());
            row.setIp(ClientIp.of(request));
            row.setUserAgent(truncate(request.getHeader("User-Agent"), MAX_UA_LENGTH));
            row.setReferer(truncate(request.getHeader("Referer"), MAX_UA_LENGTH));
            row.setStatus(resolveStatus(ex));
            row.setCostMs((int) costMs);
            visitLogService.save(row);
        } catch (Exception e) {
            // 访问日志绝不能影响正常请求
            log.warn("访问日志落库失败 path={} err={}", request.getRequestURI(), e.getMessage());
        }
    }

    /**
     * 记录口径：只区分「请求处理有没有抛异常」。GlobalExceptionHandler 的约定是
     * 业务失败也返回 HTTP 200 + R.fail(code)（业务码在响应体里，这里不解析 body），
     * 所以无异常时记 200、有异常记 500，作为访问健康度的粗粒度信号足够。
     */
    private int resolveStatus(Exception ex) {
        return ex != null ? 500 : 200;
    }

    private String truncate(String value, int max) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
