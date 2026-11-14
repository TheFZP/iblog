package com.iblog.module.stock.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iblog.common.result.R;
import com.iblog.config.IblogProperties;
import com.iblog.module.paper.support.PredictStatsCalculator;
import com.iblog.module.paper.support.TradeCalendar;
import com.iblog.module.stock.entity.StockPredictDaily;
import com.iblog.module.stock.service.NextDayPredictService;
import com.iblog.module.stock.service.PredictStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 全市场「次日看涨」榜单与准确率。
 *
 * <p>只读接口不触发上游；{@code POST /refresh} 仅当 {@code iblog.predict.manual-trigger-enabled=true}
 * 且当天 15:00 后可用，用于上线前 dry-run 验证数据是否合理。</p>
 */
@RestController
@RequestMapping("/api/stock/predict")
@RequiredArgsConstructor
public class NextDayPredictController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PredictStatsService statsService;
    private final NextDayPredictService predictService;
    private final IblogProperties properties;
    private final TradeCalendar calendar;

    /** 每日榜单；不传 date 时返回最近一期。 */
    @GetMapping("/daily")
    public R<DailyResponse> daily(@RequestParam(required = false) String date) {
        LocalDate target;
        try {
            target = date == null || date.isBlank() ? null : LocalDate.parse(date, DATE);
        } catch (DateTimeParseException ex) {
            return R.fail(400, "日期格式应为 yyyy-MM-dd");
        }
        PredictStatsService.DailyView view = statsService.daily(target);
        List<PredictItem> items = view.items().stream().map(this::toItem).toList();
        return R.ok(new DailyResponse(
                view.date() == null ? "" : view.date().format(DATE),
                view.generatedAt() == null ? "" : view.generatedAt().format(DATE_TIME),
                view.marketSentiment(), items));
    }

    /** 最近有榜单的交易日列表。 */
    @GetMapping("/dates")
    public R<List<DateItem>> dates(@RequestParam(defaultValue = "30") int limit) {
        List<DateItem> items = statsService.dates(limit).stream()
                .map(d -> new DateItem(d.date().format(DATE), d.count(), d.evaluated()))
                .toList();
        return R.ok(items);
    }

    /** 近 N 日准确率（可交易口径为主，同时给收盘对收盘）。 */
    @GetMapping("/stats")
    public R<PredictStatsCalculator.Stats> stats(@RequestParam(defaultValue = "30") int days) {
        return R.ok(statsService.stats(days));
    }

    /** 手工触发一次扫描（dryRun=true 不落库）。 */
    @PostMapping("/refresh")
    public R<RefreshResponse> refresh(@RequestParam(defaultValue = "false") boolean dryRun) {
        if (!properties.getPredict().isManualTriggerEnabled()) {
            return R.fail(403, "手工刷新未开启（iblog.predict.manual-trigger-enabled=false）");
        }
        LocalDate today = calendar.today();
        if (!calendar.isTradingDay(today)) {
            return R.fail(400, "今天不是交易日");
        }
        if (calendar.now().toLocalTime().isBefore(LocalTime.of(15, 0))) {
            return R.fail(400, "收盘后才允许刷新（15:00 之后）");
        }
        NextDayPredictService.GenerateResult result = predictService.generate(today, dryRun);
        return R.ok(new RefreshResponse(
                result.tradeDate().format(DATE), result.dryRun(), result.universe(), result.scored(),
                result.newsCovered(), result.items().size(), result.elapsedMs(),
                result.items().stream().map(this::toItem).toList()));
    }

    private PredictItem toItem(StockPredictDaily row) {
        return new PredictItem(
                row.getRankNo() == null ? 0 : row.getRankNo(),
                row.getCode(),
                row.getName(),
                row.getScore() == null ? 0 : row.getScore(),
                row.getProbability() == null ? 0 : row.getProbability().doubleValue(),
                row.getProbSource(),
                row.getCalibrationN() == null ? 0 : row.getCalibrationN(),
                row.getReason(),
                row.getNewsSummary(),
                parseNews(row.getNewsSources()),
                row.getChangePct() == null ? null : row.getChangePct().doubleValue(),
                row.getPrevClose() == null ? null : row.getPrevClose().doubleValue(),
                row.getBuyable(),
                row.getNextReturnPct(),
                row.getNextOpenReturnPct(),
                row.getHit(),
                row.getHitClose(),
                row.getEvalStatus());
    }

    private static List<NewsSource> parseNews(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isArray()) {
                return List.of();
            }
            List<NewsSource> list = new ArrayList<>();
            for (JsonNode node : root) {
                String url = node.path("url").asText("");
                list.add(new NewsSource(
                        node.path("title").asText(""),
                        url.startsWith("http://") || url.startsWith("https://") ? url : "",
                        node.path("source").asText(""),
                        node.path("date").asText(""),
                        node.path("scope").asText("stock")));
            }
            return list;
        } catch (Exception ex) {
            return List.of();
        }
    }

    public record NewsSource(String title, String url, String source, String publishedAt, String scope) {
    }

    public record PredictItem(int rank, String code, String name, int score, double probability,
                              String probSource, int calibrationN, String reasons, String newsSummary,
                              List<NewsSource> newsSources, Double changePct, Double prevClose,
                              Integer buyable, BigDecimal nextReturnPct, BigDecimal nextOpenReturnPct,
                              Integer hit, Integer hitClose, String evalStatus) {
    }

    public record DailyResponse(String date, String generatedAt, Double marketSentiment,
                                List<PredictItem> items) {
    }

    public record DateItem(String date, int count, int evaluated) {
    }

    public record RefreshResponse(String date, boolean dryRun, int universe, int scored,
                                  int newsCovered, int count, long elapsedMs,
                                  List<PredictItem> items) {
    }
}
