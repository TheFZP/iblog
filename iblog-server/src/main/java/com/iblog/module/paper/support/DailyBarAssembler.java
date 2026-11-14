package com.iblog.module.paper.support;

import com.iblog.module.stock.model.KlineItem;
import com.iblog.module.stock.model.StockQuote;

import java.util.ArrayList;
import java.util.List;

/**
 * 用收盘行情修正/补齐当日日 K（纯函数）。
 *
 * <p>为什么需要：{@code KlineCacheService} 收盘后认为「今天那根已经定型」不再回源，
 * 而 14:50 之后的选股可能已经把当天那根写进库了。15:30 的全市场扫描若直接读库，
 * 拿到的可能是 14:50 的残缺 bar（成交量、最高/最低都不完整），量比和收盘位置会全偏。</p>
 *
 * <p>同时做一次复权一致性检查：K 线是前复权，行情昨收是不复权。两者偏差超过 2%
 * 通常意味着期间发生了除权除息，调用方应回源单只 K 线，而不是继续用错位的数据打分。</p>
 */
public final class DailyBarAssembler {

    /** 昨收偏差超过该比例（%）视为复权错位。 */
    public static final double PREV_CLOSE_TOLERANCE_PCT = 2.0;

    private DailyBarAssembler() {
    }

    /**
     * @param bars             修正后的日 K（升序，最后一根是 tradeDate）
     * @param reliable         昨收与缓存倒数第二根是否对得上
     * @param prevCloseDiffPct 偏差百分比（无对比数据时为 0）
     */
    public record Result(List<KlineItem> bars, boolean reliable, double prevCloseDiffPct) {
    }

    /**
     * 用 {@code quote} 修正 {@code cached} 中 T 日的 bar：已有则覆盖，没有则追加。
     *
     * @param tradeDate T 日（yyyy-MM-dd）
     */
    public static Result assemble(String tradeDate, List<KlineItem> cached, StockQuote quote) {
        List<KlineItem> bars = new ArrayList<>();
        if (cached != null) {
            for (KlineItem item : cached) {
                if (item == null || item.getDate() == null || item.getDate().isEmpty()) {
                    continue;
                }
                if (item.getDate().compareTo(tradeDate) <= 0) {
                    bars.add(copy(item));
                }
            }
        }
        if (quote == null || quote.getPrice() <= 0) {
            return new Result(bars, false, 0);
        }

        KlineItem today = barOf(tradeDate, quote);
        if (!bars.isEmpty() && tradeDate.equals(bars.get(bars.size() - 1).getDate())) {
            bars.set(bars.size() - 1, today);
        } else {
            bars.add(today);
        }

        double diffPct = 0;
        boolean reliable = true;
        if (bars.size() >= 2 && quote.getPrevClose() > 0) {
            double prevClose = bars.get(bars.size() - 2).getClose();
            diffPct = Math.abs(prevClose - quote.getPrevClose()) / quote.getPrevClose() * 100;
            reliable = diffPct <= PREV_CLOSE_TOLERANCE_PCT;
        }
        return new Result(bars, reliable, diffPct);
    }

    /** 用收盘行情构造当日 bar；行情字段缺失时用现价兜底。 */
    public static KlineItem barOf(String tradeDate, StockQuote quote) {
        KlineItem item = new KlineItem();
        item.setDate(tradeDate);
        item.setOpen(quote.getOpen() > 0 ? quote.getOpen() : quote.getPrice());
        item.setHigh(quote.getHigh() > 0 ? quote.getHigh() : quote.getPrice());
        item.setLow(quote.getLow() > 0 ? quote.getLow() : quote.getPrice());
        item.setClose(quote.getPrice());
        item.setVolume(quote.getVolume());
        //行情 turnover 单位是万元，KlineItem 用元
        item.setTurnover(quote.getTurnover() * 10_000);
        return item;
    }

    private static KlineItem copy(KlineItem src) {
        KlineItem item = new KlineItem();
        item.setDate(src.getDate());
        item.setOpen(src.getOpen());
        item.setHigh(src.getHigh());
        item.setLow(src.getLow());
        item.setClose(src.getClose());
        item.setVolume(src.getVolume());
        item.setTurnover(src.getTurnover());
        return item;
    }
}
