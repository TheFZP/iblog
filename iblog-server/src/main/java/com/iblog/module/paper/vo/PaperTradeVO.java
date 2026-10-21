package com.iblog.module.paper.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模拟盘成交记录。
 */
@Data
@Builder
public class PaperTradeVO {

    private Long id;

    private String code;

    private String name;

    private String side;

    /** 成交价 */
    private BigDecimal price;

    private Integer quantity;

    /** 成交金额（不含费用） */
    private BigDecimal amount;

    private BigDecimal commission;

    private BigDecimal stampTax;

    private BigDecimal transferFee;

    private BigDecimal totalFee;

    /** 成交日期 yyyy-MM-dd */
    private String tradeDate;

    private LocalDateTime tradeTime;
}
