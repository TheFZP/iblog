package com.iblog.module.stock.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 同花顺热榜条目。
 */
@Data
public class HotStockItem {

    private String code;

    private String name;

    /** 热度值（字符串，源站原样返回） */
    private String rate;

    /** 涨跌幅（%） */
    private double riseAndFall;

    /** 排名变化 */
    private long hotRankChg;

    /** 当前排名序号 */
    private long order;

    /** 市场标识：17=沪 33=深 */
    private long market;

    /** 概念标签 */
    private List<String> tags = new ArrayList<>();

    /** 人气标签 */
    private String popularityTag;
}
