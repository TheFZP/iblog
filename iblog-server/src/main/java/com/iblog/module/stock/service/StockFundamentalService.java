package com.iblog.module.stock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iblog.module.stock.support.StockCode;
import com.iblog.module.stock.support.StockHttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 东方财富 F10 基本面数据源：自由流通股、股东户数（集中度）、报告期盈利。
 *
 * <p>为什么单独一个类：这些数据来自 {@code datacenter-web.eastmoney.com} 的报表接口，
 * 和 {@link EastmoneyService}（push2 资金流）完全不同的域名与返回结构，混在一起会让
 * 「资金流重试预算」这类参数变得没法解释。</p>
 *
 * <h3>字段口径（都是实测踩出来的，别换字段）</h3>
 * <ul>
 *   <li>自由流通股取 {@code RPT_F10_EH_EQUITY.FREELIQCI_SHARES}；
 *       <b>{@code FREE_SHARES} 是「流通股」不是「自由流通股」</b>，两者差得远
 *       （茅台流通 12.5 亿 / 自由流通 5.4 亿），用错会让换手率算小一半</li>
 *   <li>股东户数取 {@code RPT_F10_EH_HOLDERNUM.HOLDER_TOTAL_NUM}，按 END_DATE 倒序取两期，
 *       户数减少 = 筹码集中 = 集中度变大；户数增加 = 集中度变小</li>
 *   <li>盈利取 {@code RPT_LICO_FN_CPD.PARENT_NETPROFIT}（归母净利润），
 *       REPORTDATE 月日 06-30 = 二季报/中报、12-31 = 年报</li>
 *   <li>业绩预告取 {@code RPT_PUBLIC_OP_NEWPREDICT}，{@code PREDICT_FINANCE_CODE="004"}
 *       才是归母净利润；该接口的 columns 里<b>不能带 PREDICT_STATE</b>（上游报字段不存在）</li>
 * </ul>
 *
 * <p><b>失败一律返回 null/false 而不是抛异常</b>：基本面数据是选股的过滤项，
 * 上游抖动不能让整轮选股挂掉；调用方按「拿不到就不通过」处理（保守），
 * 但会记 debug 日志便于排查。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockFundamentalService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String REFERER = "https://data.eastmoney.com/";
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(4);

    private static final String URL_REPORT =
            "https://datacenter-web.eastmoney.com/api/data/v1/get?reportName=%s&columns=%s&filter=%s"
                    + "&pageSize=%d&sortColumns=%s&sortTypes=-1";

    /** 基本面变化很慢，缓存 6 小时足够，避免一次选股对同一只票重复打上游 */
    private static final long TTL_MS = 6 * 60 * 60 * 1000L;
    private static final int CACHE_MAX = 2000;

    private final StockHttpClient http;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * 累计真正打到上游的报表请求数（命中缓存不计数）。
     *
     * <p>选股用它做「本轮最多打几次上游」的预算：按候选只数扣预算会在缓存命中时
     * 白扣（第二轮选股其实一次都没请求上游），按请求数扣才反映真实成本。</p>
     */
    private final AtomicInteger upstreamRequests = new AtomicInteger();

    /** 已打上游的报表请求总数，单调递增。 */
    public int upstreamRequests() {
        return upstreamRequests.get();
    }

    // ==================== 自由流通股 ====================

    /**
     * 自由流通股（股）。拿不到返回 null。
     */
    public Double freeFloatShares(String rawCode) {
        String code = StockCode.normalize(rawCode);
        if (code.isEmpty() || StockCode.isHk(code)) {
            return null;
        }
        return cached("eq:" + code, () -> fetchFreeFloatShares(code));
    }

    private Double fetchFreeFloatShares(String code) {
        String secucode = hsf10(code);
        if (secucode == null) {
            return null;
        }
        JsonNode rows = fetchReport("RPT_F10_EH_EQUITY",
                "SECUCODE,END_DATE,FREELIQCI_SHARES,TOTAL_SHARES",
                "(SECUCODE=\"" + secucode + "\")", 1, "END_DATE");
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        double shares = rows.get(0).path("FREELIQCI_SHARES").asDouble(0);
        return shares > 0 ? shares : null;
    }

    /**
     * 实时换手率口径之一：全日成交量（股）÷ 自由流通股 × 100。
     */
    public Double realTurnoverPct(String rawCode, double volumeLots) {
        Double free = freeFloatShares(rawCode);
        if (free == null || free <= 0 || volumeLots <= 0) {
            return null;
        }
        return volumeLots * 100.0 / free * 100.0;
    }

    /**
     * 早盘换手率：指定窗口内的成交量（手）÷ 自由流通股 × 100。
     */
    public Double windowTurnoverPct(String rawCode, double volumeLots) {
        return realTurnoverPct(rawCode, volumeLots);
    }

    // ==================== 股东户数（集中度） ====================

    /**
     * 集中度是否变小：最新一期股东户数 < 上一期。
     *
     * @return 数据不足或上游失败返回 null（调用方按不通过处理）
     */
    public Boolean holderShrink(String rawCode) {
        String code = StockCode.normalize(rawCode);
        if (code.isEmpty() || StockCode.isHk(code)) {
            return null;
        }
        return cached("hn:" + code, () -> fetchHolderShrink(code));
    }

    private Boolean fetchHolderShrink(String code) {
        JsonNode rows = fetchReport("RPT_F10_EH_HOLDERNUM",
                "END_DATE,HOLDER_TOTAL_NUM,TOTAL_NUM_RATIO",
                "(SECURITY_CODE=\"" + code + "\")", 3, "END_DATE");
        if (rows == null || rows.size() < 2) {
            return null;
        }
        double latest = rows.get(0).path("HOLDER_TOTAL_NUM").asDouble(0);
        double previous = rows.get(1).path("HOLDER_TOTAL_NUM").asDouble(0);
        if (latest <= 0 || previous <= 0) {
            return null;
        }
        return latest < previous;
    }

    // ==================== 盈利 ====================

    /** 只要求最近一个报告期盈利。 */
    public static final int PROFIT_LATEST = 1;
    /** 二季报或年报盈利。 */
    public static final int PROFIT_HALF_OR_ANNUAL = 2;
    /** 二季报、二季报预告或年报盈利（用户口径的完整版）。 */
    public static final int PROFIT_HALF_OR_FORECAST_OR_ANNUAL = 3;

    /**
     * 按指定口径判断是否盈利。
     *
     * @param mode 0=不限（直接 true）、1/2/3 见常量
     * @return 数据不足或上游失败返回 null
     */
    public Boolean profitOk(String rawCode, int mode) {
        if (mode <= 0) {
            return Boolean.TRUE;
        }
        String code = StockCode.normalize(rawCode);
        if (code.isEmpty() || StockCode.isHk(code)) {
            return null;
        }
        return cached("pf:" + code + ":" + mode, () -> fetchProfitOk(code, mode));
    }

    private Boolean fetchProfitOk(String code, int mode) {
        JsonNode rows = fetchReport("RPT_LICO_FN_CPD",
                "SECURITY_CODE,REPORTDATE,PARENT_NETPROFIT",
                "(SECURITY_CODE=\"" + code + "\")", 8, "REPORTDATE");
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        if (mode == PROFIT_LATEST) {
            return rows.get(0).path("PARENT_NETPROFIT").asDouble(0) > 0;
        }
        Boolean half = reportProfit(rows, "06-30");
        Boolean annual = reportProfit(rows, "12-31");
        if (Boolean.TRUE.equals(half) || Boolean.TRUE.equals(annual)) {
            return Boolean.TRUE;
        }
        if (mode == PROFIT_HALF_OR_FORECAST_OR_ANNUAL) {
            // 二季报还没披露时靠预告：只认归母净利润且预盈
            Boolean forecast = forecastProfit(code);
            if (Boolean.TRUE.equals(forecast)) {
                return Boolean.TRUE;
            }
            // 预告也拿不到、定期报告也拿不到 → 未知；有明确的亏损数据则判否
            if (forecast == null && half == null && annual == null) {
                return null;
            }
        }
        // 二季报/年报都明确给了数字但不是盈利 → 否；只要有一个明确为正上面已经返回
        if (half == null && annual == null) {
            return null;
        }
        return Boolean.FALSE;
    }

    /**
     * 从定期报告列表里找指定月日的最新一期，判断是否盈利；找不到返回 null。
     */
    private Boolean reportProfit(JsonNode rows, String monthDay) {
        for (JsonNode row : rows) {
            String date = row.path("REPORTDATE").asText("");
            if (date.length() < 10 || !date.substring(5, 10).equals(monthDay)) {
                continue;
            }
            return row.path("PARENT_NETPROFIT").asDouble(0) > 0;
        }
        return null;
    }

    /**
     * 二季报业绩预告是否预盈；无预告返回 null。
     */
    private Boolean forecastProfit(String code) {
        JsonNode rows = fetchReport("RPT_PUBLIC_OP_NEWPREDICT",
                "SECURITY_CODE,REPORT_DATE,PREDICT_FINANCE_CODE,PREDICT_AMT_LOWER,PREDICT_AMT_UPPER,FORECAST_JZ",
                "(SECURITY_CODE=\"" + code + "\")", 12, "REPORT_DATE");
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        for (JsonNode row : rows) {
            String date = row.path("REPORT_DATE").asText("");
            if (date.length() < 10 || !date.substring(5, 10).equals("06-30")) {
                continue;
            }
            // 004 = 归属于上市公司股东的净利润，其它科目（营收/扣非等）不看
            if (!"004".equals(row.path("PREDICT_FINANCE_CODE").asText(""))) {
                continue;
            }
            double amount = row.path("FORECAST_JZ").asDouble(0);
            if (amount == 0) {
                amount = row.path("PREDICT_AMT_UPPER").asDouble(0);
            }
            if (amount == 0) {
                amount = row.path("PREDICT_AMT_LOWER").asDouble(0);
            }
            return amount > 0;
        }
        return null;
    }

    // ==================== 内部工具 ====================

    /**
     * 拉一张报表，返回 {@code result.data} 数组；任何失败都返回 null。
     */
    private JsonNode fetchReport(String reportName, String columns, String filter,
                                 int pageSize, String sortColumn) {
        String url = String.format(URL_REPORT, reportName, columns, encode(filter), pageSize, sortColumn);
        upstreamRequests.incrementAndGet();
        try {
            String text = http.getTextNoKeepAlive(url, REFERER, StandardCharsets.UTF_8, FETCH_TIMEOUT);
            JsonNode root = JSON.readTree(text);
            if (root == null || !root.path("success").asBoolean(false)) {
                return null;
            }
            JsonNode data = root.path("result").path("data");
            return data.isArray() ? data : null;
        } catch (Exception ex) {
            log.debug("基本面请求失败 report={} filter={} err={}", reportName, filter, ex.getMessage());
            return null;
        }
    }

    /** 东财报表的 filter 需要 URL 编码，但括号与引号编码后上游才认 */
    private static String encode(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    /** SECUCODE 形如 {@code 002086.SZ}，北交所用 .BJ；港股不支持 */
    private static String hsf10(String code) {
        if (StockCode.isHk(code)) {
            return null;
        }
        return code + "." + StockCode.marketOf(code);
    }

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, java.util.function.Supplier<T> loader) {
        Cached hit = cache.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.at < TTL_MS) {
            return (T) hit.value;
        }
        T value = loader.get();
        if (cache.size() >= CACHE_MAX) {
            cache.clear();
        }
        cache.put(key, new Cached(value, now));
        return value;
    }

    private record Cached(Object value, long at) {
    }
}
