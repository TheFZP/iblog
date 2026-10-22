package com.iblog.module.paper.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 新建模拟盘请求：名称留空自动编号，初始资金留空用服务端默认值。
 */
@Data
public class PaperAccountCreateRequest {

    private String name;

    private BigDecimal initialCash;
}
