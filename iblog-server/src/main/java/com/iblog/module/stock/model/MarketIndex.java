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
}
