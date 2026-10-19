package com.iblog.module.stock.model;

import lombok.Data;

/**
 * 单日资金流向历史条目（东方财富 daykline，单位：万元）。
 *
 * <p>口径对齐桌面版 {@code src-tauri/src/types.rs::MoneyFlowHistoryItem}。
 * <b>列表按日期升序</b>（旧 → 新），与图表的时间轴要求一致，不要在前端反转。</p>
 */
@Data
public class MoneyFlowHistoryItem {

    /** 交易日 yyyy-MM-dd */
    private String date;

    /** 主力净流入（万元） */
    private double mainNetInflow;

    /** 超大单净流入（万元） */
    private double superLargeNet;

    /** 大单净流入（万元） */
    private double largeNet;

    /** 中单净流入（万元） */
    private double mediumNet;

    /** 小单净流入（万元） */
    private double smallNet;

    /** 收盘价 */
    private double close;
}
