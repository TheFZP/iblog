package com.iblog.module.paper.support;

import com.iblog.module.stock.support.StockCode;

/**
 * A 股各板块的最小申报单位（「一手」不是处处都等于 100 股）。
 *
 * <p>这条规则直接决定「3 万本金买不买得起」：科创板限价/市价单笔申报
 * <b>不少于 200 股</b>，超过 200 股的部分才以 1 股递增；北交所不少于 100 股、
 * 之后 1 股递增；创业板与主板仍是 100 股整数倍。</p>
 *
 * <p>写这里而不是散在 {@link com.iblog.module.paper.service.PaperOrderService} 和
 * 自动交易里，是因为下单校验、自动买入取整、选股时「按资金筛可买性」三处
 * 必须用同一口径，否则会出现「选出来了却买不进」或者「能买却被筛掉」。</p>
 */
public final class LotRule {

    /** 主板/创业板的最小申报单位 */
    public static final int BOARD_LOT = 100;
    /** 科创板最小申报数量（超过后可 1 股递增） */
    public static final int STAR_MIN = 200;
    /** 北交所最小申报数量（超过后可 1 股递增） */
    public static final int BSE_MIN = 100;

    private LotRule() {
    }

    /** 是否科创板（688 科创板 / 689 科创板 CDR）。 */
    public static boolean isStarMarket(String code) {
        String c = StockCode.normalize(code);
        return c.startsWith("688") || c.startsWith("689");
    }

    /**
     * 最小申报数量：科创板 200 股，其它 100 股。
     *
     * <p>北交所名义上也是 100 股起，和主板一致，所以不用单列分支。</p>
     */
    public static int minLot(String code) {
        return isStarMarket(code) ? STAR_MIN : BOARD_LOT;
    }

    /**
     * 是否允许「1 股递增」：科创板与北交所是，主板/创业板必须以 100 股为单位。
     */
    public static boolean allowsOddShare(String code) {
        return isStarMarket(code) || StockCode.isBse(StockCode.normalize(code));
    }

    /** 委托数量是否合法。 */
    public static boolean isValidQuantity(String code, int quantity) {
        if (quantity <= 0) {
            return false;
        }
        int min = minLot(code);
        if (quantity < min) {
            return false;
        }
        if (allowsOddShare(code)) {
            return true;
        }
        return quantity % BOARD_LOT == 0;
    }

    /**
     * 把「按预算算出来的股数」向下取到合法数量；不足最小申报数量时返回 0。
     *
     * <p>科创板/北交所取整到 1 股，主板/创业板取整到 100 股。</p>
     */
    public static int roundDown(String code, int rawQuantity) {
        if (rawQuantity <= 0) {
            return 0;
        }
        int min = minLot(code);
        if (rawQuantity < min) {
            return 0;
        }
        if (allowsOddShare(code)) {
            return rawQuantity;
        }
        return rawQuantity / BOARD_LOT * BOARD_LOT;
    }

    /** 不合法的说明文案，拼错误信息用。 */
    public static String describe(String code) {
        String c = StockCode.normalize(code);
        if (isStarMarket(c)) {
            return "科创板委托数量需不少于 200 股，超过部分可 1 股递增";
        }
        if (StockCode.isBse(c)) {
            return "北交所委托数量需不少于 100 股，超过部分可 1 股递增";
        }
        return "A 股委托数量需为 100 股的整数倍";
    }
}
