package com.iblog.module.stock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iblog.module.stock.support.StockHttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 隔夜外盘快照：美股三大指数 + 日经225 + 韩国 KOSPI 的最近一个收盘涨跌幅。
 *
 * <p><b>为什么要看外盘</b>：A 股次日开盘前，最新的外部信息就是「昨晚美股」和
 * 「今天日韩」——15:30 生成榜单时，美股已收盘（北京时间凌晨），日经/韩国也已收盘。
 * 这三个市场同属亚太科技产业链与全球风险偏好，是唯一能在 T 日收盘后拿到、
 * 又确实影响 T+1 的公开数据。</p>
 *
 * <p><b>上游选择</b>：主链路用东财 {@code push2 ulist}（一次请求五个指数，实测线上机房可访问），
 * 失败时退回腾讯 {@code qt.gtimg.cn}（只有美股三个，日韩缺失时按已有权重重新归一）。
 * 两个源都不可用时返回不可用快照，打分侧按「无数据」处理，绝不填 0 假装看平。</p>
 *
 * <p><b>缓存 10 分钟</b>：15:30 任务一天只跑一次，缓存主要是防止手工刷新连续触发时重复打上游。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OverseasMarketService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String URL_EASTMONEY =
            "https://push2.eastmoney.com/api/qt/ulist.np/get?fltt=2"
                    + "&secids=100.DJIA,100.NDX,100.SPX,100.N225,100.KS11"
                    + "&fields=f3,f12,f14&ut=fa5fd1943c7b386f172d6893dbfba10b";
    private static final String REFERER_EASTMONEY = "https://quote.eastmoney.com/";

    private static final String URL_TENCENT = "https://qt.gtimg.cn/q=usDJI,usIXIC,usINX";

    /** 单次请求超时：外盘只是加分项，不能拖慢 15:30 的主流程。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final long CACHE_MS = 10 * 60_000L;
    /** 失败后也缓存一小会儿，避免同一轮里反复重试。 */
    private static final long FAILURE_CACHE_MS = 60_000L;

    /**
     * 权重：纳指对 A 股科技情绪影响最大，其次是标普、道指；日韩代表亚太风险偏好。
     * 缺失的指数会在计算时自动剔除并重新归一。
     */
    private static final Map<String, Double> WEIGHTS = Map.of(
            "NDX", 0.30,
            "SPX", 0.25,
            "DJIA", 0.20,
            "N225", 0.15,
            "KS11", 0.10);

    /** 腾讯代码 → 东财口径代码，保证两条链路的权重表是同一套。 */
    private static final Map<String, String> TENCENT_CODES = Map.of(
            "usDJI", "DJIA",
            "usIXIC", "NDX",
            "usINX", "SPX");

    private final StockHttpClient http;

    private volatile Snapshot cache;
    private volatile long cacheAt;

    /** 指数涨跌。 */
    public record Move(String code, String name, double changePct) {
    }

    /**
     * 外盘快照。
     *
     * @param weightedPct 按权重合成的涨跌幅（%）；不可用时为 0
     * @param moves       各指数涨跌幅，key 为东财口径代码
     * @param source      数据来源（eastmoney / tencent），排查问题时有用
     */
    public record Snapshot(double weightedPct, Map<String, Double> moves, String source) {

        public boolean available() {
            return !moves.isEmpty();
        }

        static Snapshot empty() {
            return new Snapshot(0, Map.of(), "");
        }
    }

    /** 取快照；永不抛异常，失败时返回不可用快照。 */
    public Snapshot snapshot() {
        long now = System.currentTimeMillis();
        Snapshot cached = cache;
        if (cached != null && now - cacheAt < (cached.available() ? CACHE_MS : FAILURE_CACHE_MS)) {
            return cached;
        }
        Snapshot fresh = fetch();
        cache = fresh;
        cacheAt = now;
        return fresh;
    }

    private Snapshot fetch() {
        try {
            String body = http.getTextNoKeepAlive(URL_EASTMONEY, REFERER_EASTMONEY,
                    StandardCharsets.UTF_8, TIMEOUT);
            Snapshot snapshot = parseEastmoney(body);
            if (snapshot.available()) {
                return snapshot;
            }
            log.debug("外盘：东财返回空数据，尝试腾讯");
        } catch (RuntimeException ex) {
            log.debug("外盘：东财不可用 err={}", ex.getMessage());
        }
        try {
            String body = http.getText(URL_TENCENT, StockHttpClient.REFERER_QT, StockHttpClient.GBK);
            Snapshot snapshot = parseTencent(body);
            if (snapshot.available()) {
                return snapshot;
            }
        } catch (RuntimeException ex) {
            log.warn("外盘：腾讯也不可用 err={}", ex.getMessage());
        }
        return Snapshot.empty();
    }

    /** 解析东财 ulist 响应；格式异常返回不可用快照。 */
    static Snapshot parseEastmoney(String body) {
        Map<String, Double> moves = new LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return new Snapshot(weighted(moves), moves, "");
        }
        try {
            JsonNode diff = JSON.readTree(body).path("data").path("diff");
            if (!diff.isArray()) {
                return new Snapshot(weighted(moves), moves, "");
            }
            for (JsonNode item : diff) {
                String code = item.path("f12").asText("");
                JsonNode pct = item.path("f3");
                if (code.isEmpty() || !pct.isNumber()) {
                    continue;
                }
                moves.put(code, pct.asDouble());
            }
        } catch (Exception ex) {
            log.debug("解析东财外盘失败 err={}", ex.toString());
        }
        return new Snapshot(weighted(moves), moves, moves.isEmpty() ? "" : "eastmoney");
    }

    /**
     * 解析腾讯 {@code v_usDJI="200~道琼斯~.DJI~48147.33~..."}。
     *
     * <p>腾讯字段位置固定：下标 3 是现价，下标 32 是涨跌幅（%）。
     * 长度不够的行直接跳过，避免上游改格式时数组越界。</p>
     */
    static Snapshot parseTencent(String body) {
        Map<String, Double> moves = new LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return new Snapshot(weighted(moves), moves, "");
        }
        for (String line : body.split("[;\\n]")) {
            int eq = line.indexOf('=');
            int quote = line.indexOf('"', eq + 1);
            int end = line.lastIndexOf('"');
            if (eq < 0 || quote < 0 || end <= quote) {
                continue;
            }
            String varName = line.substring(0, eq).replace("v_", "").trim();
            String mapped = TENCENT_CODES.get(varName);
            if (mapped == null) {
                continue;
            }
            String[] parts = line.substring(quote + 1, end).split("~");
            if (parts.length < 33) {
                continue;
            }
            try {
                moves.put(mapped, Double.parseDouble(parts[32]));
            } catch (NumberFormatException ignored) {
                // 停牌/无数据时腾讯会给空串，跳过即可
            }
        }
        return new Snapshot(weighted(moves), moves, moves.isEmpty() ? "" : "tencent");
    }

    /** 按权重合成；缺失的指数剔除后重新归一，不把缺数据当成 0%。 */
    static double weighted(Map<String, Double> moves) {
        double sum = 0;
        double weightSum = 0;
        for (Map.Entry<String, Double> entry : moves.entrySet()) {
            Double weight = WEIGHTS.get(entry.getKey());
            if (weight == null || entry.getValue() == null) {
                continue;
            }
            sum += weight * entry.getValue();
            weightSum += weight;
        }
        if (weightSum <= 0) {
            return 0;
        }
        return Math.round(sum / weightSum * 100) / 100.0;
    }
}
