package com.iblog.module.stock.model;

import lombok.Data;

/**
 * 大盘指数。
 */
@Data
public class MarketIndex {

    private String code;

    private String name;

    private double price;

    private double change;

    private double changePct;

    /**
     * 上游行情时间 yyyy-MM-dd HH:mm:ss（腾讯字段 [30]）。
     *
     * <p>大盘开市意味着个股也在交易时段，前端拿它判断「市场是否还活着」，
     * 比本地时钟可靠：节假日/周末它不会前进。</p>
     */
    private String quoteTime;
}
