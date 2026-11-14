package com.iblog.module.stock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 全市场「次日看涨」预测榜单（全局一份，不按账户）。
 *
 * <p>每个交易日收盘后生成，T 日预测 T+1 日上涨。次日收盘后回填
 * {@code nextOpen/nextClose/nextReturnPct/nextOpenReturnPct/hit/hitClose}，
 * 未回填前 {@code evalStatus = PENDING}。主指标 {@code hit} 使用可交易口径
 * （T+1 开盘买入 → T+1 收盘卖出收益 &gt; 0）。</p>
 */
@Data
@TableName("stock_predict_daily")
public class StockPredictDaily {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 预测生成日（T 日） */
    private LocalDate tradeDate;

    /** 榜单排名，从 1 开始 */
    private Integer rankNo;

    private String code;

    private String name;

    /** 最终分数 0~100（含新闻加减分） */
    private Integer score;

    /** 规则原始分 */
    private BigDecimal rawScore;

    /** 原始分满分，用于归一化 */
    private Integer maxScore;

    /** 次日上涨参考概率 % */
    private BigDecimal probability;

    /** HEURISTIC / CALIBRATED */
    private String probSource;

    /** 校准使用的历史样本数 */
    private Integer calibrationN;

    /** 命中理由（顿号分隔） */
    private String reason;

    private String modelVersion;

    private String newsSummary;

    /** 新闻情绪分 -100~100 */
    private Integer newsScore;

    /** 新闻置信度 0~1 */
    private BigDecimal newsConfidence;

    /** 新闻来源 JSON */
    private String newsSources;

    /** 因子快照 JSON */
    private String features;

    private BigDecimal marketSentiment;

    private BigDecimal prevClose;

    private BigDecimal changePct;

    private BigDecimal turnoverRate;

    /** 成交额（万元） */
    private BigDecimal amountWan;

    /** T 日收盘是否涨停 */
    private Integer limitUpAtClose;

    /** 是否可买 */
    private Integer buyable;

    private LocalDate nextTradeDate;

    private BigDecimal nextOpen;

    private BigDecimal nextClose;

    /** T+1 收盘 vs T 收盘 % */
    private BigDecimal nextReturnPct;

    /** T+1 开盘买 → 收盘卖 %（主指标） */
    private BigDecimal nextOpenReturnPct;

    /** T+1 当日涨跌幅 % */
    private BigDecimal nextDayChangePct;

    /** T+1 开盘是否涨停 */
    private Integer nextLimitUpAtOpen;

    /** 可交易口径是否命中 1=是 0=否 */
    private Integer hit;

    /** 收盘对收盘是否上涨 1=是 0=否 */
    private Integer hitClose;

    /** PENDING / EVALUATED / NO_DATA */
    private String evalStatus;

    private Integer evalRetry;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
