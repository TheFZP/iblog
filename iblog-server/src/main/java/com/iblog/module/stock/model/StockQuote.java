package com.iblog.module.stock.model;

import lombok.Data;

/**
 * 个股实时行情。
 *
 * <p>字段口径对齐桌面版 {@code src-tauri/src/types.rs::StockQuote}，前端可直接复用：
 * 成交量单位为「手」，成交额单位为「万元」，涨跌幅与振幅为百分数（如 1.22 表示 1.22%）。</p>
 */
@Data
public class StockQuote {

    /** 归一化代码（A 股 6 位 / 港股 5 位） */
    private String code;

    private String name;

    /** 现价 */
    private double price;

    /** 昨收 */
    private double prevClose;

    /** 今开 */
    private double open;

    /** 成交量（手） */
    private double volume;

    /** 成交额（万元） */
    private double turnover;

    /** 涨跌额 */
    private double change;

    /** 涨跌幅（%） */
    private double changePct;

    private double high;

    private double low;

    /** 换手率（%） */
    private double turnoverRate;

    /** 市盈率 */
    private double pe;

    /** 振幅（%）。注意腾讯字段下标是 [43]，[46] 是市净率，极易混淆 */
    private double amplitude;

    /** 市场标识 SH / SZ / BJ / HK */
    private String market;

    /** 币种符号 */
    private String currency;

    /**
     * 上游行情时间 yyyy-MM-dd HH:mm:ss（腾讯字段 [30]）。
     *
     * <p>这是判断「数据是否还在动」最硬的证据 —— 比本地时钟准，因为休市时它不会前进。
     * 前端用它做「数据新鲜度」提示，为空表示上游未给（港股分时接口偶尔缺）。</p>
     */
    private String quoteTime;

    /** 成交量（手），别名，便于前端不同写法取值 */
    public double getAmount() {
        return turnover;
    }

    /** 是否停牌 / 无成交（现价为 0） */
    public boolean isSuspended() {
        return price <= 0;
    }
}
