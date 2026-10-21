package com.iblog.module.paper.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 模拟盘持仓。
 */
@Data
@Builder
public class PaperPositionVO {

    private String code;

    private String name;

    /** 总持仓（股） */
    private Integer quantity;

    /** 可卖数量（T+1，卖出后减少，收盘结算后转正） */
    private Integer availableQuantity;

    private BigDecimal costPrice;

    private BigDecimal lastPrice;

    private BigDecimal marketValue;

    private BigDecimal profit;

    /** 盈亏比例（%） */
    private BigDecimal profitPct;

    /** 建仓日期 yyyy-MM-dd */
    private String openDate;

    /** ¥ / HK$ */
    private String currency;
}
