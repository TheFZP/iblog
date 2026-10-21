package com.iblog.module.paper.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模拟盘持仓。
 *
 * <p>{@code availableQuantity} 是 T+1 的载体：当天买入只增加 quantity，
 * 收盘结算时 quantity 抄到 availableQuantity，第二天才能卖。</p>
 */
@Data
@TableName("paper_position")
public class PaperPosition {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long accountId;

    /** 归一化代码 */
    private String code;

    private String name;

    /** 持仓数量（股） */
    private Integer quantity;

    /** 可卖数量（T+1） */
    private Integer availableQuantity;

    /** 摊薄成本价（含买入费用） */
    private BigDecimal costPrice;

    /** 最新价 */
    private BigDecimal lastPrice;

    /** 市值 */
    private BigDecimal marketValue;

    /** 浮动盈亏 */
    private BigDecimal profit;

    /** 浮动盈亏率（%） */
    private BigDecimal profitPct;

    /** 首次建仓日 yyyy-MM-dd */
    private String openDate;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
