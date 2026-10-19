package com.iblog.module.stock.support;

/**
 * 东方财富 fflow 接口的字段口径（唯一真源）。
 *
 * <p><b>为什么把这个搬进独立类</b>：这些下标一旦错位不会报错，只会让「涨了显示跌」，
 * 属于最难发现的静默错误。集中定义 + 用真实响应做单测钉住，比在 service 里散落魔数安全得多。</p>
 *
 * <h3>请求参数</h3>
 * <pre>
 * fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61,f62
 * </pre>
 *
 * <h3>响应里每行 klines 的逗号分隔顺序（实测 2026-10-16 收盘数据核对过语义）</h3>
 * <pre>
 * 下标  字段   含义
 *  0    f51    交易日 yyyy-MM-dd
 *  1    f52    主力净流入（元，负号表示净流出）
 *  2    f53    小单净流入（元）
 *  3    f54    中单净流入（元）
 *  4    f55    大单净流入（元）
 *  5    f56    超大单净流入（元）
 *  6    f57    主力净占比（%）
 *  7    f58    小单净占比（%）
 *  8    f59    中单净占比（%）
 *  9    f60    大单净占比（%）
 * 10    f61    超大单净占比（%）
 * 11    f62    收盘价
 * </pre>
 *
 * <p><b>注意顺序陷阱</b>：净流入是 [主力, 小, 中, 大, 超大] 这个「从大到小倒着排」的顺序，
 * 而占比是 [主力, 小, 中, 大, 超大] 同样的顺序。直觉上会以为是「超大/大/中/小」，
 * 按直觉取会把超大单和小单对调 —— 单测已覆盖此点。</p>
 */
public final class EmMoneyFlowFields {

    private EmMoneyFlowFields() {
    }

    /** f51 交易日 */
    public static final int IDX_DATE = 0;
    /** f52 主力净流入（元） */
    public static final int IDX_MAIN_NET = 1;
    /** f53 小单净流入（元） */
    public static final int IDX_SMALL_NET = 2;
    /** f54 中单净流入（元） */
    public static final int IDX_MEDIUM_NET = 3;
    /** f55 大单净流入（元） */
    public static final int IDX_LARGE_NET = 4;
    /** f56 超大单净流入（元） */
    public static final int IDX_SUPER_NET = 5;
    /** f57 主力净占比（%） */
    public static final int IDX_MAIN_PCT = 6;
    /** f58 小单净占比（%） */
    public static final int IDX_SMALL_PCT = 7;
    /** f59 中单净占比（%） */
    public static final int IDX_MEDIUM_PCT = 8;
    /** f60 大单净占比（%） */
    public static final int IDX_LARGE_PCT = 9;
    /** f61 超大单净占比（%） */
    public static final int IDX_SUPER_PCT = 10;
    /** f62 收盘价 */
    public static final int IDX_CLOSE = 11;

    /** 含收盘价在内，一行至少要有 12 个字段才算完整 */
    public static final int MIN_FIELDS = 12;

    /** 请求参数里 fields2 的取值（顺序必须与上面的下标一一对应） */
    public static final String FIELDS2 = "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61,f62";

    /** 元 → 万元 */
    public static final double YUAN_PER_WAN = 10000.0;

    /**
     * 把「元」换算成项目统一的「万元」。
     *
     * @param yuan 原始值（元）
     * @return 万元，保留 2 位小数（避免前端出现 -46820.656 这种毛刺）
     */
    public static double yuanToWan(double yuan) {
        return Math.round(yuan / YUAN_PER_WAN * 100.0) / 100.0;
    }

    /**
     * 安全取下标对应的字符串值。
     */
    public static String at(String[] fields, int idx) {
        return idx >= 0 && idx < fields.length && fields[idx] != null ? fields[idx].trim() : "";
    }

    /**
     * 安全取下标对应的数值。空串 / "--" / 非数字一律返回 0（上游偶有缺值）。
     */
    public static double num(String[] fields, int idx) {
        String s = at(fields, idx);
        if (s.isEmpty() || "--".equals(s)) {
            return 0;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
