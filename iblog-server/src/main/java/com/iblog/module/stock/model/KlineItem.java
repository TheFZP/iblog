package com.iblog.module.stock.model;

import lombok.Data;

/**
 * K 线单根。
 *
 * <p>日/周/月 K 来自腾讯 fqkline 接口（<b>前复权</b> 120 根，仅 6 个字段无成交额）；
 * 分钟 K 来自 mkline（320 根，<b>不复权</b>，港股不支持）。</p>
 */
@Data
public class KlineItem {

    /** 日/周/月：yyyy-MM-dd；分钟：yyyyMMddHHmm */
    private String date;

    private double open;

    private double close;

    private double high;

    private double low;

    /** 成交量（手） */
    private double volume;

    /** 成交额（元）。日/周/月接口无此字段，由 volume×close 兜底 */
    private double turnover;
}
