package com.iblog.module.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 官网访问日志：记录 /api/portal/** 的每次调用。
 *
 * <p>官网是 SPA，页面本身由 nginx 直接吐静态文件、不进 Spring；
 * 但每次页面打开必然触发 /api/portal/site 等接口调用，
 * 所以在 API 层逐条记录即可等价反映官网的访问情况。</p>
 */
@Data
@TableName("blog_visit_log")
public class VisitLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 请求路径（不含 query） */
    private String path;

    /** HTTP 方法 */
    private String method;

    /** 客户端 IP（取 X-Forwarded-For 首段） */
    private String ip;

    /** 浏览器 UA（截断 255） */
    private String userAgent;

    /** 来源页（截断 255） */
    private String referer;

    /** 响应业务码（R.code，200 为成功；与后台接口的约定一致，业务失败 HTTP 仍是 200） */
    private Integer status;

    /** 耗时（毫秒） */
    private Integer costMs;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
