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
}
