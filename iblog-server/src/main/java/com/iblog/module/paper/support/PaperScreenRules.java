package com.iblog.module.paper.support;

import com.iblog.module.stock.model.IntradayItem;
import com.iblog.module.stock.model.KlineItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 选股用的纯计算规则（无 Spring 依赖，便于单测）：
 * 换手率/流通市值区间、分时是否全程站在均价线上方、近 N 日涨停记忆、
 * 昨日连板高度（首板/二板/…）。
 *
 * <p>多数条件只参与打分，不做硬过滤 —— 满足的加分，不满足的不扣分，
 * 这样既能把「一夜持股法」这类网络流传的筛选条件量化进模型，又不会因为一条
 * 数据缺失（如分时取不到）就把候选直接淘汰。例外是「连板高度」：
 * 一进二这类策略要求严格只做昨日首板，配了上限后由调用方直接剔除，
 * 否则三板、四板的票靠其它因子也能凑够分数。</p>
 */
public final class PaperScreenRules {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private PaperScreenRules() {
    }

    /**
     * 数值是否落在 [min,max] 内。
     *
     * <p>min/max 同时 &lt;= 0 表示该因子关闭；任一值为 0 表示该侧不限。
     * 入参 &lt;= 0 视为无数据（停牌、港股缺市值等），不加分。</p>
     */
    public static boolean inRange(double value, double min, double max) {
        if (Double.isNaN(value) || value <= 0) {
            return false;
        }
        boolean hasMin = min > 0;
        boolean hasMax = max > 0;
        if (!hasMin && !hasMax) {
            return false;
        }
        if (hasMin && value < min) {
            return false;
        }
        if (hasMax && value > max) {
            return false;
        }
        return true;
    }

    /** 分时价格不低于均价（容忍 1 分钱浮点误差）的分钟占比，0~1。 */
    public static double vwapAboveRatio(List<IntradayItem> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        int total = 0;
        int above = 0;
        for (IntradayItem item : items) {
            if (item == null || item.getAvgPrice() <= 0 || item.getPrice() <= 0) {
                continue;
            }
            total++;
            if (item.getPrice() + 1e-6 >= item.getAvgPrice()) {
                above++;
            }
        }
        return total == 0 ? 0 : (double) above / total;
    }

    /**
     * 最近 {@code withinDays} 个交易日内是否出现过涨停。
     *
     * <p>日 K 没有分时明细，用「最高价触及涨停价」或「收盘等于涨停价」近似判断；
     * 涨跌停幅度复用 {@link PriceLimit#rate(String, String)}（主板 10%、创业/科创 20%、北交所 30%、ST 5%）。</p>
     */
    public static boolean hasLimitUp(List<KlineItem> bars, int withinDays, String code, String name) {
        if (bars == null || bars.size() < 2) {
            return false;
        }
        int window = Math.min(Math.max(withinDays, 1), bars.size() - 1);
        int last = bars.size() - 1;
        for (int i = last; i > last - window; i--) {
            KlineItem bar = bars.get(i);
            KlineItem prev = bars.get(i - 1);
            if (bar == null || prev == null || prev.getClose() <= 0) {
                continue;
            }
            BigDecimal limitUp = PriceLimit.limitUp(BigDecimal.valueOf(prev.getClose()), code, name);
            if (limitUp == null) {
                continue;
            }
            double limit = limitUp.doubleValue();
            if (bar.getHigh() + 0.005 >= limit || bar.getClose() + 0.005 >= limit) {
                return true;
            }
        }
        return false;
    }

    /**
     * 上一交易日（最后已收盘的那天）的连续涨停数。
     *
     * <p>日 K 在盘中会带上当日未收盘的 bar，所以先判断最后一根的日期是不是今天：
     * 是则从倒数第二根往前数，否则最后一根就是上一交易日（盘前尚未生成当日 bar）。</p>
     *
     * <p>用「收盘价 ≥ 涨停价」判断封板，不把盘中触板算作涨停，避免炸板票被当成连板。
     * 第一根 bar 没有前收盘价，连续计数到此为止。</p>
     *
     * @return 连板数；0 表示上一交易日没有涨停；-1 表示数据不足
     */
    public static int boardHeight(List<KlineItem> bars, String code, String name) {
        if (bars == null || bars.size() < 2) {
            return -1;
        }
        int last = bars.size() - 1;
        String today = LocalDate.now(ZONE).toString();
        int ref = today.equals(bars.get(last).getDate()) ? last - 1 : last;
        if (ref < 1) {
            return -1;
        }
        int count = 0;
        int i = ref;
        while (i >= 1) {
            KlineItem bar = bars.get(i);
            KlineItem prev = bars.get(i - 1);
            if (bar == null || prev == null || prev.getClose() <= 0 || bar.getClose() <= 0) {
                break;
            }
            BigDecimal limitUp = PriceLimit.limitUp(BigDecimal.valueOf(prev.getClose()), code, name);
            if (limitUp == null || bar.getClose() + 0.005 < limitUp.doubleValue()) {
                break;
            }
            count++;
            i--;
        }
        return count;
    }

    /** 上一交易日是否为首板（涨停，且前一天没有涨停）。 */
    public static boolean isFirstBoard(List<KlineItem> bars, String code, String name) {
        return boardHeight(bars, code, name) == 1;
    }

    /** 两年大约 486 个交易日；深历史按这个窗口判断「历史新高」。 */
    public static final int TWO_YEAR_BARS = 486;

    /**
     * 硬过滤要用的一组技术指标，一次算好传下去，避免调用方反复从 bars 重算。
     *
     * @param volumeRatio 量比（最后一日量 ÷ 前 5 日均量）
     * @param ma5         5 日均价
     * @param ma20        20 日均价
     * @param volumeTrend 5 日均量 ÷ 20 日均量
     */
    public record Technicals(double volumeRatio, double ma5, double ma20, double volumeTrend) {
    }

    /** 一年大约 242 个交易日，用于「现价 / 一年内最低价」。 */
    public static final int ONE_YEAR_BARS = 242;

    /**
     * 现价与近 {@code lookbackBars} 根日 K 内最低价的比值（现价 ÷ 最低价）。
     *
     * <p>比值越接近 1 说明离一年低点越近，用户要的「从小到大排列」就是按这个值升序。
     * 用复权后的最低价（与现价同口径），停牌/数据不足返回 {@link Double#NaN}。</p>
     */
    public static double lowRatio(List<KlineItem> bars, int lookbackBars) {
        if (bars == null || bars.isEmpty()) {
            return Double.NaN;
        }
        int last = bars.size() - 1;
        KlineItem latest = bars.get(last);
        if (latest == null || latest.getClose() <= 0) {
            return Double.NaN;
        }
        int lookback = Math.min(Math.max(lookbackBars, 20), bars.size());
        double lowest = 0;
        for (int i = last - lookback + 1; i <= last; i++) {
            if (i < 0) {
                continue;
            }
            KlineItem bar = bars.get(i);
            if (bar == null || bar.getLow() <= 0) {
                continue;
            }
            lowest = lowest <= 0 ? bar.getLow() : Math.min(lowest, bar.getLow());
        }
        if (lowest <= 0) {
            return Double.NaN;
        }
        return latest.getClose() / lowest;
    }

    /**
     * 短均量 / 长均量（如 5 日均量 ÷ 20 日均量）。数据不足返回 {@link Double#NaN}。
     */
    public static double volumeTrendRatio(List<KlineItem> bars, int shortPeriod, int longPeriod) {
        if (bars == null || bars.size() < Math.max(shortPeriod, longPeriod)) {
            return Double.NaN;
        }
        int size = bars.size();
        double shortSum = 0;
        for (int i = size - shortPeriod; i < size; i++) {
            shortSum += bars.get(i).getVolume();
        }
        double longSum = 0;
        for (int i = size - longPeriod; i < size; i++) {
            longSum += bars.get(i).getVolume();
        }
        if (longSum <= 0 || shortSum <= 0) {
            return Double.NaN;
        }
        return (shortSum / shortPeriod) / (longSum / longPeriod);
    }

    /**
     * 分时窗口 [start, end]（HH:mm，含两端）内的成交量之和，单位「手」。
     *
     * <p>用于「9:25-9:40 成交量 ÷ 自由流通股」这类早盘放量条件。
     * 腾讯分时从 09:30 才开始给分钟点（集合竞价的量并进 09:30 那根），
     * 所以窗口起点早于 09:30 时按 09:30 起算，不会漏掉竞价成交。</p>
     */
    public static double windowVolume(List<IntradayItem> items, String start, String end) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        int from = minutes(start, 9 * 60 + 25);
        int to = minutes(end, 9 * 60 + 40);
        if (to < from) {
            return 0;
        }
        double sum = 0;
        for (IntradayItem item : items) {
            if (item == null || item.getVolume() <= 0) {
                continue;
            }
            int t = minutes(item.getTime(), -1);
            if (t < 0) {
                continue;
            }
            if (t >= from && t <= to) {
                sum += item.getVolume();
            }
        }
        return sum;
    }

    /**
     * 集合竞价成交量（单位「手」）。
     *
     * <p>腾讯分钟分时的 <b>09:30 那根就是集合竞价单独一根</b>：它的成交量等于
     * 9:25 撮合成交的量，09:31 那根才是连续竞价的第一分钟（已用东财逐笔
     * {@code 09:25:00} 行的 cumVol 交叉验证过三只票，完全相等）。
     * 所以取时间最早的那根即可，不需要另接逐笔接口。</p>
     *
     * <p>容错：分时被截断（只有下午的数据）或第一根不是 09:30 时，返回 0 而不是
     * 「第一根的成交量」—— 后者会把某一分钟的连续竞价量当成竞价量，制造假信号。</p>
     */
    public static double auctionVolume(List<IntradayItem> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        for (IntradayItem item : items) {
            if (item == null) {
                continue;
            }
            int t = minutes(item.getTime(), -1);
            if (t == OPEN_AUCTION_MINUTE) {
                return Math.max(item.getVolume(), 0);
            }
        }
        return 0;
    }

    /** 集合竞价撮合时刻（09:30 那根分钟 bar 代表竞价成交）。 */
    private static final int OPEN_AUCTION_MINUTE = 9 * 60 + 30;

    /** "09:30" → 570；解析失败返回 fallback。 */
    private static int minutes(String hhmm, int fallback) {
        if (hhmm == null || hhmm.length() != 5 || hhmm.charAt(2) != ':') {
            return fallback;
        }
        try {
            int h = Integer.parseInt(hhmm.substring(0, 2));
            int m = Integer.parseInt(hhmm.substring(3, 5));
            return h * 60 + m;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 最近 {@code withinDays} 个交易日内，是否有一天的最高价超过此前
     * {@code lookbackBars} 根日 K 的最高价（即创出阶段新高）。
     *
     * <p>用最高价而不是收盘价：盘中冲过前高又回落也算触及过新高，
     * 这类票的「突破」意义同样成立，且实盘里更容易被看到。</p>
     */
    public static boolean newHighWithin(List<KlineItem> bars, int withinDays, int lookbackBars) {
        if (bars == null || bars.size() < 2) {
            return false;
        }
        int last = bars.size() - 1;
        int days = Math.min(Math.max(withinDays, 1), last);
        int lookback = Math.min(Math.max(lookbackBars, 30), last);
        for (int i = last - days + 1; i <= last; i++) {
            if (i < 1) {
                continue;
            }
            KlineItem bar = bars.get(i);
            if (bar == null || bar.getHigh() <= 0) {
                continue;
            }
            int from = Math.max(0, i - lookback);
            double priorHigh = 0;
            for (int j = from; j < i; j++) {
                KlineItem older = bars.get(j);
                if (older != null) {
                    priorHigh = Math.max(priorHigh, older.getHigh());
                }
            }
            if (priorHigh > 0 && bar.getHigh() > priorHigh + 0.005) {
                return true;
            }
        }
        return false;
    }

    /**
     * 缩量上涨：最后已收盘的那根收涨，且成交量不超过前一交易日的 {@code maxRatio} 倍。
     *
     * <p>盘中当日 bar 只有部分成交量，和完整的前一日不可比（否则几乎所有上涨票都算缩量），
     * 所以先判断最后一根是不是今天：是则回退到昨日那根，即「昨日缩量上涨」作为今日买入信号。
     * 用前一交易日量能而不是均量，是为了让信号出现在刚缩量的第一天。</p>
     */
    public static boolean shrinkVolumeRise(List<KlineItem> bars, double maxRatio) {
        if (bars == null || bars.size() < 2) {
            return false;
        }
        int last = bars.size() - 1;
        if (LocalDate.now(ZONE).toString().equals(bars.get(last).getDate())) {
            last--;
        }
        if (last < 1) {
            return false;
        }
        KlineItem bar = bars.get(last);
        KlineItem prev = bars.get(last - 1);
        if (bar == null || prev == null) {
            return false;
        }
        if (bar.getClose() <= 0 || prev.getClose() <= 0
                || bar.getVolume() <= 0 || prev.getVolume() <= 0) {
            return false;
        }
        double ratio = Math.max(0.1, Math.min(1.0, maxRatio));
        return bar.getClose() > prev.getClose() && bar.getVolume() <= prev.getVolume() * ratio;
    }
}
