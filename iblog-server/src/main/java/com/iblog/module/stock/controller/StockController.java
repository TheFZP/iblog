package com.iblog.module.stock.controller;

import com.iblog.common.result.R;
import com.iblog.module.stock.model.HotStockItem;
import com.iblog.module.stock.model.IntradayData;
import com.iblog.module.stock.model.KlineItem;
import com.iblog.module.stock.model.MarketIndex;
import com.iblog.module.stock.model.MoneyFlow;
import com.iblog.module.stock.model.MoneyFlowHistoryItem;
import com.iblog.module.stock.model.OrderBook;
import com.iblog.module.stock.model.SearchResult;
import com.iblog.module.stock.model.StockQuote;
import com.iblog.module.stock.service.EastmoneyService;
import com.iblog.module.stock.service.HotListService;
import com.iblog.module.stock.service.TencentService;
import com.iblog.module.stock.support.StockCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 股票行情接口（公开访问）。
 *
 * <p>这些数据原本由桌面版 stock-analysis 的 Rust 后端直连腾讯/同花顺获取，
 * 网页版无法在浏览器里直接调用（CORS + GBK 编码 + 反爬头），因此改由本服务代理。</p>
 *
 * <p>路径统一挂在 {@code /api/stock} 下，与博客业务接口隔离。</p>
 */
@Slf4j
@io.swagger.v3.oas.annotations.tags.Tag(name = "股票行情")
@RestController
@RequestMapping("/api/stock")
@RequiredArgsConstructor
public class StockController {

    private final TencentService tencentService;
    private final HotListService hotListService;
    private final EastmoneyService eastmoneyService;

    /** 首页展示的主要指数（上证 / 深证 / 创业板 / 沪深300 / 科创50 / 中证500） */
    private static final List<String> INDEX_CODES = List.of(
            "sh000001", "sz399001", "sz399006", "sh000300", "sh000688", "sh000905"
    );

    @Operation(summary = "单只行情")
    @GetMapping("/quote")
    public R<StockQuote> quote(@Parameter(description = "股票代码，A 股 6 位 / 港股 5 位")
                               @RequestParam String code) {
        return R.ok(tencentService.fetchQuote(code));
    }

    @Operation(summary = "批量行情")
    @GetMapping("/quotes")
    public R<List<StockQuote>> quotes(
            @Parameter(description = "逗号分隔的股票代码")
            @RequestParam String codes) {
        List<String> list = splitCodes(codes);
        if (list.isEmpty()) {
            return R.fail(400, "codes 不能为空");
        }
        return R.ok(tencentService.fetchQuotesBatch(list));
    }

    @Operation(summary = "搜索股票")
    @GetMapping("/search")
    public R<List<SearchResult>> search(
            @Parameter(description = "关键词：代码或名称")
            @RequestParam String keyword) {
        return R.ok(tencentService.search(keyword));
    }

    @Operation(summary = "K 线")
    @GetMapping("/kline")
    public R<List<KlineItem>> kline(
            @RequestParam String code,
            @Parameter(description = "day / week / month / m5 / m15 / m30 / m60，默认 day")
            @RequestParam(required = false, defaultValue = "day") String period) {
        return R.ok(tencentService.fetchKline(code, period));
    }

    @Operation(summary = "分时")
    @GetMapping("/intraday")
    public R<IntradayData> intraday(@RequestParam String code) {
        return R.ok(tencentService.fetchIntraday(code));
    }

    @Operation(summary = "五档盘口")
    @GetMapping("/orderbook")
    public R<OrderBook> orderBook(@RequestParam String code) {
        return R.ok(tencentService.fetchOrderBook(code));
    }

    @Operation(summary = "大盘指数")
    @GetMapping("/indices")
    public R<List<MarketIndex>> indices() {
        List<MarketIndex> result = new ArrayList<>();
        for (String code : INDEX_CODES) {
            try {
                result.add(tencentService.fetchIndex(code));
            } catch (Exception e) {
                // 单个指数失败不影响其余（兜底用代码占位，避免前端整块空白）
                log.warn("指数 {} 获取失败：{}", code, e.getMessage());
                MarketIndex fallback = new MarketIndex();
                fallback.setCode(code.replaceFirst("^(sh|sz)", ""));
                fallback.setName(fallbackName(code));
                result.add(fallback);
            }
        }
        return R.ok(result);
    }

    @Operation(summary = "同花顺热榜")
    @GetMapping("/hotlist")
    public R<List<HotStockItem>> hotList() {
        return R.ok(hotListService.fetchHotList());
    }

    @Operation(summary = "主力资金流向（当日分档快照）",
            description = "单位万元。非交易时段 current=false，此时 date 是最近一个交易日；"
                    + "上游不提供该股数据时 unavailable=true（前端应显示空态而不是 0）")
    @GetMapping("/moneyflow")
    public R<MoneyFlow> moneyFlow(@Parameter(description = "股票代码") @RequestParam String code) {
        return R.ok(eastmoneyService.fetchMoneyFlow(code));
    }

    @Operation(summary = "主力资金流向历史（按日期升序）",
            description = "单位万元，默认 30 个交易日，上限 120")
    @GetMapping("/moneyflow/history")
    public R<List<MoneyFlowHistoryItem>> moneyFlowHistory(
            @Parameter(description = "股票代码") @RequestParam String code,
            @Parameter(description = "交易日条数，默认 30")
            @RequestParam(required = false, defaultValue = "30") Integer limit) {
        return R.ok(eastmoneyService.fetchMoneyFlowHistory(code, limit));
    }

    @Operation(summary = "股票代码归一化（调试用）")
    @GetMapping("/normalize")
    public R<java.util.Map<String, String>> normalize(@RequestParam String code) {
        String normalized = StockCode.normalize(code);
        return R.ok(java.util.Map.of(
                "code", normalized,
                "market", StockCode.marketOf(normalized),
                "tencent", StockCode.toTencent(normalized),
                "currency", StockCode.currencySymbol(normalized)
        ));
    }

    private List<String> splitCodes(String codes) {
        List<String> list = new ArrayList<>();
        if (codes == null || codes.isBlank()) {
            return list;
        }
        for (String s : codes.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                list.add(t);
            }
        }
        return list;
    }

    /** 指数兜底名称：接口失败时至少显示正确名字，而不是空白 */
    private String fallbackName(String code) {
        return switch (code) {
            case "sh000001" -> "上证指数";
            case "sz399001" -> "深证成指";
            case "sz399006" -> "创业板指";
            case "sh000300" -> "沪深300";
            case "sh000688" -> "科创50";
            case "sh000905" -> "中证500";
            default -> code;
        };
    }
}
