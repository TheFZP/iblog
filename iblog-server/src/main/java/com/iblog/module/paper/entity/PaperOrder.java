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
 * 模拟盘委托。
 *
 * <p>状态机：PENDING（挂单/待成交）→ FILLED（成交）/ CANCELLED（撤单）/
 * REJECTED（拒单）。市价单在下单时立即撮合，不会停留在 PENDING。</p>
 */
@Data
@TableName("paper_order")
public class PaperOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long accountId;

    private String code;

    private String name;

    /** BUY / SELL */
    private String side;

    /** MARKET / LIMIT */
    private String type;

    /** 限价；市价单为空 */
    private BigDecimal price;

    /** 委托数量（股） */
    private Integer quantity;

    /** 已成交数量 */
    private Integer filledQuantity;

    /** 成交均价 */
    private BigDecimal avgPrice;

    /** 成交金额（不含费用） */
    private BigDecimal amount;

    /** PENDING / FILLED / CANCELLED / REJECTED */
    private String status;

    /** 拒单原因（前端直接展示） */
    private String rejectReason;

    /** MANUAL / STRATEGY */
    private String source;

    /** 自动交易时的策略 id */
    private Long strategyId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 成交时间 */
    private LocalDateTime filledTime;
}
