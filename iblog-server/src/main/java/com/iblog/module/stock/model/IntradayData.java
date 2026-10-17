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
}
