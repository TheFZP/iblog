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
 * 模拟盘每日净值快照。uk(accountId, tradeDate) 保证重复结算只更新不重复插。
 */
@Data
@TableName("paper_nav_daily")
public class PaperNavDaily {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long accountId;

    /** 交易日 yyyy-MM-dd */
    private String tradeDate;

    private BigDecimal cash;

    private BigDecimal marketValue;

    private BigDecimal totalAsset;

    /** 当日盈亏 */
    private BigDecimal dayPnl;

    /** 当日收益率（%） */
    private BigDecimal dayPnlPct;

    /** 累计盈亏 */
    private BigDecimal totalPnl;

    /** 累计收益率（%） */
    private BigDecimal totalPnlPct;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
