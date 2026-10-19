package com.iblog.module.stock.service;

import com.iblog.module.stock.support.StockCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 行情字段口径与代码归一化的回归测试。
 *
 * <p>这两类错误都不会抛异常：前者只会让数据显示成错的日期，后者只会让请求打到不存在的标的上。
 * 所以必须用断言钉死。</p>
 */
class TencentServiceFieldsTest {

    /** fixQuoteTime 是私有方法，用反射调用 —— 比为了测试把它改成 public 更划算 */
    private String fixQuoteTime(String raw) throws Exception {
        TencentService svc = new TencentService(null);
        Method m = TencentService.class.getDeclaredMethod("fixQuoteTime", String.class);
        m.setAccessible(true);
        return (String) m.invoke(svc, raw);
    }

    @Test
    @DisplayName("腾讯 [30] 字段 14 位数字 → yyyy-MM-dd HH:mm:ss")
    void formatTencentTimestamp() throws Exception {
        assertEquals("2026-10-19 09:27:30", fixQuoteTime("20261019092730"));
        assertEquals("2026-10-16 15:00:03", fixQuoteTime("20261016150003"));
    }

    @Test
    @DisplayName("已是带分隔符的格式原样透传")
    void keepReadableTimestamp() throws Exception {
        assertEquals("2026-10-19 09:27:30", fixQuoteTime("2026-10-19 09:27:30"));
    }

    @Test
    @DisplayName("港股 [30] 是 yyyy/M/d H:mm:ss（月日不补零），必须归一化而不是丢弃")
    void formatHkTimestamp() throws Exception {
        // 实测原值：hk00700 → "2026/10/16 16:08:22"
        assertEquals("2026-10-16 16:08:22", fixQuoteTime("2026/10/16 16:08:22"));
        // 单数字月/日/时也要补齐
        assertEquals("2026-01-05 09:03:07", fixQuoteTime("2026/1/5 9:03:07"));
    }

    @Test
    @DisplayName("空 / 异常值返回空串，不抛异常（时间戳缺失不该让行情请求失败）")
    void tolerateBadTimestamp() throws Exception {
        assertEquals("", fixQuoteTime(""));
        assertEquals("", fixQuoteTime(null));
        assertEquals("", fixQuoteTime("bad"));
        assertEquals("", fixQuoteTime("2026101909273"));  // 13 位
        assertEquals("", fixQuoteTime("202610190927301")); // 15 位
    }

    @Test
    @DisplayName("★ 带 .SH/.SZ 后缀的代码必须被正确换算（曾经 600519.SH → 1.600519.SH）")
    void secidAndTencentStripSuffix() {
        assertEquals("1.600519", StockCode.toEmSecid("600519.SH"));
        assertEquals("0.000002", StockCode.toEmSecid("000002.SZ"));
        assertEquals("0.430047", StockCode.toEmSecid("430047.BJ"));
        assertEquals("116.00700", StockCode.toEmSecid("00700.HK"));

        assertEquals("sh600519", StockCode.toTencent("600519.SH"));
        assertEquals("sz000002", StockCode.toTencent("000002.SZ"));
        assertEquals("hk00700", StockCode.toTencent("00700.HK"));

        assertEquals("SH", StockCode.marketOf("600519.SH"));
        assertEquals("HK", StockCode.marketOf("00700.HK"));
        assertEquals("HK$", StockCode.currencySymbol("00700.HK"));
        assertEquals("¥", StockCode.currencySymbol("600519.SH"));
    }

    @Test
    @DisplayName("小写前缀与之字形前缀都能归一化")
    void normalizeAcceptsPrefixedCodes() {
        assertEquals("600519", StockCode.normalize("sh600519"));
        assertEquals("600519", StockCode.normalize("SH600519"));
        assertEquals("00700", StockCode.normalize("700"));
        assertEquals("600519", StockCode.normalize(" 600519.sz "));
    }
}
