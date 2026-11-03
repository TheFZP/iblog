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
    /**
     * 盘前预选：9:25 集合竞价结束后先用日 K 粗筛一批候选，9:30 开盘后再用完整数据复核。
     *
     * <p>预选阶段没有盘中分时/成交数据，所以会自动跳过所有依赖分时的硬条件与加分项，
     * 只保留日 K、均线、资金流、热榜等盘前可用的因子。默认开启；旧规则 JSON 里
     * 没有这个字段，解析后同样跟随默认值，不需要逐账户手工打开。</p>
     */
    private boolean preMarketPreselect = true;

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

    /** 近 N 个交易日内创两年新高的得分 */
    private int newHighScore = 0;
    /** 新高的「近 N 个交易日」窗口 */
    private int newHighWithinDays = 5;
    /** 缩量上涨（收涨且较前一日缩量）的得分 */
    private int shrinkVolumeScore = 0;
    /** 缩量上涨：当日成交量 ≤ 前一日成交量 × 该系数（0.1~1） */
    private double shrinkVolumeMaxRatio = 0.9;

    /** 次日开盘强制卖出（隔夜策略用：T+1 可卖后的第一个 tick 直接市价清仓，不看止损止盈） */
    private boolean sellAtOpen = false;
    /** 自动买入时间窗起点（HH:mm，含） */
    private String buyStartTime = "09:30";
    /** 自动买入时间窗终点（HH:mm，含） */
    private String buyEndTime = "15:00";

    /** 严格硬过滤总开关：下列硬条件（阈值 >0 或开关打开的那几条）不满足就直接剔除，不参与打分 */
    private boolean strictFilters = false;
    /** 现价 / 一年内最低价 的上限（>0 时生效，越小越接近底部） */
    private double lowRatioMax = 0;
    /** 候选按「现价 / 一年内最低价」升序排列（替代默认按得分降序） */
    private boolean sortByLowRatioAsc = false;
    /** 早盘自由流通换手率下限（%）：[openTurnoverStart, openTurnoverEnd] 成交量 ÷ 自由流通股 × 100 */
    private double openTurnoverMinPct = 0;
    /** 早盘换手率统计窗口起点（HH:mm，含） */
    private String openTurnoverStart = "09:25";
    /** 早盘换手率统计窗口终点（HH:mm，含） */
    private String openTurnoverEnd = "09:40";
    /**
     * 集合竞价换手率下限（%）：9:25–9:30 集合竞价成交量 ÷ 自由流通股 × 100；0 表示不限。
     *
     * <p>与 {@link #openTurnoverMinPct} 的区别：那个是「竞价 + 开盘后一段」的累计量，
     * 这个只看竞价那一下（9:25 撮合的量），专门用来筛「竞价就被资金盯上」的票。
     * 数据仍取自分钟分时的 09:30 那根 —— 腾讯把集合竞价单独放在这一根里
     * （09:31 才是连续竞价第一分钟），所以取这一根就等价于竞价量，不需要额外数据源。</p>
     */
    private double auctionTurnoverMinPct = 0;
    /** 全天实际换手率下限（%）：成交量 ÷ 自由流通股 × 100；0 表示不限 */
    private double realTurnoverMinPct = 0;
    /** 现价必须高于今日开盘价 */
    private boolean requireAboveOpen = false;
    /** 现价必须高于今日分时均价 */
    private boolean requireAboveVwap = false;
    /** 量比硬下限（0 表示不限） */
    private double hardVolumeRatioMin = 0;
    /** 5 日均价 / 20 日均价 硬下限（0 表示不限，>1 表示短期均线在长期之上） */
    private double ma5OverMa20Min = 0;
    /** 5 日均量 / 20 日均量 硬下限（0 表示不限，>1 表示近期放量） */
    private double vol5OverVol20Min = 0;
    /** 总市值下限（亿元；0 表示不限） */
    private double totalCapMinYi = 0;
    /** 近 N 个交易日内不得出现涨停（0 表示不限） */
    private int noLimitUpWithinDays = 0;
    /** 股东户数较上一期减少（筹码集中度变小/集中） */
    private boolean requireHolderShrink = false;
    /** 盈利要求：0=不限；1=最近报告期盈利；2=二季报或年报盈利；3=二季报、二季报预告或年报盈利 */
    private int profitRequire = 0;
    /** 只保留当前可用资金买得起的标的（按各板最小申报单位估算一手成本） */
    private boolean affordableOnly = false;

    // ---------- 市场情绪 / 热度 ----------

    /**
     * 情绪维度总开关。
     *
     * <p>关掉时下面所有情绪字段都不生效，引擎行为与加这个功能之前完全一致
     * ——老规则的参数 JSON 里没有这些字段，解析出来是默认值，
     * 而默认值里开关是 false，所以升级不会突然改变已有规则的行为。</p>
     */
    private boolean sentimentEnabled = false;
    /** 情绪分低于该值时不开新仓（0 表示不限制） */
    private double sentimentBuyMinScore = 0;
    /** 情绪分低于该值时直接清仓（0 表示不限制；应小于等于买入下限） */
    private double sentimentExitScore = 0;
    /** 候选必须来自热榜前 N 名（0 表示不限） */
    private int hotRankTopN = 0;
    /** 候选的热榜排名必须较上次上升（热度在升温） */
    private boolean requireHotRankUp = false;
    /** 个股热度分项满分（按热榜排名换算：越靠前越高） */
    private int hotnessScore = 0;
    /** 情绪越好，「当日涨幅上限」在此基础上额外放宽到该值（0 表示不额外放宽） */
    private double sentimentChangeMaxPct = 0;

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
            params.preMarketPreselect = bool(node, "preMarketPreselect", params.preMarketPreselect);

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
            params.newHighScore = integer(node, "newHighScore", params.newHighScore, 0, 100);
            params.newHighWithinDays = integer(node, "newHighWithinDays", params.newHighWithinDays, 1, 10);
            params.shrinkVolumeScore = integer(node, "shrinkVolumeScore", params.shrinkVolumeScore, 0, 100);
            params.shrinkVolumeMaxRatio = number(node, "shrinkVolumeMaxRatio", params.shrinkVolumeMaxRatio, 0.1, 1);

            params.sellAtOpen = bool(node, "sellAtOpen", params.sellAtOpen);
            params.buyStartTime = timeText(node, "buyStartTime", params.buyStartTime);
            params.buyEndTime = timeText(node, "buyEndTime", params.buyEndTime);

            params.strictFilters = bool(node, "strictFilters", params.strictFilters);
            params.lowRatioMax = number(node, "lowRatioMax", params.lowRatioMax, 0, 100);
            params.sortByLowRatioAsc = bool(node, "sortByLowRatioAsc", params.sortByLowRatioAsc);
            params.openTurnoverMinPct = number(node, "openTurnoverMinPct", params.openTurnoverMinPct, 0, 100);
            params.openTurnoverStart = timeText(node, "openTurnoverStart", params.openTurnoverStart);
            params.openTurnoverEnd = timeText(node, "openTurnoverEnd", params.openTurnoverEnd);
            params.auctionTurnoverMinPct = number(node, "auctionTurnoverMinPct",
                    params.auctionTurnoverMinPct, 0, 100);
            params.realTurnoverMinPct = number(node, "realTurnoverMinPct", params.realTurnoverMinPct, 0, 100);
            params.requireAboveOpen = bool(node, "requireAboveOpen", params.requireAboveOpen);
            params.requireAboveVwap = bool(node, "requireAboveVwap", params.requireAboveVwap);
            params.hardVolumeRatioMin = number(node, "hardVolumeRatioMin", params.hardVolumeRatioMin, 0, 100);
            params.ma5OverMa20Min = number(node, "ma5OverMa20Min", params.ma5OverMa20Min, 0, 100);
            params.vol5OverVol20Min = number(node, "vol5OverVol20Min", params.vol5OverVol20Min, 0, 100);
            params.totalCapMinYi = number(node, "totalCapMinYi", params.totalCapMinYi, 0, 100000);
            params.noLimitUpWithinDays = integer(node, "noLimitUpWithinDays", params.noLimitUpWithinDays, 0, 120);
            params.requireHolderShrink = bool(node, "requireHolderShrink", params.requireHolderShrink);
            params.profitRequire = integer(node, "profitRequire", params.profitRequire, 0, 3);
            params.affordableOnly = bool(node, "affordableOnly", params.affordableOnly);

            params.sentimentEnabled = bool(node, "sentimentEnabled", params.sentimentEnabled);
            params.sentimentBuyMinScore = number(node, "sentimentBuyMinScore",
                    params.sentimentBuyMinScore, 0, 100);
            params.sentimentExitScore = number(node, "sentimentExitScore", params.sentimentExitScore, 0, 100);
            params.hotRankTopN = integer(node, "hotRankTopN", params.hotRankTopN, 0, 100);
            params.requireHotRankUp = bool(node, "requireHotRankUp", params.requireHotRankUp);
            params.hotnessScore = integer(node, "hotnessScore", params.hotnessScore, 0, 100);
            params.sentimentChangeMaxPct = number(node, "sentimentChangeMaxPct",
                    params.sentimentChangeMaxPct, 0, 50);
            // 清仓线不能高于开仓线，否则会出现「刚买就触发清仓」的自相矛盾配置
            if (params.sentimentExitScore > params.sentimentBuyMinScore) {
                params.sentimentExitScore = params.sentimentBuyMinScore;
            }
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

    /** 分时时间点（HH:mm）是否落在早盘换手率统计窗口内；窗口非法时视为全部收录。 */
    public boolean inOpenTurnoverWindow(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) {
            return false;
        }
        try {
            LocalTime start = LocalTime.parse(openTurnoverStart, TIME_FORMAT);
            LocalTime end = LocalTime.parse(openTurnoverEnd, TIME_FORMAT);
            if (!start.isBefore(end)) {
                return true;
            }
            LocalTime at = LocalTime.parse(hhmm.trim(), TIME_FORMAT);
            return !at.isBefore(start) && !at.isAfter(end);
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
