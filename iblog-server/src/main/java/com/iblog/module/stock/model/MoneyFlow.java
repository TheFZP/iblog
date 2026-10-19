package com.iblog.module.stock.model;

import lombok.Data;

/**
 * 个股主力资金流向（当日分档快照）。
 *
 * <p>口径对齐桌面版 {@code src-tauri/src/types.rs::MoneyFlow}，前端可直接复用：
 * <b>净流入单位统一为「万元」</b>，占比为百分数（如 9.23 表示 9.23%）。</p>
 *
 * <p>数据源是东方财富 fflow 接口，<b>只有 net 与 pct 两组值</b>，没有「买入额/卖出额」明细 ——
 * 净流入 = 买入额 - 卖出额，因此这里的数值可以为负。</p>
 */
@Data
public class MoneyFlow {

    /** 净流入对应的交易日（yyyy-MM-dd）。盘中取不到当日时，这里是历史最近一个交易日 */
    private String date;

    /** 主力净流入（万元）。注意：东财口径的「主力」= 超大单 + 大单 */
    private double mainNetInflow;

    /** 主力净占比（%） */
    private double mainNetPct;

    /** 超大单净流入（万元） */
    private double superLargeNet;

    /** 超大单净占比（%） */
    private double superLargePct;

    /** 大单净流入（万元） */
    private double largeNet;

    /** 大单净占比（%） */
    private double largePct;

    /** 中单净流入（万元） */
    private double mediumNet;

    /** 中单净占比（%） */
    private double mediumPct;

    /** 小单净流入（万元） */
    private double smallNet;

    /** 小单净占比（%） */
    private double smallPct;

    /**
     * 该快照是否为「当前交易日的盘中实时数据」。
     *
     * <p>{@code false} 表示上游当日数据尚未生成（集合竞价、非交易日），
     * 本次返回的是最近一个交易日收盘后的定稿数据，前端应显示 {@link #getDate()}
     * 而不是"今日"。</p>
     */
    private boolean current;

    /** 上游暂不提供该股资金流（如部分北交所标的） */
    private boolean unavailable;

    /**
     * {@link #isUnavailable()} 的原因：{@code "network"} 表示上游连不上（过一会儿再试就好），
     * 其它情况为空串（上游确实没有这只标的的资金流）。
     *
     * <p>前端据此区分「暂时取不到，请稍后再看」与「该股暂无资金流数据」—— 两种都说成
     * 「暂无数据」会让用户以为是自己选错了股票。</p>
     */
    private String reason;

    /** {@link #reason} 的取值：上游网络不可达 */
    public static final String REASON_NETWORK = "network";
}