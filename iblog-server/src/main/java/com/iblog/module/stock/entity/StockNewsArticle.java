package com.iblog.module.stock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 参与「次日看涨」预测的新闻原文存档。
 *
 * <p>目前免费新闻源没有历史库，预测当天的新闻不存下来，之后就无法回测。
 * 按 {@code (trade_date, code, url_hash)} 去重，保留 {@code iblog.predict.news-keep-days} 天。</p>
 */
@Data
@TableName("stock_news_article")
public class StockNewsArticle {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 抓取所属交易日 */
    private LocalDate tradeDate;

    private String code;

    private String name;

    private String title;

    /** 原文链接（仅 http/https） */
    private String url;

    /** MD5(url) */
    private String urlHash;

    private String source;

    private LocalDateTime publishedAt;

    private String snippet;

    /** stock / industry / market */
    private String scope;

    /** 该条新闻情绪分 -100~100 */
    private Integer sentimentScore;

    /** 所属扫描批次标识 */
    private String batch;

    private LocalDateTime fetchedAt;
}
