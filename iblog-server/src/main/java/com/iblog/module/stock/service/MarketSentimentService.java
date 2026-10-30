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
 * <p><b>降级策略</b>：分两层。
 * <ol>
 *   <li><b>链路降级</b>：东财 {@code push2delay} 对部分机房 IP 直接拒连（实测线上服务器
 *       全部 HTTP 000 / {@code header parser received no bytes}，同一台机器访问
 *       {@code push2ex}、腾讯、新浪却都正常），所以主链路拿不到涨跌家数时，
 *       改用「新浪按涨跌幅降序分页二分 + 腾讯指数」兜底。</li>
 *   <li><b>时间降级</b>：任一路径都失败时沿用上一次成功的快照（{@code stale=true}）。
 *       情绪是慢变量，5 分钟前的读数和现在差不了多少；而「因为一次网络抖动就改策略行为」
 *       才是真正危险的。从未成功过时返回中性的 50 分并标 {@code available=false}，
 *       调用方应据此<b>不拦单</b>。</li>
 * </ol>
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

    /**
     * 兜底链路（东财 push2 被机房 IP 封禁时启用）：
     * 新浪按涨跌幅排序分页 + 腾讯指数。
     *
     * <p>实测线上 IP 对 push2delay / push2 全部连接被拒（curl 返回 000），
     * 但同一台机器访问 push2ex（涨跌停池）、qt.gtimg.cn、新浪行情均正常，
     * 所以这不是网络整体故障，而是东财对该 IP 段的主动限制。</p>
     */
    private static final String REFERER_SINA = "https://finance.sina.com.cn/";

    private static final String URL_SINA_COUNT =
            "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php"
                    + "/Market_Center.getHQNodeStockCount?node=hs_a";

    private static final String URL_SINA_PAGE =
            "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php"
                    + "/Market_Center.getHQNodeData?num=" + 100
                    + "&sort=changepercent&asc=0&node=hs_a&symbol=&_s_r_a=page&page=%d";

    private static final String URL_TENCENT_INDEX =
            "https://qt.gtimg.cn/q=sh000001,sz399001,sz399006";

    /** 新浪分页每页条数（上游硬上限，传更大也只返 100） */
    private static final int SINA_PAGE_SIZE = 100;

    /** 兜底链路的总时间预算：二分要打好几次请求，不能把引擎 tick 拖住 */
    private static final long FALLBACK_BUDGET_MS = 8_000L;

    /** 兜底链路单次请求超时：比主路径短，因为要连打多次。实测新浪/腾讯都在 300ms 内返回 */
    private static final Duration FALLBACK_TIMEOUT = Duration.ofMillis(2_000);

    private final StockHttpClient http;

    private final UpstreamBreaker breaker = new UpstreamBreaker(BREAKER_THRESHOLD, BREAKER_COOLDOWN_MS);

    /** 上一次成功的完整快照（含「昨天收盘后」的读数，盘前拿到也能用） */
    private volatile MarketSentiment lastGood;
    private volatile long cachedAt;

    /** 上一次打「已切兜底链路」日志的时间，避免每 60 秒刷一条 */
    private volatile long fallbackLoggedAt = 0L;

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
        Raw raw = fetchRawEastmoney();
        if (raw != null) {
            return raw;
        }
        // 东财 push2 这条路径对部分机房 IP 直接拒连，退回新浪 + 腾讯的兜底链路。
        //
        // 这里<b>刻意不清空熔断计数</b>：兜底成功不代表主链路恢复了。若清了，
        // 每个 60 秒冷却期一过就会再拿一个 tick 去等东财超时（最长 4 秒 × 多次），
        // 而线上这个 IP 段是长期被封的 —— 等于每隔一分钟白白拖慢一次引擎。
        // 熔断器只由主链路自己的成功/失败驱动。
        Raw fallback = fetchRawFallback();
        if (fallback != null) {
            // 兜底是常态（线上 IP 被封），每 10 分钟提示一次足够排查，不必刷屏
            long now = System.currentTimeMillis();
            if (now - fallbackLoggedAt > 600_000L) {
                fallbackLoggedAt = now;
                log.info("情绪：东财主链路不可用，已改用新浪 + 腾讯兜底链路");
            }
        }
        return fallback;
    }

    private Raw fetchRawEastmoney() {
        // 熔断只针对东财主链路：它挂了不代表兜底链路也不可用，
        // 所以这个判断放在主链路内部，而不是 fetchRaw 的入口。
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
            // 涨跌停池在 push2ex 上，即使 push2 被封也通常可用，所以这里仍然读
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
     * 兜底链路：新浪按涨跌幅降序分页 + 二分找 0% 边界，得出涨/跌/平家数；
     * 指数涨幅与成交额改从腾讯取（腾讯对机房 IP 友好，且本来就在用）。
     *
     * <p>为什么用二分而不是逐页扫：沪深 5000+ 只股票按 100 条一页要 56 次请求，
     * 15 秒一次的 tick 撑不住。二分只需 6~7 次就能定位「涨跌幅由正转负」的那一页。</p>
     *
     * <p><b>时间预算很紧</b>：整条兜底链路必须在 {@link #FALLBACK_BUDGET_MS} 内跑完，
     * 因为它挂在 15 秒一次的引擎 tick 上。所以每一步都用 {@link #FALLBACK_TIMEOUT}
     * （2.5 秒）而不是默认的 4 秒，并且在循环里反复检查 deadline —— 宁可不交这个数
     * （沿用上次快照），也不能把 tick 拖过下一轮。</p>
     */
    private Raw fetchRawFallback() {
        long deadline = System.currentTimeMillis() + FALLBACK_BUDGET_MS;
        try {
            // 先读涨跌停池：push2ex 是独立的域名，即使 push2delay 被封它通常还活着，
            // 而且这三次请求是「有就用、没有就缺项」，放在最前面能保证它们拿到配额。
            String today = LocalDate.now(ZONE).format(DateTimeFormatter.BASIC_ISO_DATE);
            int limitUp = readPoolTotalInBudget(String.format(URL_LIMIT_UP, today), deadline);
            int limitDown = readPoolTotalInBudget(String.format(URL_LIMIT_DOWN, today), deadline);
            int broken = readPoolTotalInBudget(String.format(URL_BROKEN, today), deadline);

            int total = readSinaTotal();
            if (total <= 0) {
                return null;
            }
            int maxPage = (total + SINA_PAGE_SIZE - 1) / SINA_PAGE_SIZE;
            int lo = 1;
            int hi = maxPage;
            while (lo < hi) {
                if (System.currentTimeMillis() > deadline) {
                    log.debug("情绪：兜底链路超出预算，放弃");
                    return null;
                }
                int mid = (lo + hi) >>> 1;
                Double tail = readSinaPageTailPct(mid);
                if (tail == null) {
                    return null;
                }
                if (tail <= 0) {
                    hi = mid;
                } else {
                    lo = mid + 1;
                }
            }
            int boundaryPage = lo;
            double[] page = readSinaPageCounts(boundaryPage);
            if (page == null) {
                return null;
            }
            int gt0 = (int) page[0];
            int eq0 = (int) page[1];
            int up = (boundaryPage - 1) * SINA_PAGE_SIZE + gt0;
            int flat = eq0;
            if (up + flat > total) {
                // 上游分页口径变了，宁可不要这个数，也别交出一个自相矛盾的读数
                return null;
            }
            int down = total - up - flat;

            double[] index = readTencentIndex();

            Raw raw = new Raw();
            raw.up = up;
            raw.down = down;
            raw.flat = flat;
            raw.limitUp = Math.max(0, limitUp);
            raw.limitDown = Math.max(0, limitDown);
            raw.broken = Math.max(0, broken);
            raw.amountYi = index == null ? Double.NaN : Math.round(index[1] * 10) / 10.0;
            raw.indexChangePct = index == null ? Double.NaN : index[0];
            raw.tradeDate = LocalDate.now(ZONE).toString();
            raw.updatedAt = LocalTime.now(ZONE).format(TIME_FORMAT);
            return raw;
        } catch (RuntimeException ex) {
            log.debug("情绪：兜底链路失败 err={}", ex.getMessage());
            return null;
        }
    }

    private int readSinaTotal() {
        String text = readSinaText(URL_SINA_COUNT);
        if (text == null) {
            return -1;
        }
        String trimmed = text.trim().replace("\"", "");
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /** 该页最后一条的涨跌幅（降序排列，所以是这一页里最小的那个）。 */
    private Double readSinaPageTailPct(int page) {
        JsonNode root = readSinaJson(page);
        if (root == null || !root.isArray() || root.isEmpty()) {
            return null;
        }        return root.get(root.size() - 1).path("changepercent").asDouble(Double.NaN);
    }

    /** 返回 [涨跌幅>0 条数, 涨跌幅==0 条数]；拿不到返回 null。 */
    private double[] readSinaPageCounts(int page) {
        JsonNode root = readSinaJson(page);
        if (root == null || !root.isArray() || root.isEmpty()) {
            return null;
        }
        int gt0 = 0;
        int eq0 = 0;
        for (JsonNode n : root) {
            double pct = n.path("changepercent").asDouble(Double.NaN);
            if (Double.isNaN(pct)) {
                continue;
            }
            if (pct > 0) {
                gt0++;
            } else if (pct == 0) {
                eq0++;
            }
        }
        return new double[]{gt0, eq0};
    }

    private JsonNode readSinaJson(int page) {
        String text = readSinaText(String.format(URL_SINA_PAGE, page));
        if (text == null) {
            return null;
        }
        try {
            return JSON.readTree(text);
        } catch (Exception ex) {
            return null;
        }
    }

    private String readSinaText(String url) {
        try {
            String text = http.getTextNoKeepAlive(url, REFERER_SINA, StandardCharsets.UTF_8, FALLBACK_TIMEOUT);
            return text == null || text.isBlank() ? null : text;
        } catch (Exception ex) {
            log.debug("情绪：新浪请求失败 url={} err={}", url, ex.getMessage());
            return null;
        }
    }

    /**
     * 从腾讯取 [指数平均涨幅, 沪深成交额(亿)]。
     *
     * <p>腾讯 {@code v_sh000001} 返回值是 {@code ~} 分隔的 88 个字段，GBK 编码：
     * [3] 当前点位、[32] 涨跌幅(%)、[37] 成交额（万元）。这里取上证 + 深证两市的成交额，
     * 创业板指不累加（与东财口径一致，它已包含在深市里）。</p>
     */
    private double[] readTencentIndex() {
        try {
            String text = http.getTextNoKeepAlive(URL_TENCENT_INDEX, StockHttpClient.REFERER_QT,
                    StockHttpClient.GBK, FALLBACK_TIMEOUT);
            if (text == null || text.isBlank()) {
                return null;
            }
            return parseTencentIndex(text);
        } catch (Exception ex) {
            log.debug("情绪：腾讯指数失败 err={}", ex.getMessage());
            return null;
        }
    }

    /** 解析腾讯 {@code v_sh000001="..."} 文本，返回 [指数平均涨幅, 沪深成交额(亿)]；解析不出返回 null。 */
    public static double[] parseTencentIndex(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        double changeSum = 0;
        int changeCount = 0;
        double amountWan = 0;
        for (String line : text.split("\n")) {
            int eq = line.indexOf("=\"");
            if (eq < 0) {
                continue;
            }
            String code = line.substring(0, eq);
            String body = line.substring(eq + 2).replaceAll("[\";\\s]+$", "");
            String[] parts = body.split("~");
            if (parts.length < 38) {
                continue;
            }
            double pct = parseDouble(parts[32]);
            if (!Double.isNaN(pct)) {
                changeSum += pct;
                changeCount++;
            }
            // 只累加沪市与深市，创业板已含在深市里
            if (code.endsWith("sh000001") || code.endsWith("sz399001")) {
                double wan = parseDouble(parts[37]);
                if (!Double.isNaN(wan) && wan > 0) {
                    amountWan += wan;
                }
            }
        }
        if (changeCount == 0) {
            return null;
        }
        // 万元 → 亿元
        return new double[]{changeSum / changeCount, amountWan / 1e4};
    }

    private static double parseDouble(String text) {
        if (text == null || text.isBlank()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    /**
     * 读涨跌停池的 {@code data.tc}（当日总数）。
     *
     * <p>拿不到就返回 -1，由调用方决定「这一项缺失」——不要把失败当 0 家涨停，
     * 那会被算成冰点。</p>
     */
    private int readPoolTotal(String url) {
        return readPoolTotalInBudget(url, Long.MAX_VALUE);
    }

    /** 同 {@link #readPoolTotal}，但超出 deadline 就放弃（返回 -1 表示缺项）。 */
    private int readPoolTotalInBudget(String url, long deadline) {
        if (System.currentTimeMillis() > deadline) {
            return -1;
        }
        JsonNode root = readJsonInBudget(url, deadline);
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
        return readJsonInBudget(url, Long.MAX_VALUE);
    }

    private JsonNode readJsonInBudget(String url, long deadline) {
        if (System.currentTimeMillis() > deadline) {
            return null;
        }
        try {
            // 兜底链路要走短线超时；主链路仍用默认的 4 秒
            Duration timeout = deadline == Long.MAX_VALUE ? FETCH_TIMEOUT : FALLBACK_TIMEOUT;
            String text = http.getTextNoKeepAlive(url, REFERER, StandardCharsets.UTF_8, timeout);
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
