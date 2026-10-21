package com.iblog.module.paper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.iblog.module.paper.support.TradeCalendar;
import com.iblog.module.stock.entity.StockKline;
import com.iblog.module.stock.mapper.StockKlineMapper;
import com.iblog.module.stock.model.KlineItem;
import com.iblog.module.stock.service.TencentService;
import com.iblog.module.stock.support.StockCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日 K 缓存：先读 stock_kline，缺数据/不是今天才回源腾讯。
 *
 * <p>为什么缓存：选股一次要看 20 只 × 60 根日 K，每次都回源会被上游限流；
 * 而且信号胜率统计需要历史数据落在库里才可复盘（表注释也是这个目的）。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KlineCacheService {

    private static final String PERIOD_DAY = "day";
    private static final int MAX_BARS = 120;
    private static final long REFETCH_INTERVAL_MS = 60_000L;

    private final StockKlineMapper klineMapper;
    private final TencentService tencentService;
    private final TradeCalendar calendar;

    /** 单只股票的最近回源时间，避免同一次选股里反复打上游。 */
    private final Map<String, Long> lastFetchAt = new ConcurrentHashMap<>();

    /** 读取至少 minBars 根日 K（升序）。 */
    public List<KlineItem> load(String rawCode, int minBars) {
        String code = StockCode.normalize(rawCode);
        if (code.isEmpty()) {
            return List.of();
        }
        List<StockKline> cached = select(code);
        int need = Math.min(Math.max(minBars, 30), MAX_BARS);
        if (cached.size() >= need && !isStale(cached)) {
            return cached.stream().map(this::toItem).toList();
        }
        Long last = lastFetchAt.get(code);
        if (last != null && System.currentTimeMillis() - last < REFETCH_INTERVAL_MS && !cached.isEmpty()) {
            return cached.stream().map(this::toItem).toList();
        }
        try {
            List<KlineItem> fetched = tencentService.fetchKline(code, PERIOD_DAY);
            if (fetched != null && !fetched.isEmpty()) {
                List<KlineItem> sorted = new ArrayList<>(fetched);
                sorted.sort(Comparator.comparing(KlineItem::getDate));
                upsert(code, cached, sorted);
                lastFetchAt.put(code, System.currentTimeMillis());
                return sorted;
            }
        } catch (RuntimeException ex) {
            log.warn("拉取日K失败 code={}", code, ex);
        }
        lastFetchAt.put(code, System.currentTimeMillis());
        return cached.stream().map(this::toItem).toList();
    }

    private List<StockKline> select(String code) {
        return klineMapper.selectList(Wrappers.<StockKline>lambdaQuery()
                .eq(StockKline::getCode, code)
                .eq(StockKline::getPeriod, PERIOD_DAY)
                .orderByAsc(StockKline::getDate));
    }

    /** 交易日且缓存最后一根不是今天 → 需要回源；非交易日缓存就是最新的。 */
    private boolean isStale(List<StockKline> cached) {
        if (cached.isEmpty()) {
            return true;
        }
        if (!calendar.isTradingDay(calendar.today())) {
            return false;
        }
        String lastDate = cached.get(cached.size() - 1).getDate();
        return !calendar.todayText().equals(lastDate);
    }

    private void upsert(String code, List<StockKline> cached, List<KlineItem> fetched) {
        Map<String, StockKline> byDate = new HashMap<>();
        for (StockKline item : cached) {
            byDate.put(item.getDate(), item);
        }
        String latestDate = fetched.get(fetched.size() - 1).getDate();
        for (KlineItem item : fetched) {
            StockKline existing = byDate.get(item.getDate());
            if (existing == null) {
                klineMapper.insert(toEntity(code, item));
            } else if (item.getDate().equals(latestDate)) {
                // 当天那根 K 在盘中会变，每次回源都覆盖
                apply(existing, item);
                klineMapper.updateById(existing);
            }
        }
    }

    private StockKline toEntity(String code, KlineItem item) {
        StockKline entity = new StockKline();
        entity.setCode(code);
        entity.setPeriod(PERIOD_DAY);
        entity.setDate(item.getDate());
        apply(entity, item);
        return entity;
    }

    private void apply(StockKline entity, KlineItem item) {
        entity.setOpen(price(item.getOpen()));
        entity.setHigh(price(item.getHigh()));
        entity.setLow(price(item.getLow()));
        entity.setClose(price(item.getClose()));
        entity.setVolume(price(item.getVolume()));
        entity.setTurnover(price(item.getTurnover()));
    }

    private KlineItem toItem(StockKline entity) {
        KlineItem item = new KlineItem();
        item.setDate(entity.getDate());
        item.setOpen(toDouble(entity.getOpen()));
        item.setHigh(toDouble(entity.getHigh()));
        item.setLow(toDouble(entity.getLow()));
        item.setClose(toDouble(entity.getClose()));
        item.setVolume(toDouble(entity.getVolume()));
        item.setTurnover(toDouble(entity.getTurnover()));
        return item;
    }

    private BigDecimal price(double value) {
        return BigDecimal.valueOf(value).setScale(4, java.math.RoundingMode.HALF_UP);
    }

    private double toDouble(BigDecimal value) {
        return value == null ? 0 : value.doubleValue();
    }
}
