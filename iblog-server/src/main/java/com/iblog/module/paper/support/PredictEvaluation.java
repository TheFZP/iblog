package com.iblog.module.paper.support;

import com.iblog.module.stock.model.KlineItem;

/**
 * 次日预测回填的纯计算（便于单测）。
 *
 * <p>两种收益同时算：{@code nextReturnPct} 是 T+1 收盘 vs T 收盘（榜单展示口径），
 * {@code nextOpenReturnPct} 是 T+1 开盘买 → 收盘卖（可交易主指标）。
 * 开盘一字涨停买不进，因此 {@code hit} 记为 0，而不是按开盘到收盘的纸面收益算命中。</p>
 */
public final class PredictEvaluation {

    private PredictEvaluation() {
    }

    public record Result(double nextOpen, double nextClose, double nextReturnPct,
                         double nextOpenReturnPct, boolean limitUpAtOpen, int hit, int hitClose) {
    }

    /**
     * @param prevClose T 日收盘价
     * @param bar       T+1 日 K
     * @param limitPct  该股票涨停幅度（10 / 20 / 5）
     */
    public static Result evaluate(double prevClose, KlineItem bar, double limitPct) {
        double nextClose = bar.getClose();
        double nextOpen = bar.getOpen() > 0 ? bar.getOpen() : nextClose;
        double nextReturnPct = (nextClose - prevClose) / prevClose * 100;
        double nextOpenReturnPct = (nextClose - nextOpen) / nextOpen * 100;
        double openChangePct = (nextOpen - prevClose) / prevClose * 100;
        boolean limitUpAtOpen = openChangePct >= limitPct - 0.15;
        int hit = limitUpAtOpen ? 0 : (nextOpenReturnPct > 0 ? 1 : 0);
        int hitClose = nextReturnPct > 0 ? 1 : 0;
        return new Result(nextOpen, nextClose, nextReturnPct, nextOpenReturnPct,
                limitUpAtOpen, hit, hitClose);
    }
}
