package com.iblog.module.paper.dto;

import lombok.Data;

/**
 * 规则模板保存请求：新建时 name 必填，params 留空则用默认 22 项参数；
 * 更新时字段为 null 表示不修改。
 */
@Data
public class PaperRuleSaveRequest {

    private String name;

    private String params;
}
