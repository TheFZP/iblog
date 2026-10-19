package com.iblog.module.stock.model;

import lombok.Data;

import java.util.List;

/**
 * 分时全量数据。
 */
@Data
public class IntradayData {

    private List<IntradayItem> items;

    /** 昨收，分时图基准线 */
    private double preClose;

    /** 交易日 yyyyMMdd */
    private String date;

    private String code;

    /**
     * 上游行情时间 yyyy-MM-dd HH:mm:ss（腾讯 qt 字段 [30]）。为空表示上游未给。
     *
     * <p>分时曲线自带「最后一根时间」，但那是 K 线粒度（分钟），
     * 这个才是报价自身的时刻，用于判断数据新鲜度。</p>
     */
    private String quoteTime;
}
