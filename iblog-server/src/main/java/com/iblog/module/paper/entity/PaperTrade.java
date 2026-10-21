package com.iblog.module.paper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模拟盘成交（交割单）。费用三项分开存，便于展示与统计。
 */
@Data
@TableName("paper_trade")
public class PaperTrade {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long accountId;

    private Long orderId;

    private String code;

    private String name;

    /** BUY / SELL */
    private String side;

    private BigDecimal price;

    /** 成交数量（股） */
    private Integer quantity;

    /** 成交金额（不含费用） */
    private BigDecimal amount;

    /** 佣金 */
    private BigDecimal commission;

    /** 印花税（仅卖出） */
    private BigDecimal stampTax;

    /** 过户费 */
    private BigDecimal transferFee;

    /** 费用合计 */
    private BigDecimal totalFee;

    /** 交易日 yyyy-MM-dd */
    private String tradeDate;

    /** 成交时间 */
    private LocalDateTime tradeTime;
}
