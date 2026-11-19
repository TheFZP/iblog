package com.iblog.module.paper.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 模拟盘账户概览。
 */
@Data
@Builder
public class PaperAccountVO {

    private Long accountId;

    private String name;

    /** 展示排序（主账户=1） */
    private Integer sortNo;

    /** 是否主账户：不传 accountId 时接口默认落到它 */
    private Boolean primary;

    private BigDecimal initialCash;

    /** 可用现金 */
    private BigDecimal cash;

    private BigDecimal marketValue;

    private BigDecimal totalAsset;

    private BigDecimal totalProfit;

    /** 累计收益率（%） */
    private BigDecimal totalProfitPct;

    /** 今日盈亏（相对上一交易日收盘总资产） */
    private BigDecimal todayProfit;

    /** 今日收益率（%） */
    private BigDecimal todayProfitPct;

    /** 今日是否触发日亏熔断：触发后自动交易只减仓、不加仓 */
    private Boolean dailyLossBrake;

    /** 日亏熔断阈值（%，正数） */
    private BigDecimal dailyLossLimitPct;

    private Integer positionCount;

    /** 0 关 1 开 */
    private Integer autoTrade;

    /** 最近一个交易日 */
    private String tradeDate;
}
