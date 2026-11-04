package com.iblog.module.stock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 个股新闻情绪分（选股「新闻资讯分析」因子的落库缓存）。
 *
 * <p>score -100~100（正利好 / 负利空），confidence 0~1（来源越官方越高）。
 * 按「交易日 + 代码」唯一，每天定时扫描覆盖；数据库里这份主要为了可回溯，
 * 选股主链路读的是 {@code AiNewsScoreService} 里的内存缓存，不直接查库。</p>
 */
@Data
@TableName("stock_news_score")
public class StockNewsScore {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate tradeDate;

    private String code;

    private String name;

    /** 新闻情绪分 -100~100 */
    private Integer score;

    /** 置信度 0~1 */
    private BigDecimal confidence;

    /** 利好 / 中性 / 利空 */
    private String label;

    /** 话题标签（逗号分隔） */
    private String tags;

    /** 一句结论摘要 */
    private String summary;

    /** 参与评分的新闻标题/来源/时间 JSON */
    private String headlines;

    private Integer sourceCount;

    /** 打分方式：ai / keyword */
    private String model;

    private LocalDateTime updateTime;
}