package com.iblog.module.paper.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模拟盘尾盘预测候选与胜率明细。
 *
 * <p>每次尾盘预测（14:30 / 14:50）跑完把候选落一条；当日收盘结算（15:05）时用当日
 * 收盘价回填 {@code closePrice}/{@code closePct}/{@code hit}，据此判断「预测涨得对不对」。
 * 未回填（当日结算还没跑）时 {@code closePct} 为 NULL，视为结果待定。</p>
 */
@Data
@TableName("paper_predict_candidate")
public class PaperPredictCandidate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long accountId;

    private Long strategyId;

    /** 预测交易日 yyyy-MM-dd */
    private String tradeDate;

    /** 预测轮次：14:30 / 14:50 */
    private String roundTime;

    /** 归一化代码 */
    private String code;

    private String name;

    /** 预测分 */
    private Integer score;

    /** 命中理由（原因前缀「尾盘预测：」） */
    private String reason;

    /** 预测时现价 */
    private BigDecimal predictPrice;

    /** 当日收盘价（结算时回填） */
    private BigDecimal closePrice;

    /** 当日收盘涨幅%（涨 &gt; 0 记命中） */
    private BigDecimal closePct;

    /** 是否命中：1=涨 0=跌 2=平；NULL=待回填 */
    private Integer hit;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}