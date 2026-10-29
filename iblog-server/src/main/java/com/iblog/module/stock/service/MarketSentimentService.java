package com.iblog.module.stock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iblog.module.stock.model.MarketSentiment;
import com.iblog.module.stock.support.MarketSentimentRule;
import com.iblog.module.stock.support.StockHttpClient;
import com.iblog.module.stock.support.UpstreamBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 市场情绪（赚钱效应）数据源：把全市场涨跌家数、涨跌停家数、炸板率、指数涨幅、
 * 成交额合成一个 0~100 的情绪分。
 *
 * <p><b>为什么选这几个数</b>：短线策略最怕的不是指数跌，而是「跌停一片、涨停没人接」。
 * 涨跌家数说明普跌还是普涨，涨停家数说明接力资金还在不在，炸板率说明接力资金敢不敢接，
 * 成交额说明有没有增量。这五项合起来才是「能不能做多」的直接读数。</p>
 *
 * <p><b>上游怪癖（实测）</b>：
 * <ul>
 *   <li>{@code push2delay} 用于涨跌家数与成交额（{@code f104/f105/f106/f6}）；
 *       指数列表接口一次就能拿到沪深两市的家数，不用自建全市场扫描</li>
 *   <li>{@code push2ex} 的涨跌停池返回 {@code data.tc} 即当日总数，
 *       {@code pagesize=1} 就够用，不必翻页</li>
 *   <li>这两个域名都会 reset 复用连接，必须走 {@link StockHttpClient#getTextNoKeepAlive}</li>
 *   <li>非交易日/盘前池子为空 → 返回 available=false 而不是 0 分，
 *       否则策略会在节假日被判定成「冰点」而误清仓</li>
 * </ul>
 *
 * <p><b>降级策略</b>：任一上游失败时沿用上一次成功的快照（{@code stale=true}）。
 * 情绪是慢变量，5 分钟前的读数和现在差不了多少；而「因为一次网络抖动就改策略行为」
 * 才是真正危险的。从未成功过时返回中性的 50 分并标 {@code available=false}，
 * 调用方应据此<b>不拦单</b>。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketSentimentService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 快照缓存时长：情绪是慢变量，15 秒的 tick 不必每次都打上游 */
    private static final long CACHE_MS = 60_000L;
    /** 上游单次超时：东财正常 250~600ms 返回 */
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(4);
    /** 熔断器：与资金流同样的阈值思路，连续 4 次失败就暂停 60 秒 */
    private static final int BREAKER_THRESHOLD = 4;
    private static final long BREAKER_COOLDOWN_MS = 60_000L;

    /** 涨跌家数 + 成交额：沪深两市 + 创业板，f104 涨 / f105 跌 / f106 平 / f6 成交额 / f3 涨跌幅 */
    private static final String URL_BREADTH =
            "https://push2delay.eastmoney.com/api/qt/ulist.np/get?fltt=2"
                    + "&secids=1.000001,0.399001,0.399006&fields=f2,f3,f6,f12,f14,f104,f105,f106"
                    + "&ut=fa5fd1943c7b386f172d6893dbfba10b";

    private static final String URL_LIMIT_UP =
            "https://push2ex.eastmoney.com/getTopicZTPool?ut=7eea3edcaed734bea9cbfc24409ed989"
                    + "&dpt=wz.ztzt&Pageindex=0&pagesize=1&sort=fbt%%3Aasc&date=%s";

    private static final String URL_LIMIT_DOWN =
            "https://push2ex.eastmoney.com/getTopicDTPool?ut=7eea3edcaed734bea9cbfc24409ed989"
                    + "&dpt=wz.ztzt&Pageindex=0&pagesize=1&sort=fund%%3Aasc&date=%s";

    private static final String URL_BROKEN =
            "https://push2ex.eastmoney.com/getTopicZBPool?ut=7eea3edcaed734bea9cbfc24409ed989"
                    + "&dpt=wz.ztzt&Pageindex=0&pagesize=1&sort=fbt%%3Aasc&date=%s";

    private static final String REFERER = "https://quote.eastmoney.com/";

    private final StockHttpClient http;

    private final UpstreamBreaker breaker = new UpstreamBreaker(BREAKER_THRESHOLD, BREAKER_COOLDOWN_MS);

    /** 上一次成功的完整快照（含「昨天收盘后」的读数，盘前拿到也能用） */
    private volatile MarketSentiment lastGood;
    private volatile long cachedAt;

    /**
     * 取当前情绪快照（带 60 秒缓存）。
     *
     * <p>任何上游失败都不会抛异常：策略里「拿不到情绪」应该退化成「不拦单」，
     * 而不是把整轮自动交易打断。</p>
     */
    public MarketSentiment current() {
        long now = System.currentTimeMillis();
        MarketSentiment cached = lastGood;
        if (cached != null && now - cachedAt < CACHE_MS) {
            return copyOf(cached, false);
        }
        synchronized (this) {
            cached = lastGood;
            if (cached != null && System.currentTimeMillis() - cachedAt < CACHE_MS) {
                return copyOf(cached, false);
            }
            Raw raw = fetchRaw();
            if (raw == null) {
                if (cached != null) {
                    // 沿用上一份快照但标 stale：情绪是慢变量，一次抖动不值得改策略行为
                    log.debug("情绪：上游失败，沿用上一份快照");
                    // 顺手把时间戳推后，避免每个 tick 都去打一遍不可用的上游
                    cachedAt = System.currentTimeMillis();
                    return copyOf(cached, true);
                }
                log.warn("情绪：上游不可用且无历史快照，返回中性占位");
                return neutral();
            }
            MarketSentiment computed = build(raw);
            lastGood = computed;
            cachedAt = System.currentTimeMillis();
            return copyOf(computed, false);
        }
    }

    /** 供单测/排查用：直接按原始数算分，不碰网络。 */
    public static MarketSentiment build(Raw raw) {
        if (raw == null) {
            // 与 neutral() 保持同一口径：没数据时给中性占位分，绝不给 0（0 分会被当成冰点）
            MarketSentiment blank = new MarketSentiment();
            blank.setAvailable(false);
            blank.setStale(false);
            blank.setScore(50);
            blank.setLevel(MarketSentimentRule.level(50));
            return blank;
        }
        MarketSentiment vo = new MarketSentiment();
        vo.setAvailable(true);
        vo.setStale(false);
        vo.setUpCount(raw.up);
        vo.setDownCount(raw.down);
        vo.setFlatCount(raw.flat);
        vo.setLimitUpCount(raw.limitUp);
        vo.setLimitDownCount(raw.limitDown);
        vo.setBrokenCount(raw.broken);
        vo.setAmountYi(raw.amountYi);
        vo.setIndexChangePct(raw.indexChangePct);
        vo.setTradeDate(raw.tradeDate);
        vo.setUpdatedAt(raw.updatedAt);
        // 分母用「已封住的 + 炸开的」，炸板率的定义就是炸板占曾涨停总数的比例
        int base = raw.limitUp + raw.broken;
        vo.setBrokenRate(base <= 0 ? 0 : Math.round(raw.broken * 1000.0 / base) / 10.0);
        vo.setScore(score(raw));
        vo.setLevel(MarketSentimentRule.level(vo.getScore()));
        return vo;
    }

    /**
     * 加权合成情绪分。
     *
     * <p>缺失的分项<b>不按 0 分算</b>，而是把它的权重按比例摊给其余分项
     * ——否则某个接口失败会让分数整体偏低，策略被误判成冰点。</p>
     */
    static double score(Raw raw) {
        double[] scores = {
                MarketSentimentRule.breadthScore(raw.up, raw.down),
                MarketSentimentRule.limitScore(raw.limitUp, raw.limitDown),
                MarketSentimentRule.brokenScore(raw.limitUp, raw.broken),
                MarketSentimentRule.indexScore(raw.indexChangePct),
                MarketSentimentRule.amountScore(raw.amountYi)
        };
        double[] weights = {
                MarketSentimentRule.WEIGHT_BREADTH, MarketSentimentRule.WEIGHT_LIMIT,
                MarketSentimentRule.WEIGHT_BROKEN, MarketSentimentRule.WEIGHT_INDEX,
                MarketSentimentRule.WEIGHT_AMOUNT
        };
        double sum = 0;
        double weightSum = 0;
        for (int i = 0; i < scores.length; i++) {
            if (Double.isNaN(scores[i])) {
                continue;
            }
            sum += scores[i] * weights[i];
            weightSum += weights[i];
        }
        if (weightSum <= 0) {
            return 50;
        }
        double value = sum / weightSum;
        return Math.round(value * 10) / 10.0;
    }

    // ---------- 上游 ----------

    private Raw fetchRaw() {
        if (breaker.isOpen()) {
            return null;
        }
        String today = LocalDate.now(ZONE).format(DateTimeFormatter.BASIC_ISO_DATE);
        try {
            JsonNode breadth = readJson(URL_BREADTH);
            JsonNode diff = breadth == null ? null : breadth.path("data").path("diff");
            if (diff == null || !diff.isArray() || diff.isEmpty()) {
                breaker.recordFailure();
                return null;
            }
            int up = 0;
            int down = 0;
            int flat = 0;
            double amount = 0;
            double changeSum = 0;
            int changeCount = 0;
            for (JsonNode n : diff) {
                up += n.path("f104").asInt(0);
                down += n.path("f105").asInt(0);
                flat += n.path("f106").asInt(0);
                double amt = n.path("f6").asDouble(0);
                if (amt > 0) {
                    // 指数成分口径：沪深两指已含全市场，创业板指会重叠，所以只累加上证 + 深证
                    String code = n.path("f12").asText("");
                    if ("000001".equals(code) || "399001".equals(code)) {
                        amount += amt;
                    }
                }
                double pct = n.path("f3").asDouble(Double.NaN);
                if (!Double.isNaN(pct)) {
                    changeSum += pct;
                    changeCount++;
                }
            }
            if (up + down <= 0) {
                // 盘前/停市：家数为 0 说明上游还没有当日数据
                breaker.recordFailure();
                return null;
            }
            int limitUp = readPoolTotal(String.format(URL_LIMIT_UP, today));
            int limitDown = readPoolTotal(String.format(URL_LIMIT_DOWN, today));
            int broken = readPoolTotal(String.format(URL_BROKEN, today));
            breaker.recordSuccess();

            Raw raw = new Raw();
            raw.up = up;
            raw.down = down;
            raw.flat = flat;
            raw.limitUp = Math.max(0, limitUp);
            raw.limitDown = Math.max(0, limitDown);
            raw.broken = Math.max(0, broken);
            raw.amountYi = Math.round(amount / 1e8 * 10) / 10.0;
            raw.indexChangePct = changeCount == 0 ? Double.NaN : changeSum / changeCount;
            raw.tradeDate = LocalDate.now(ZONE).toString();
            raw.updatedAt = LocalTime.now(ZONE).format(TIME_FORMAT);
            return raw;
        } catch (RuntimeException ex) {
            if (breaker.recordFailure()) {
                log.warn("情绪：上游连续失败触发熔断 {} 秒 msg={}", BREAKER_COOLDOWN_MS / 1000, ex.getMessage());
            }
            return null;
        }
    }

    /**
     * 读涨跌停池的 {@code data.tc}（当日总数）。
     *
     * <p>拿不到就返回 -1，由调用方决定「这一项缺失」——不要把失败当 0 家涨停，
     * 那会被算成冰点。</p>
     */
    private int readPoolTotal(String url) {
        JsonNode root = readJson(url);
        if (root == null) {
            return -1;
        }
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            return -1;
        }
        return data.path("tc").asInt(-1);
    }

    private JsonNode readJson(String url) {
        try {
            String text = http.getTextNoKeepAlive(url, REFERER, StandardCharsets.UTF_8, FETCH_TIMEOUT);
            if (text == null || text.isBlank()) {
                return null;
            }
            return JSON.readTree(text);
        } catch (Exception ex) {
            log.debug("情绪：请求失败 url={} err={}", url, ex.getMessage());
            return null;
        }
    }

    private static MarketSentiment neutral() {
        MarketSentiment vo = new MarketSentiment();
        vo.setAvailable(false);
        vo.setStale(false);
        vo.setScore(50);
        vo.setLevel(MarketSentimentRule.level(50));
        return vo;
    }

    private static MarketSentiment copyOf(MarketSentiment src, boolean stale) {
        MarketSentiment vo = new MarketSentiment();
        vo.setAvailable(src.isAvailable());
        vo.setStale(stale);
        vo.setScore(src.getScore());
        vo.setLevel(src.getLevel());
        vo.setUpCount(src.getUpCount());
        vo.setDownCount(src.getDownCount());
        vo.setFlatCount(src.getFlatCount());
        vo.setLimitUpCount(src.getLimitUpCount());
        vo.setLimitDownCount(src.getLimitDownCount());
        vo.setBrokenCount(src.getBrokenCount());
        vo.setBrokenRate(src.getBrokenRate());
        vo.setAmountYi(src.getAmountYi());
        vo.setIndexChangePct(src.getIndexChangePct());
        vo.setTradeDate(src.getTradeDate());
        vo.setUpdatedAt(src.getUpdatedAt());
        return vo;
    }

    /** 原始读数：五个维度 + 元信息，与打分逻辑解耦，便于单测直接构造。 */
    public static class Raw {
        public int up;
        public int down;
        public int flat;
        public int limitUp;
        public int limitDown;
        public int broken;
        public double amountYi;
        public double indexChangePct = Double.NaN;
        public String tradeDate = "";
        public String updatedAt = "";

        public Raw up(int value) {
            this.up = value;
            return this;
        }

        public Raw down(int value) {
            this.down = value;
            return this;
        }

        public Raw limitUp(int value) {
            this.limitUp = value;
            return this;
        }

        public Raw limitDown(int value) {
            this.limitDown = value;
            return this;
        }

        public Raw broken(int value) {
            this.broken = value;
            return this;
        }

        public Raw amountYi(double value) {
            this.amountYi = value;
            return this;
        }

        public Raw indexChangePct(double value) {
            this.indexChangePct = value;
            return this;
        }
    }
}
