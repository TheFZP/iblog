package com.iblog.module.paper.support;

import com.iblog.module.stock.model.KlineItem;
import com.iblog.module.stock.model.StockQuote;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalPredictScorerTest {

    private static List<KlineItem> trend(double start, double step, int n) {
        List<KlineItem> bars = new ArrayList<>();
        double close = start;
        for (int i = 0; i < n; i++) {
            KlineItem bar = new KlineItem();
            bar.setDate("2026-07-" + String.format("%02d", (i % 28) + 1));
            bar.setOpen(close - step / 2);
            bar.setHigh(close + 0.2);
            bar.setLow(close - 0.2);
            bar.setClose(close);
            bar.setVolume(i == n - 1 ? 2500 : 1000);
            bar.setTurnover(close * bar.getVolume());
            bars.add(bar);
            close += step;
        }
        return bars;
    }

    private static StockQuote quoteFor(List<KlineItem> bars, double changePct) {
        KlineItem last = bars.get(bars.size() - 1);
        KlineItem prev = bars.get(bars.size() - 2);
        StockQuote quote = new StockQuote();
        quote.setCode("600000");
        quote.setName("测试股份");
        quote.setPrice(last.getClose());
        quote.setPrevClose(prev.getClose());
        quote.setOpen(last.getClose() * 0.99);
        quote.setHigh(last.getClose() + 0.1);
        quote.setLow(last.getClose() - 0.3);
        quote.setVolume(last.getVolume());
        quote.setTurnover(8000);
        quote.setChangePct(changePct);
        quote.setTurnoverRate(6);
        quote.setFloatMarketCap(100);
        return quote;
    }

    @Test
    void risingTrendScoresHigherThanFallingTrend() {
        List<KlineItem> rising = trend(10, 0.1, 80);
        List<KlineItem> falling = trend(20, -0.1, 80);

        GlobalPredictScorer.Result up = GlobalPredictScorer.score(
                quoteFor(rising, 0.5), rising, null, 65);
        GlobalPredictScorer.Result down = GlobalPredictScorer.score(
                quoteFor(falling, -0.5), falling, null, 45);

        assertTrue(up.score() > down.score(), up.score() + " vs " + down.score());
        assertTrue(up.score() >= 50);
        assertFalse(up.reasons().isEmpty());
        assertTrue(up.reasons().contains("均线多头"));
        assertTrue(up.reasons().contains("MACD红柱"));
    }

    @Test
    void positiveNewsRaisesScoreAndNegativeLowersIt() {
        List<KlineItem> bars = trend(10, 0.1, 80);
        StockQuote quote = quoteFor(bars, 0.5);

        int base = GlobalPredictScorer.score(quote, bars, null, 55).score();
        int good = GlobalPredictScorer.score(quote, bars, 60, 55).score();
        int bad = GlobalPredictScorer.score(quote, bars, -60, 55).score();

        assertTrue(good >= base);
        assertTrue(bad < base);
    }

    @Test
    void missingSentimentAndShortHistoryDoNotCrash() {
        List<KlineItem> bars = trend(10, 0.1, 30);
        GlobalPredictScorer.Result result = GlobalPredictScorer.score(
                quoteFor(bars, 0.5), bars, null, Double.NaN);
        assertTrue(result.score() >= 0 && result.score() <= 100);
        assertEquals(GlobalPredictScorer.BASE_MAX_SCORE, result.maxScore());

        GlobalPredictScorer.Result empty = GlobalPredictScorer.score(
                quoteFor(bars, 0.5), List.of(), null, Double.NaN);
        assertEquals(0, empty.score());
        assertEquals(List.of("数据不足"), empty.reasons());
    }

    @Test
    void onePriceBarHasNoClosePositionBonus() {
        KlineItem flat = new KlineItem();
        flat.setDate("2026-11-14");
        flat.setOpen(10);
        flat.setHigh(10);
        flat.setLow(10);
        flat.setClose(10);
        assertEquals(Double.NaN, GlobalPredictScorer.closePosition(flat, 10), 1e-9);
    }

    @Test
    void overseasAndMacroShiftWholeMarket() {
        List<KlineItem> bars = trend(10, 0.1, 80);
        StockQuote quote = quoteFor(bars, 0.5);

        int neutral = GlobalPredictScorer.score(quote, bars, null, 55).score();
        int bullish = GlobalPredictScorer.score(quote, bars, null, 55,
                new GlobalPredictScorer.MarketContext(2.0, 50)).score();
        int bearish = GlobalPredictScorer.score(quote, bars, null, 55,
                new GlobalPredictScorer.MarketContext(-2.0, -50)).score();

        assertTrue(bullish > neutral, bullish + " vs " + neutral);
        assertTrue(bearish < neutral, bearish + " vs " + neutral);
    }

    @Test
    void marketContextJoinsTheDenominator() {
        List<KlineItem> bars = trend(10, 0.1, 80);
        StockQuote quote = quoteFor(bars, 0.5);

        GlobalPredictScorer.Result withContext = GlobalPredictScorer.score(quote, bars, null, 55,
                new GlobalPredictScorer.MarketContext(1.0, 10));
        assertEquals(GlobalPredictScorer.BASE_MAX_SCORE + GlobalPredictScorer.MARKET_MAX_BONUS,
                withContext.maxScore());
        assertTrue(withContext.score() >= 0 && withContext.score() <= 100);

        // 空 context 与旧版完全一致，避免调用方传了个空对象就把分数分布改了
        GlobalPredictScorer.Result emptyContext = GlobalPredictScorer.score(quote, bars, null, 55,
                new GlobalPredictScorer.MarketContext(null, null));
        assertEquals(GlobalPredictScorer.BASE_MAX_SCORE, emptyContext.maxScore());
        assertEquals(GlobalPredictScorer.score(quote, bars, null, 55).score(), emptyContext.score());
    }

    @Test
    void overseasPointsFollowBands() {
        assertEquals(5, GlobalPredictScorer.overseasPoints(1.5));
        assertEquals(3, GlobalPredictScorer.overseasPoints(1.49));
        assertEquals(3, GlobalPredictScorer.overseasPoints(0.5));
        assertEquals(0, GlobalPredictScorer.overseasPoints(0.49));
        assertEquals(0, GlobalPredictScorer.overseasPoints(-0.49));
        assertEquals(-3, GlobalPredictScorer.overseasPoints(-0.5));
        assertEquals(-5, GlobalPredictScorer.overseasPoints(-1.5));
        assertEquals(0, GlobalPredictScorer.overseasPoints(null));
    }

    @Test
    void macroPointsFollowBands() {
        assertEquals(3, GlobalPredictScorer.macroPoints(20));
        assertEquals(1, GlobalPredictScorer.macroPoints(19));
        assertEquals(0, GlobalPredictScorer.macroPoints(0));
        assertEquals(-1, GlobalPredictScorer.macroPoints(-1));
        assertEquals(-3, GlobalPredictScorer.macroPoints(-20));
        assertEquals(0, GlobalPredictScorer.macroPoints(null));
    }

    @Test
    void marketPointsCombineBothInputsAndCap() {
        assertEquals(0, GlobalPredictScorer.marketPoints(null));
        assertEquals(0, GlobalPredictScorer.marketPoints(
                new GlobalPredictScorer.MarketContext(null, null)));
        assertEquals(3, GlobalPredictScorer.marketPoints(
                new GlobalPredictScorer.MarketContext(0.6, null)));
        assertEquals(4, GlobalPredictScorer.marketPoints(
                new GlobalPredictScorer.MarketContext(0.6, 10)));
        // 5 + 3 超出封顶，截到 +6
        assertEquals(6, GlobalPredictScorer.marketPoints(
                new GlobalPredictScorer.MarketContext(2.0, 50)));
        assertEquals(-6, GlobalPredictScorer.marketPoints(
                new GlobalPredictScorer.MarketContext(-2.0, -50)));
    }

    @Test
    void detectsRecentLimitUp() {
        List<KlineItem> bars = new ArrayList<>(trend(10, 0.01, 19));
        KlineItem last = new KlineItem();
        last.setDate("2026-11-14");
        last.setClose(11);
        last.setHigh(11);
        last.setLow(10.9);
        last.setOpen(10.5);
        bars.add(last);
        // 前一根收盘 10.18 → 11 约 8%，不到主板 10%，换一根严格 10% 的
        KlineItem limit = new KlineItem();
        limit.setDate("2026-11-14");
        limit.setClose(11);
        limit.setHigh(11);
        limit.setLow(10.9);
        limit.setOpen(10.5);
        List<KlineItem> strict = new ArrayList<>(bars.subList(0, bars.size() - 2));
        KlineItem prev = new KlineItem();
        prev.setDate("2026-11-13");
        prev.setClose(10);
        prev.setHigh(10.1);
        strict.add(prev);
        strict.add(limit);

        assertTrue(GlobalPredictScorer.hasLimitUpRecently(strict, quoteFor(strict, 10), 20));
    }
}
