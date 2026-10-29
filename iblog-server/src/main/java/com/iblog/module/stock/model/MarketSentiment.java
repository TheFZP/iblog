package com.iblog.module.stock.model;

import lombok.Data;

/**
 * 市场情绪快照（全市场维度的「赚钱效应」温度计）。
 *
 * <p>为什么模拟盘要用它：单只票的技术形态只说明「这只票自己能涨」，
 * 但在全市场普跌、跌停家数上百的日子里，任何形态都会被系统性砸掉。
 * 把大盘情绪量化成一个 0~100 的分，才能做到「情绪好时才做多、冰点时先跑」。</p>
 *
 * <p>字段全部来自东财公开接口（涨跌家数 + 涨跌停池 + 炸板池 + 指数涨幅），
 * 不猜测、不使用研报口径，任何人打开东财行情页都能复算出同样的数。</p>
 */
@Data
public class MarketSentiment {

    /** 是否拿到了实时数据；false 时 score 只是中性占位值，调用方不应据此拦单 */
    private boolean available;

    /** 是否降级沿用上一次成功的快照（上游临时失败时不清零，避免因一次网络抖动直接改策略行为） */
    private boolean stale;

    /** 情绪分 0~100（越高越热） */
    private double score;

    /** 情绪等级：冰点 / 低迷 / 中性 / 活跃 / 亢奋 */
    private String level;

    /** 上涨家数（沪深两市合计） */
    private int upCount;

    /** 下跌家数 */
    private int downCount;

    /** 平盘家数 */
    private int flatCount;

    /** 涨停家数 */
    private int limitUpCount;

    /** 跌停家数 */
    private int limitDownCount;

    /** 炸板家数（曾涨停后打开） */
    private int brokenCount;

    /** 炸板率（%）= 炸板 ÷（涨停 + 炸板）；0 表示无样本 */
    private double brokenRate;

    /** 沪深两市成交额（亿元） */
    private double amountYi;

    /** 主要指数平均涨跌幅（%） */
    private double indexChangePct;

    /** 数据所属交易日（yyyy-MM-dd） */
    private String tradeDate;

    /** 快照时刻（HH:mm:ss） */
    private String updatedAt;
}
