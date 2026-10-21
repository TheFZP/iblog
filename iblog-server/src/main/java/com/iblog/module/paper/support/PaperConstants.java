package com.iblog.module.paper.support;

/**
 * 模拟盘的字符串常量。
 *
 * <p>数据库里存的是字符串而不是枚举，方便直接看表；集中在这里避免各处拼错。</p>
 */
public final class PaperConstants {

    public static final String SIDE_BUY = "BUY";
    public static final String SIDE_SELL = "SELL";

    public static final String TYPE_MARKET = "MARKET";
    public static final String TYPE_LIMIT = "LIMIT";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_FILLED = "FILLED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_REJECTED = "REJECTED";

    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_STRATEGY = "STRATEGY";

    public static final String ACTION_CANDIDATE = "CANDIDATE";
    public static final String ACTION_BUY = "BUY";
    public static final String ACTION_SELL = "SELL";

    public static final String PERIOD_DAY = "day";

    private PaperConstants() {
    }
}
