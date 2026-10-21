package com.iblog.module.paper.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 模拟盘每日净值快照。
 */
@Data
@Builder
public class PaperNavVO {

    /** yyyy-MM-dd */
    private String date;

    private BigDecimal cash;

    private BigDecimal marketValue;

    private BigDecimal totalAsset;

    /** 当日盈亏（相对上一交易日） */
    private BigDecimal dayPnl;

    /** 当日收益率（%） */
    private BigDecimal dayPnlPct;

    /** 累计盈亏 */
    private BigDecimal totalPnl;

    /** 累计收益率（%） */
    private BigDecimal totalPnlPct;
}
