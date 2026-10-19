package com.iblog.module.stock.support;

/**
 * 股票代码归一化与市场判定。
 *
 * <p>口径对齐桌面版 stock-analysis（Rust {@code helpers.rs}），改这里必须两边同步：</p>
 * <ul>
 *   <li>A 股 6 位；港股 5 位（可能前导 0，如 00700）；北交所 43/82/83/87/88/92 开头</li>
 *   <li>外部来源（问财等）常带 {@code .SH / .SZ / .BJ} 后缀，必须先剥离</li>
 * </ul>
 */
public final class StockCode {

    private StockCode() {
    }

    /**
     * 归一化：去空格、去 {@code .SH/.SZ/.BJ} 后缀、港股补足 5 位。
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String code = raw.trim().toUpperCase();
        // 剥离 .SH / .SZ / .BJ 后缀（外部数据源常见）
        int dot = code.lastIndexOf('.');
        if (dot > 0 && dot == code.length() - 3) {
            String suffix = code.substring(dot + 1);
            if ("SH".equals(suffix) || "SZ".equals(suffix) || "BJ".equals(suffix) || "HK".equals(suffix)) {
                code = code.substring(0, dot);
            }
        }
        // 去掉可能存在的市场前缀
        if (code.startsWith("SH") || code.startsWith("SZ") || code.startsWith("BJ") || code.startsWith("HK")) {
            String stripped = code.substring(2);
            if (!stripped.isEmpty() && isDigits(stripped)) {
                code = stripped;
            }
        }
        // 港股：纯数字且不足 5 位时左补 0（如 "700" → "00700"）
        if (!code.isEmpty() && isDigits(code) && code.length() < 5) {
            code = String.format("%5s", code).replace(' ', '0');
        }
        return code;
    }

    /**
     * 是否为港股：5 位纯数字。
     */
    public static boolean isHk(String code) {
        String c = stripMarketPrefix(code);
        return c.length() == 5 && isDigits(c);
    }

    /**
     * 是否为北交所：6 位纯数字且以 43/82/83/87/88/92 开头。
     */
    public static boolean isBse(String code) {
        String c = stripMarketPrefix(code);
        if (c.length() != 6 || !isDigits(c)) {
            return false;
        }
        String p2 = c.substring(0, 2);
        return "43".equals(p2) || "82".equals(p2) || "83".equals(p2)
                || "87".equals(p2) || "88".equals(p2) || "92".equals(p2);
    }

    /**
     * 是否沪市：6 开头（沪 A）或 900 开头（沪 B）。
     */
    public static boolean isShanghai(String code) {
        String c = stripMarketPrefix(code);
        return c.startsWith("6") || c.startsWith("900");
    }

    /**
     * 腾讯代码：{@code sh600519 / sz300750 / hk00700 / bj430047}。
     * 注意小写，腾讯接口区分大小写。
     */
    public static String toTencent(String code) {
        // 用 normalize 而不是 stripMarketPrefix：后者不剥 ".SH" 后缀，
        // 会让 "600519.SH" 变成 "sh600519.SH"（单测 EastmoneyServiceTest.secidMapping 覆盖）
        String c = normalize(code);
        if (isHk(c)) {
            return "hk" + c;
        }
        if (isBse(c)) {
            return "bj" + c;
        }
        return isShanghai(c) ? "sh" + c : "sz" + c;
    }

    /**
     * 东方财富 secid：{@code 1.600519 / 0.300750 / 116.00700 / 0.430047}。
     */
    public static String toEmSecid(String code) {
        // 同 toTencent：必须走 normalize，否则 "000002.SZ" 会变成 "0.000002.SZ"
        String c = normalize(code);
        if (isHk(c)) {
            return "116." + c;
        }
        if (isBse(c)) {
            // 东财把北交所归入 0 市场
            return "0." + c;
        }
        return isShanghai(c) ? "1." + c : "0." + c;
    }

    /**
     * 市场标识：SH / SZ / BJ / HK，用于前端展示与自选股分组。
     */
    public static String marketOf(String code) {
        String c = normalize(code);
        if (isHk(c)) {
            return "HK";
        }
        if (isBse(c)) {
            return "BJ";
        }
        return isShanghai(c) ? "SH" : "SZ";
    }

    /**
     * 币种符号：港股走港币，其余人民币。
     */
    public static String currencySymbol(String code) {
        return isHk(normalize(code)) ? "HK$" : "¥";
    }

    private static String stripMarketPrefix(String code) {
        if (code == null) {
            return "";
        }
        String c = code.trim().toUpperCase();
        if (c.startsWith("SH") || c.startsWith("SZ") || c.startsWith("BJ") || c.startsWith("HK")) {
            String stripped = c.substring(2);
            if (!stripped.isEmpty() && isDigits(stripped)) {
                return stripped;
            }
        }
        return c;
    }
    private static boolean isDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
