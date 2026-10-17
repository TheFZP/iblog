package com.iblog.module.stock.model;

import lombok.Data;

/**
 * 分时单分钟。
 *
 * <p>volume 为「本分钟」成交量（手，已由累计量差分得出）；turnover 为本分钟成交额（元）。
 * 已剔除交易时段外的零星盘后成交。</p>
 */
@Data
public class IntradayItem {

    /** HH:mm */
    private String time;

    private double price;

    /** 累计均价 = 累计成交额 / 累计股数 */
    private double avgPrice;

    /** 本分钟成交量（手） */
    private double volume;

    /** 本分钟成交额（元） */
    private double turnover;

    /** 累计 VWAP */
    private double vwap;
}
