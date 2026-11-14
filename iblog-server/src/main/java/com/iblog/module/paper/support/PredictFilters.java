package com.iblog.module.paper.support;

import com.iblog.config.IblogProperties;
import com.iblog.module.stock.model.StockQuote;
import com.iblog.module.stock.support.StockCode;

/**
 * 全市场次日预测的预筛规则（纯函数，便于单测）。
 *
 * <p>预筛发生在买行情之后、读 K 线之前：K 线只能逐只拉，必须先把
 * 停牌、ST、北交所、低价股、成交太小的票挡掉，否则 15:30 的任务跑不完。</p>
 */
public final class PredictFilters {

    private PredictFilters() {
    }

    /** 预筛参数（从 {@link IblogProperties.Predict} 拷贝，避免纯函数依赖 Spring）。 */
    public record Options(
            double minAmountWan,
            double minPrice,
            double maxPrice,
            double maxChangePct,
            boolean excludeSt,
            boolean excludeBse,
            boolean excludeLimitUpAtClose) {

        public static Options from(IblogProperties.Predict predict) {
            return new Options(
                    predict.getMinAmountWan(),
                    predict.getMinPrice(),
                    predict.getMaxPrice(),
                    predict.getMaxChangePct(),
                    predict.isExcludeSt(),
                    predict.isExcludeBse(),
                    predict.isExcludeLimitUpAtClose());
        }
    }

    /**
     * 返回拒绝原因；通过预筛返回 {@code null}。
     */
    public static String rejectReason(StockQuote quote, Options options) {
        if (quote == null) {
            return "无行情";
        }
        String code = StockCode.normalize(quote.getCode());
        String name = quote.getName() == null ? "" : quote.getName().trim();

        if (StockCode.isHk(code)) {
            return "港股";
        }
        if (options.excludeBse() && StockCode.isBse(code)) {
            return "北交所";
        }
        if (options.excludeSt() && isSpecialTreatment(name)) {
            return "ST/退市/新股";
        }
        if (quote.getPrice() <= 0 || quote.getVolume() <= 0) {
            return "停牌或无效行情";
        }
        if (quote.getPrevClose() <= 0) {
            return "昨收缺失";
        }
        if (quote.getPrice() < options.minPrice()) {
            return "价格低于 " + options.minPrice();
        }
        if (quote.getPrice() > options.maxPrice()) {
            return "价格高于 " + options.maxPrice();
        }
        if (quote.getTurnover() < options.minAmountWan()) {
            return "成交额不足";
        }
        if (quote.getChangePct() > options.maxChangePct()) {
            return "涨幅过大";
        }
        if (quote.getChangePct() < -options.maxChangePct() - 0.5) {
            return "跌幅过大";
        }
        if (options.excludeLimitUpAtClose() && isLimitUpAtClose(code, name, quote)) {
            return "收盘涨停";
        }
        return null;
    }

    /** ST / *ST / 退市整理 / 上市首日（N）/ 上市前 5 日（C）。 */
    public static boolean isSpecialTreatment(String name) {
        if (isSt(name)) {
            return true;
        }
        if (name == null || name.isEmpty()) {
            return false;
        }
        String upper = name.toUpperCase();
        return upper.startsWith("N") || upper.startsWith("C");
    }

    /** 仅 ST / 退市整理（新股没有 5% 涨停限制，不能混进这里判断涨停幅度）。 */
    public static boolean isSt(String name) {
        return name != null && (name.toUpperCase().contains("ST") || name.contains("退"));
    }

    /**
     * 收盘是否涨停。
     *
     * <p>涨跌幅限制按板块区分：主板 10%，创业板 / 科创板 20%，ST 5%。
     * 价格恰好封在涨停价时，次日很难按开盘价成交，所以默认排除。</p>
     */
    public static boolean isLimitUpAtClose(String code, String name, StockQuote quote) {
        double limitPct = limitUpPct(code, name);
        if (limitPct <= 0) {
            return false;
        }
        // 允许 0.15 个百分点的浮点/四舍五入误差
        if (quote.getChangePct() < limitPct - 0.15) {
            return false;
        }
        // 有些行情源会给出略高于涨停幅度的异常值，再用「收盘=最高价」交叉验证
        return quote.getHigh() <= 0 || quote.getPrice() >= quote.getHigh() - 0.001;
    }

    /** 涨停幅度（%）；无法判断时给主板 10%。 */
    public static double limitUpPct(String code, String name) {
        String c = StockCode.normalize(code);
        if (isSt(name)) {
            return 5;
        }
        if (c.startsWith("300") || c.startsWith("301") || c.startsWith("688") || c.startsWith("689")) {
            return 20;
        }
        return 10;
    }
}
