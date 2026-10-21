package com.iblog.module.paper.service;

import com.iblog.common.exception.BizException;
import com.iblog.module.stock.model.StockQuote;
import com.iblog.module.stock.service.TencentService;
import com.iblog.module.stock.support.StockCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模拟盘专用行情读取，带 4 秒内存缓存。
 *
 * <p>为什么缓存放在内存而不是 Redis：模拟盘一次页面刷新只需 1~3 个代码，
 * 单个实例（线上就是一台后端）用 ConcurrentHashMap 足够，还能避免序列化开销。
 * 引擎 tick 间隔 15 秒，缓存 4 秒既不会读到过旧价格，也不会把上游打爆。</p>
 */
@Service
@RequiredArgsConstructor
public class PaperQuoteService {

    private static final long CACHE_TTL_MS = 4000L;

    private final TencentService tencentService;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public StockQuote quote(String rawCode) {
        String code = StockCode.normalize(rawCode);
        if (code.isEmpty()) {
            throw new BizException(400, "股票代码不能为空");
        }
        Cached cached = cache.get(code);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < CACHE_TTL_MS) {
            return cached.quote();
        }
        try {
            StockQuote quote = tencentService.fetchQuote(code);
            if (quote != null && quote.getPrice() > 0) {
                cache.put(code, new Cached(quote, now));
            }
            if (quote != null) {
                return quote;
            }
        } catch (RuntimeException ex) {
            if (cached != null) {
                return cached.quote();
            }
            throw new BizException(500, "行情获取失败，请稍后重试");
        }
        if (cached != null) {
            return cached.quote();
        }
        throw new BizException(500, "未获取到该股票的行情");
    }

    /**
     * 批量行情。批量接口失败时退化为单只拉取；仍失败的代码不放在结果里，
     * 调用方用持仓的 lastPrice 兜底，不让一次上游抖动把整页拖垮。
     */
    public Map<String, StockQuote> quotes(Collection<String> rawCodes) {
        Map<String, StockQuote> result = new LinkedHashMap<>();
        if (rawCodes == null || rawCodes.isEmpty()) {
            return result;
        }
        List<String> codes = rawCodes.stream()
                .map(StockCode::normalize)
                .filter(code -> !code.isEmpty())
                .distinct()
                .toList();
        long now = System.currentTimeMillis();
        List<String> missing = new ArrayList<>();
        for (String code : codes) {
            Cached cached = cache.get(code);
            if (cached != null && now - cached.at() < CACHE_TTL_MS) {
                result.put(code, cached.quote());
            } else {
                missing.add(code);
            }
        }
        if (missing.isEmpty()) {
            return result;
        }
        List<String> stillMissing = new ArrayList<>(missing);
        try {
            List<StockQuote> batch = tencentService.fetchQuotesBatch(missing);
            if (batch != null) {
                for (StockQuote quote : batch) {
                    if (quote == null || quote.getCode() == null) {
                        continue;
                    }
                    String code = StockCode.normalize(quote.getCode());
                    cache.put(code, new Cached(quote, now));
                    result.put(code, quote);
                    stillMissing.remove(code);
                }
            }
        } catch (RuntimeException ignored) {
            // 批量失败继续走单只兜底
        }
        for (String code : stillMissing) {
            try {
                StockQuote quote = tencentService.fetchQuote(code);
                if (quote != null && quote.getPrice() > 0) {
                    cache.put(code, new Cached(quote, now));
                    result.put(code, quote);
                }
            } catch (RuntimeException ignored) {
                // 单只也失败：跳过，调用方用旧价兜底
            }
        }
        return result;
    }

    public void invalidate() {
        cache.clear();
    }

    private record Cached(StockQuote quote, long at) {
    }
}
