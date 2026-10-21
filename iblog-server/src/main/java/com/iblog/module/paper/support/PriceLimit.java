package com.iblog.module.paper.support;

import com.iblog.module.stock.support.StockCode;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A 股涨跌停价计算。
 *
 * <p>规则按板块近似：</p>
 * <ul>
 *   <li>创业板（300/301）、科创板（688/689）：20%；</li>
 *   <li>北交所（43/82/83/87/88/92 开头）：30%；</li>
 *   <li>主板：10%，名称带 ST 的 5%。</li>
 * </ul>
 *
 * <p>新股上市首日、退市整理期等特殊情形没有可靠标记，模拟盘按普通规则处理，
 * 并在拒单原因里提示 —— 模拟盘追求的是「规则自洽」，不是交易所级精确。</p>
 */
public final class PriceLimit {

    private static final BigDecimal RATE_MAIN = new BigDecimal("0.10");
    private static final BigDecimal RATE_ST = new BigDecimal("0.05");
    private static final BigDecimal RATE_GROWTH = new BigDecimal("0.20");
    private static final BigDecimal RATE_BSE = new BigDecimal("0.30");

    private PriceLimit() {
    }

    /** 涨跌停比例。 */
    public static BigDecimal rate(String code, String name) {
        String normalized = code == null ? "" : code.trim();
        if (StockCode.isBse(normalized)) {
            return RATE_BSE;
        }
        boolean growth = normalized.startsWith("300") || normalized.startsWith("301")
                || normalized.startsWith("688") || normalized.startsWith("689");
        if (growth) {
            // 注册制板块的 ST 股同样是 20%
            return RATE_GROWTH;
        }
        return isSt(name) ? RATE_ST : RATE_MAIN;
    }

    public static BigDecimal limitUp(BigDecimal prevClose, String code, String name) {
        return limit(prevClose, code, name, true);
    }

    public static BigDecimal limitDown(BigDecimal prevClose, String code, String name) {
        return limit(prevClose, code, name, false);
    }

    /** 现价是否已经涨停（此时不追买）。 */
    public static boolean atLimitUp(BigDecimal price, BigDecimal prevClose, String code, String name) {
        BigDecimal up = limitUp(prevClose, code, name);
        return up != null && price != null && price.compareTo(up) >= 0;
    }

    /** 现价是否已经跌停（此时不砸卖）。 */
    public static boolean atLimitDown(BigDecimal price, BigDecimal prevClose, String code, String name) {
        BigDecimal down = limitDown(prevClose, code, name);
        return down != null && price != null && price.compareTo(down) <= 0;
    }

    private static BigDecimal limit(BigDecimal prevClose, String code, String name, boolean up) {
        if (prevClose == null || prevClose.signum() <= 0) {
            // 没有昨收（新股/数据缺失）时不做涨跌停限制
            return null;
        }
        BigDecimal ratio = rate(code, name);
        BigDecimal factor = up ? BigDecimal.ONE.add(ratio) : BigDecimal.ONE.subtract(ratio);
        return prevClose.multiply(factor).setScale(2, RoundingMode.HALF_UP);
    }

    private static boolean isSt(String name) {
        if (name == null) {
            return false;
        }
        String upper = name.toUpperCase();
        return upper.contains("ST");
    }
}
