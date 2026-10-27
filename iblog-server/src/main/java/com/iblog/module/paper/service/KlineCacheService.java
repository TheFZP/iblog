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

    /** 两年 ≈ 486 个交易日；腾讯 fqkline 上限 640 根，这里取 500 留出余量。 */
    private static final int DEEP_BARS = 500;
    /** 深历史在内存里缓存 6 小时，避免每轮选股都为同一批票重复拉 500 根。 */
    private static final long DEEP_TTL_MS = 6 * 60 * 60 * 1000L;
    private static final int DEEP_CACHE_MAX = 600;

    private final StockKlineMapper klineMapper;
    private final TencentService tencentService;
    private final TradeCalendar calendar;

    /** 单只股票的最近回源时间，避免同一次选股里反复打上游。 */
    private final Map<String, Long> lastFetchAt = new ConcurrentHashMap<>();

    /** 两年深历史（只进内存，不落库）：每日选股复用，隔日或 6 小时后重新拉。 */
    private final Map<String, DeepBars> deepCache = new ConcurrentHashMap<>();

    private record DeepBars(List<KlineItem> bars, long fetchedAt) {
    }

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

    /**
     * 读取两年深历史（约 500 根日 K），用于「近 N 日创两年新高」判断。
     *
     * <p>不落库：stock_kline 只缓存最近 120 根给常规打分/回测用，
     * 深历史只在选股时短期复用；拉取失败退回普通缓存，让调用方拿到多少算多少
     * （数据不足时该因子不生效，不会误杀候选）。</p>
     */
    public List<KlineItem> deepLoad(String rawCode, int minBars) {
        String code = StockCode.normalize(rawCode);
        if (code.isEmpty()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        int need = Math.min(Math.max(minBars, MAX_BARS), DEEP_BARS);
        DeepBars hit = deepCache.get(code);
        // 交易时段内今天那根还在变（见 isStaleItems），但深历史一次 500 根很贵，
        // 这里跟 load 的回源节流同口径：每分钟最多刷一次，避免每轮选股都为同一批票重拉。
        boolean throttled = hit != null && calendar.isTradingTimeNow()
                && now - hit.fetchedAt() < REFETCH_INTERVAL_MS;
        if (hit != null && hit.bars().size() >= need && now - hit.fetchedAt() < DEEP_TTL_MS
                && (throttled || !isStaleItems(hit.bars()))) {
            return hit.bars();
        }
        try {
            List<KlineItem> fetched = tencentService.fetchKline(code, PERIOD_DAY, DEEP_BARS);
            if (fetched != null && !fetched.isEmpty()) {
                List<KlineItem> sorted = new ArrayList<>(fetched);
                sorted.sort(Comparator.comparing(KlineItem::getDate));
                if (deepCache.size() >= DEEP_CACHE_MAX) {
                    deepCache.clear();
                }
                deepCache.put(code, new DeepBars(sorted, now));
                return sorted;
            }
        } catch (RuntimeException ex) {
            log.warn("拉取两年深K失败 code={}", code, ex);
        }
        if (hit != null) {
            return hit.bars();
        }
        return load(code, need);
    }

    /**
     * 深历史是否过期：交易日且最后一根不是今天 → 重新拉；
     * 交易时段内今天那根还在变（见 {@link #isStale(List)}），同样视为过期。
     */
    private boolean isStaleItems(List<KlineItem> bars) {
        if (bars == null || bars.isEmpty()) {
            return true;
        }
        if (!calendar.isTradingDay(calendar.today())) {
            return false;
        }
        String lastDate = bars.get(bars.size() - 1).getDate();
        if (!calendar.todayText().equals(lastDate)) {
            return true;
        }
        return calendar.isTradingTimeNow();
    }

    private List<StockKline> select(String code) {
        return klineMapper.selectList(Wrappers.<StockKline>lambdaQuery()
                .eq(StockKline::getCode, code)
                .eq(StockKline::getPeriod, PERIOD_DAY)
                .orderByAsc(StockKline::getDate));
    }

    /**
     * 日 K 是否需要回源。
     *
     * <p>除了「最后一根不是今天」之外，**盘中今天那根的成交量还在变**：开盘一分钟就把当天
     * 那一行写进库后，如果只按日期判断是否过期，这一行会整个交易日停在开盘时的累计量，
     * 量比 / 当日涨幅 / 均线全都被这份假数据带偏（线上曾出现量比算成 0.3 的现象）。
     * 所以交易时段内今天那根一律视为需要刷新，实际频率由 {@code lastFetchAt} 的
     * {@link #REFETCH_INTERVAL_MS} 兜住，每只票每分钟最多回源一次；收盘后当天那根已定型，不再回源。</p>
     */
    private boolean isStale(List<StockKline> cached) {
        if (cached.isEmpty()) {
            return true;
        }
        if (!calendar.isTradingDay(calendar.today())) {
            return false;
        }
        String lastDate = cached.get(cached.size() - 1).getDate();
        if (!calendar.todayText().equals(lastDate)) {
            return true;
        }
        return calendar.isTradingTimeNow();
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
                // update_time 显式刷新：strictUpdateFill 遇到已有值会跳过，不显式设就看不出来
                // 这一行到底有没有在盘中更新（排查「量比异常」时踩过这个坑）
                existing.setUpdateTime(java.time.LocalDateTime.now());
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
