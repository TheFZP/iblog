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
 * 模拟盘信号 / 选股结果。
 *
 * <p>action：CANDIDATE（候选）/ BUY（买入信号）/ SELL（卖出信号）。
 * reason 与 score 是可复盘的人类可读理由（「为什么买这一只」）。</p>
 */
@Data
@TableName("paper_signal")
public class PaperSignal {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long accountId;

    private Long strategyId;

    private String code;

    private String name;

    /** CANDIDATE / BUY / SELL */
    private String action;

    private String reason;

    /** 规则得分 */
    private Integer score;

    private BigDecimal price;

    /** 交易日 yyyy-MM-dd */
    private String tradeDate;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
