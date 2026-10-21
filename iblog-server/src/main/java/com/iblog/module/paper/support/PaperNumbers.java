package com.iblog.module.paper.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 模拟盘金额/比例的统一口径。
 *
 * <p>数据库里金额是 DECIMAL(20,2)、价格是 DECIMAL(16,4)，
 * 这里集中收口，避免各服务自己 setScale 忘了四舍五入。</p>
 */
public final class PaperNumbers {

    public static final BigDecimal HUNDRED = new BigDecimal("100");

    private PaperNumbers() {
    }

    public static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public static int nzInt(Integer value) {
        return value == null ? 0 : value;
    }

    /** 金额：2 位小数，四舍五入。 */
    public static BigDecimal money(BigDecimal value) {
        return nz(value).setScale(2, RoundingMode.HALF_UP);
    }

    /** 价格/成本价：4 位小数，四舍五入。 */
    public static BigDecimal price(BigDecimal value) {
        return nz(value).setScale(4, RoundingMode.HALF_UP);
    }

    /** 百分比：part / base * 100，2 位小数；base 非正时返回 0。 */
    public static BigDecimal pct(BigDecimal part, BigDecimal base) {
        if (base == null || base.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return nz(part).multiply(HUNDRED).divide(base, 2, RoundingMode.HALF_UP);
    }
}
