package com.iblog.module.stock.model;

import lombok.Data;

import java.util.List;

/**
 * 个股基本面快照：盈利质量 + 现金流 + 成长性。
 *
 * <p>数据源是东方财富 F10 报表接口（{@code datacenter-web.eastmoney.com}），
 * 与 {@link MoneyFlow}（push2 资金流）是完全不同的域名与口径。</p>
 *
 * <h3>单位约定（关键，前端与提示词都按这个口径说）</h3>
 * <ul>
 *   <li>金额字段（营收、净利、现金流）**统一为元**，不是万元也不是亿元。
 *       上游给的就是元，这里不换算 —— 换算一次就多一次出错机会，
 *       展示层按需除 1e8 显示「亿元」即可。</li>
 *   <li>比率字段（毛利率、净利率、ROE）为百分数，如 89.58 表示 89.58%。</li>
 *   <li>同比增速为百分数，如 12.34 表示同比增长 12.34%；无去年同期数据时为 null。</li>
 * </ul>
 *
 * <p>港股不支持（东财 F10 只覆盖 A 股/北交所），此时 {@link #available} 为 false。</p>
 */
@Data
public class FundamentalData {

    private String code;

    private String name;

    /** 是否取到数据；false 时其余业务字段为空，{@link #reason} 说明原因 */
    private boolean available;

    /** 不可用原因：{@code "hk"} 港股不支持 / {@code "network"} 上游不可达 / {@code "empty"} 上游无该股报表 */
    private String reason;

    /** 最新报告期（yyyy-MM-dd），如 2026-09-30 */
    private String reportDate;

    /** 报告期类型：一季报 / 中报 / 三季报 / 年报 */
    private String reportType;

    /** 营业总收入（元） */
    private double revenue;

    /** 归母净利润（元） */
    private double netProfit;

    /** 扣非归母净利润（元），东财 KCFJCXSYJLR —— 用于区分「主营赚钱」与「一次性收益」 */
    private double deductedNetProfit;

    /** 销售毛利率（%） */
    private double grossMargin;

    /** 销售净利率（%） */
    private double netMargin;

    /** 加权净资产收益率 ROE（%） */
    private double roe;

    /** 基本每股收益（元） */
    private double eps;

    /** 每股净资产（元） */
    private double bps;

    /** 每股经营现金流（元），东财 MGJYXJJE */
    private double cashPerShare;

    /** 经营活动现金流净额（元） */
    private double operatingCashFlow;

    /** 投资活动现金流净额（元） */
    private double investingCashFlow;

    /** 筹资活动现金流净额（元） */
    private double financingCashFlow;

    /**
     * 净现比 = 经营活动现金流净额 ÷ 归母净利润。
     *
     * <p>这是判断「利润是不是真钱」的核心指标：长期 &gt; 1 说明利润能收回现金，
     * 持续 &lt; 0.5 说明利润可能躺在应收账款里。净利润为负时不计算（返回 NaN）。</p>
     */
    private double cashToProfitRatio;

    /** 营业总收入同比（%）；无去年同期数据时 NaN */
    private double revenueYoY;

    /** 归母净利润同比（%）；无去年同期数据时 NaN */
    private double profitYoY;

    /** 最近若干期报表（新→旧），用于看盈利趋势与季节性 */
    private List<Period> periods;

    /**
     * 单期报表要点。
     *
     * <p>只放趋势判断需要的字段，不复制 {@link FundamentalData} 的全部内容 ——
     * 六期报表全字段塞进提示词会白占上下文。</p>
     */
    @Data
    public static class Period {

        /** 报告期（yyyy-MM-dd） */
        private String reportDate;

        /** 报告期类型 */
        private String reportType;

        /** 营业总收入（元） */
        private double revenue;

        /** 归母净利润（元） */
        private double netProfit;

        /** 销售毛利率（%） */
        private double grossMargin;

        /** 加权 ROE（%） */
        private double roe;

        /** 经营活动现金流净额（元） */
        private double operatingCashFlow;
    }
}
