package com.iblog.module.stock.model;

import lombok.Data;

import java.util.List;

/**
 * 个股行业分析（数据来自东方财富 HSF10 的 IndustryAnalysis 接口）。
 *
 * <p>对应桌面版 stock-analysis-prh 的 {@code IndustryData}。</p>
 */
@Data
public class IndustryData {

    /** 行业名称（如「白酒」）。取不到时为空串，前端降级显示「行业分析」。 */
    private String industryName;

    /** 个股相对沪深300 的阶段涨幅对比。 */
    private List<MarketPerformance> marketPerformance;

    /** 同行业公司的营收排名。 */
    private List<RevenueRanking> revenueRanking;

    /** 行业平均指标（营收、净利润、总市值），用于对比个股在行业中的位置。 */
    private IndustryAverage industryAverage;

    /** 数据来源的报表期，如「2026年中报」。 */
    private String reportType;

    @Data
    public static class MarketPerformance {
        /** 1=近1月 2=近3月 3=近6月 4=今年以来。 */
        private int timeType;
        /** 个股区间涨跌幅（%）。 */
        private double changeRate;
        /** 沪深300 同期涨跌幅（%）。 */
        private double hs300ChangeRate;
    }

    @Data
    public static class RevenueRanking {
        private String stockCode;
        private String stockName;
        /** 营业总收入（元）。 */
        private double totalOperateIncome;
        /** 营收在行业内的排名（1 为最大）。 */
        private int totalOperateIncomeRank;
        /** 净利润（元）。 */
        private double netProfit;
        private int netProfitRank;
        /** 总市值（元）。 */
        private double totalCap;
    }

    @Data
    public static class IndustryAverage {
        private double totalOperateIncome;
        private double netProfit;
        private double totalCap;
    }
}
