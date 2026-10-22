package com.iblog.module.paper.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 规则模板视图。active 表示该模板参数与查询账户当前生效参数一致。
 */
@Data
@Builder
public class PaperRuleVO {

    private Long id;

    private String name;

    /** 参数 JSON 文本（已规范化） */
    private String params;

    private Boolean active;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
