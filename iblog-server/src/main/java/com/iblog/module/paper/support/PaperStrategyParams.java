package com.iblog.module.paper.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 策略参数（存在 paper_strategy.params 的 JSON 文本）。
 *
 * <p>一个模拟盘账户一套规则，改完下一轮 tick / 选股刷新即生效。
 * 解析失败或字段缺失都回落到默认值，避免一条脏配置把自动交易整个停掉；
 * 数值统一钳制在合理区间，防止 0 权重、除零或异常阈值。</p>
 *
 * <p>字段名与前端「高级规则」表单一一对应，改名要同步前端。</p>
 */
@Data
public class PaperStrategyParams {

    /** 入选最低分 */
    private double minScore = 60;
    /** 止损（%，浮亏达到该值卖出） */
    private double stopLossPct = 8;
    /** 止盈（%） */
    private double takeProfitPct = 15;
    /** 最长持有交易日数 */
    private int maxHoldDays = 10;

    /** 候选池大小（同花顺热榜前 N 只） */
    private int universeSize = 20;
    /** 每日最多买入候选数 */
    private int maxCandidates = 5;
    /** 选股刷新间隔（秒） */
    private int selectionRefreshSec = 300;

    /** 均线多头排列得分 */
    private int maTrendScore = 25;
    /** 仅站上 20 日线得分 */
    private int ma20Score = 10;
    /** MACD 红柱得分 */
    private int macdScore = 20;
    /** 放量得分 */
    private int volumeRatioScore = 15;
    /** 主力净流入得分 */
    private int moneyFlowScore = 20;
    /** 主力净占比达标额外得分 */
    private int moneyFlowPctScore = 5;
    /** RSI 健康区间得分 */
    private int rsiScore = 10;
    /** 当日温和上涨得分 */
    private int changeScore = 5;
    /** 热榜排名上升得分 */
    private int hotRankScore = 5;

    /** 量比阈值（>=） */
    private double volumeRatioMin = 1.2;
    /** 主力净占比阈值（%，>=） */
    private double moneyFlowPctMin = 5;
    /** RSI 下限（含） */
    private double rsiMin = 40;
    /** RSI 上限（含） */
    private double rsiMax = 70;
    /** 当日涨幅下限（%，不含） */
    private double changeMinPct = 0;
    /** 当日涨幅上限（%，不含） */
    private double changeMaxPct = 9;

    /** 换手率在 [min,max] 区间的得分 */
    private int turnoverScore = 5;
    /** 换手率下限（%，含；0 表示不限） */
    private double turnoverMinPct = 3;
    /** 换手率上限（%，含；0 表示不限） */
    private double turnoverMaxPct = 15;

    /** 流通市值在 [min,max] 区间的得分 */
    private int floatCapScore = 5;
    /** 流通市值下限（亿元，含；0 表示不限） */
    private double floatCapMinYi = 50;
    /** 流通市值上限（亿元，含；0 表示不限） */
    private double floatCapMaxYi = 200;

    /** 分时全程站在均价线上方的得分 */
    private int vwapScore = 10;
    /** 「全程站在均价线上方」的分钟占比阈值（0.5~1） */
    private double vwapAboveRatio = 0.95;

    /** 近 N 个交易日出现过涨停的得分 */
    private int limitUpScore = 10;
    /** 涨停记忆回看的交易日数 */
    private int limitUpWithinDays = 20;

    /** 昨日首板（昨日涨停且前一日未涨停）的得分 */
    private int firstBoardScore = 0;
    /** 连板高度硬过滤：0=不限；1=仅昨日首板；N=昨日涨停且连板数不超过 N */
    private int boardHeightMax = 0;

    /** 次日开盘强制卖出（隔夜策略用：T+1 可卖后的第一个 tick 直接市价清仓，不看止损止盈） */
    private boolean sellAtOpen = false;
    /** 自动买入时间窗起点（HH:mm，含） */
    private String buyStartTime = "09:30";
    /** 自动买入时间窗终点（HH:mm，含） */
    private String buyEndTime = "15:00";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    public static PaperStrategyParams parse(ObjectMapper mapper, String json) {
        PaperStrategyParams params = new PaperStrategyParams();
        if (json == null || json.isBlank()) {
            return params;
        }
        try {
            JsonNode node = mapper.readTree(json);
            params.minScore = number(node, "minScore", params.minScore, 0, 200);
            params.stopLossPct = number(node, "stopLossPct", params.stopLossPct, 0.2, 50);
            params.takeProfitPct = number(node, "takeProfitPct", params.takeProfitPct, 0.2, 200);
            params.maxHoldDays = integer(node, "maxHoldDays", params.maxHoldDays, 1, 250);

            params.universeSize = integer(node, "universeSize", params.universeSize, 1, 100);
            params.maxCandidates = integer(node, "maxCandidates", params.maxCandidates, 1, 20);
            params.selectionRefreshSec = integer(node, "selectionRefreshSec", params.selectionRefreshSec, 30, 3600);

            params.maTrendScore = integer(node, "maTrendScore", params.maTrendScore, 0, 100);
            params.ma20Score = integer(node, "ma20Score", params.ma20Score, 0, 100);
            params.macdScore = integer(node, "macdScore", params.macdScore, 0, 100);
            params.volumeRatioScore = integer(node, "volumeRatioScore", params.volumeRatioScore, 0, 100);
            params.moneyFlowScore = integer(node, "moneyFlowScore", params.moneyFlowScore, 0, 100);
            params.moneyFlowPctScore = integer(node, "moneyFlowPctScore", params.moneyFlowPctScore, 0, 100);
            params.rsiScore = integer(node, "rsiScore", params.rsiScore, 0, 100);
            params.changeScore = integer(node, "changeScore", params.changeScore, 0, 100);
            params.hotRankScore = integer(node, "hotRankScore", params.hotRankScore, 0, 100);

            params.volumeRatioMin = number(node, "volumeRatioMin", params.volumeRatioMin, 0.1, 10);
            params.moneyFlowPctMin = number(node, "moneyFlowPctMin", params.moneyFlowPctMin, 0, 100);
            params.rsiMin = number(node, "rsiMin", params.rsiMin, 0, 99);
            params.rsiMax = number(node, "rsiMax", params.rsiMax, params.rsiMin + 0.1, 100);
            params.changeMinPct = number(node, "changeMinPct", params.changeMinPct, -10, 49.9);
            params.changeMaxPct = number(node, "changeMaxPct", params.changeMaxPct, params.changeMinPct + 0.1, 50);

            params.turnoverScore = integer(node, "turnoverScore", params.turnoverScore, 0, 100);
            params.turnoverMinPct = number(node, "turnoverMinPct", params.turnoverMinPct, 0, 100);
            params.turnoverMaxPct = number(node, "turnoverMaxPct", params.turnoverMaxPct, 0, 100);
            params.floatCapScore = integer(node, "floatCapScore", params.floatCapScore, 0, 100);
            params.floatCapMinYi = number(node, "floatCapMinYi", params.floatCapMinYi, 0, 100000);
            params.floatCapMaxYi = number(node, "floatCapMaxYi", params.floatCapMaxYi, 0, 100000);
            params.vwapScore = integer(node, "vwapScore", params.vwapScore, 0, 100);
            params.vwapAboveRatio = number(node, "vwapAboveRatio", params.vwapAboveRatio, 0.5, 1);
            params.limitUpScore = integer(node, "limitUpScore", params.limitUpScore, 0, 100);
            params.limitUpWithinDays = integer(node, "limitUpWithinDays", params.limitUpWithinDays, 1, 120);
            params.firstBoardScore = integer(node, "firstBoardScore", params.firstBoardScore, 0, 100);
            params.boardHeightMax = integer(node, "boardHeightMax", params.boardHeightMax, 0, 10);

            params.sellAtOpen = bool(node, "sellAtOpen", params.sellAtOpen);
            params.buyStartTime = timeText(node, "buyStartTime", params.buyStartTime);
            params.buyEndTime = timeText(node, "buyEndTime", params.buyEndTime);
        } catch (Exception ignored) {
            // 脏 JSON 用默认值
        }
        return params;
    }

    /** 新建策略时写入的默认参数 JSON（字段完整，前端可以直接展示）。 */
    public static String defaultJson(ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(new PaperStrategyParams());
        } catch (Exception ex) {
            return "{}";
        }
    }

    /** 规范化后的 JSON，保存回数据库避免脏字段长期存在。 */
    public String toJson(ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(this);
        } catch (Exception ex) {
            return "{}";
        }
    }

    /** 当前时间是否在自动买入时间窗内；时间配置非法或跨零点时视为不限制。 */
    public boolean inBuyWindow(LocalDateTime at) {
        if (at == null) {
            return true;
        }
        try {
            LocalTime start = LocalTime.parse(buyStartTime, TIME_FORMAT);
            LocalTime end = LocalTime.parse(buyEndTime, TIME_FORMAT);
            if (!start.isBefore(end)) {
                return true;
            }
            LocalTime now = at.toLocalTime();
            return !now.isBefore(start) && !now.isAfter(end);
        } catch (RuntimeException ex) {
            return true;
        }
    }

    private static boolean bool(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            return value.asInt() != 0;
        }
        String text = value.asText("").trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    private static String timeText(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        String text = value == null || value.isNull() ? fallback : value.asText("").trim();
        try {
            return LocalTime.parse(text, TIME_FORMAT).format(TIME_FORMAT);
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static double number(JsonNode node, String field, double fallback, double min, double max) {
        JsonNode value = node.get(field);
        double parsed = value == null || value.isNull() ? fallback : value.asDouble(fallback);
        if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
            return fallback;
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private static int integer(JsonNode node, String field, int fallback, int min, int max) {
        JsonNode value = node.get(field);
        int parsed = value == null || value.isNull() ? fallback : value.asInt(fallback);
        return Math.max(min, Math.min(max, parsed));
    }
}
