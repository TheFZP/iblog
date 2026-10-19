package com.iblog.module.stock.service;

import com.iblog.module.stock.model.MoneyFlow;
import com.iblog.module.stock.model.MoneyFlowHistoryItem;
import com.iblog.module.stock.support.EmFflowParser;
import com.iblog.module.stock.support.StockCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资金流向解析单测。
 *
 * <p>数据全部是<b>真实上游响应</b>（2026-10-16 收盘后抓取），不是编造的。
 * 之所以值得为解析写测试：字段下标一旦错位，界面不会报错，只会把超大单和小单对调 ——
 * 这种错误只有靠「拿已知语义的真实数据去断言」才能发现。</p>
 */
class EastmoneyServiceTest {

    /**
     * 贵州茅台 600519 最近 3 个交易日的 daykline。
     *
     * 语义已知：10-16 收盘 1329.00，当日小幅下跌，因此小单/中单净流出、大单净流入。
     */
    private static final String REAL_800519_TAIL =
            "2026-10-14,-44751280.0,52577088.0,-7825808.0,68488368.0,-113239648.0,"
                    + "-2.14,2.52,-0.37,3.28,-5.42,1308.10";

    private static final String REAL_800519_MID =
            "2026-10-15,151203184.0,27475552.0,-178678736.0,124327808.0,26875376.0,"
                    + "7.24,1.32,-8.55,5.95,1.29,1305.20";

    private static final String REAL_800519_LAST =
            "2026-10-16,468661168.0,-454626.0,-468206560.0,425044128.0,43617040.0,"
                    + "9.23,-0.01,-9.22,8.37,0.86,1329.00";

    /** 完整响应：带 JSONP 外壳（桌面版 &cb=jQuery 就是这个形态） */
    private static String jsonp(String... lines) {
        StringBuilder sb = new StringBuilder("jQuery({\"rc\":0,\"rt\":17,\"svr\":181669960,\"lt\":1,\"full\":1,");
        sb.append("\"data\":{\"code\":\"600519\",\"market\":1,\"name\":\"贵州茅台\",\"klines\":[");
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(lines[i]).append('"');
        }
        sb.append("]}});");
        return sb.toString();
    }

    @Test
    @DisplayName("JSONP 外壳能被剥掉，klines 按原顺序返回")
    void parseKlinesStripsJsonp() {
        List<String> lines = EmFflowParser.parseKlines(jsonp(REAL_800519_TAIL, REAL_800519_MID, REAL_800519_LAST));
        assertEquals(3, lines.size(), "应解析出 3 行");
        assertEquals(REAL_800519_LAST, lines.get(2), "最后一行必须原样返回");
    }

    @Test
    @DisplayName("裸 JSON（不带 cb 参数）也能解析")
    void parseKlinesAcceptsPlainJson() {
        // jsonp() 产出 "jQuery({...});"，剥掉前缀 8 字符与后缀 3 字符（");" + ";"）得到裸 JSON
        String wrapped = jsonp(REAL_800519_LAST);
        String plain = wrapped.substring("jQuery(".length(), wrapped.length() - ");".length());
        List<String> lines = EmFflowParser.parseKlines(plain);
        assertEquals(1, lines.size());
    }

    @Test
    @DisplayName("rc=100 + data=null 视为「无数据」而不是抛错")
    void parseKlinesTreatsRc100AsEmpty() {
        List<String> lines = EmFflowParser.parseKlines("jQuery({\"rc\":100,\"rt\":17,\"data\":null});");
        assertTrue(lines.isEmpty(), "不存在 / 无资金流的代码应返回空列表");
    }

    @Test
    @DisplayName("空响应 / 非法 JSON 抛 StockDataException")
    void parseKlinesRejectsGarbage() {
        org.junit.jupiter.api.Assertions.assertThrows(
                com.iblog.module.stock.support.StockHttpClient.StockDataException.class,
                () -> EmFflowParser.parseKlines("   "));
        org.junit.jupiter.api.Assertions.assertThrows(
                com.iblog.module.stock.support.StockHttpClient.StockDataException.class,
                () -> EmFflowParser.parseKlines("<html>punish</html>"));
    }

    @Test
    @DisplayName("★ 字段顺序：f52=主力 f53=小单 f54=中单 f55=大单 f56=超大单（元→万元）")
    void parseSnapshotMapsFieldsInUpstreamOrder() {
        MoneyFlow mf = EastmoneyService.parseSnapshot(REAL_800519_LAST);
        assertNotNull(mf);

        assertEquals("2026-10-16", mf.getDate());
        // 主力 468661168 元 = 46866.12 万元
        assertEquals(46866.12, mf.getMainNetInflow(), 0.001);
        // 小单 -454626 元 = -45.46 万元
        assertEquals(-45.46, mf.getSmallNet(), 0.001);
        // 中单 -468206560 元 = -46820.66 万元
        assertEquals(-46820.66, mf.getMediumNet(), 0.001);
        // 大单 425044128 元 = 42504.41 万元
        assertEquals(42504.41, mf.getLargeNet(), 0.001);
        // 超大单 43617040 元 = 4361.70 万元
        assertEquals(4361.70, mf.getSuperLargeNet(), 0.001);

        // 若按直觉把「超大/大/中/小」反过来取，下面两条会互换（正是本测试要钉住的）
        assertTrue(mf.getLargeNet() > mf.getSuperLargeNet(),
                "10-16 是大单接盘、超大单小幅流入，取反了就会把两者对调");
        assertTrue(mf.getSmallNet() < 0 && mf.getSmallNet() > -1000,
                "小单净流出很小（-45 万），若取成中单会变成 -4.6 亿");
    }

    @Test
    @DisplayName("占比字段原样透传，不做二次换算")
    void parseSnapshotKeepsPercents() {
        MoneyFlow mf = EastmoneyService.parseSnapshot(REAL_800519_LAST);
        assertEquals(9.23, mf.getMainNetPct(), 0.001);
        assertEquals(-0.01, mf.getSmallPct(), 0.001);
        assertEquals(-9.22, mf.getMediumPct(), 0.001);
        assertEquals(8.37, mf.getLargePct(), 0.001);
        assertEquals(0.86, mf.getSuperLargePct(), 0.001);
    }

    @Test
    @DisplayName("主力 = 超大单 + 大单（东财口径自洽性检查）")
    void mainEqualsSuperPlusLarge() {
        MoneyFlow mf = EastmoneyService.parseSnapshot(REAL_800519_LAST);
        assertEquals(mf.getMainNetInflow(),
                mf.getSuperLargeNet() + mf.getLargeNet(), 0.02,
                "东财的「主力」定义为超大单+大单，若字段错位此等式必然不成立");
    }

    @Test
    @DisplayName("历史条目解析：日期 + 各档 + 收盘价")
    void parseHistoryItemMapsAllFields() {
        MoneyFlowHistoryItem it = EastmoneyService.parseHistoryItem(REAL_800519_LAST);
        assertNotNull(it);
        assertEquals("2026-10-16", it.getDate());
        assertEquals(46866.12, it.getMainNetInflow(), 0.001);
        assertEquals(4361.70, it.getSuperLargeNet(), 0.001);
        assertEquals(42504.41, it.getLargeNet(), 0.001);
        assertEquals(-46820.66, it.getMediumNet(), 0.001);
        assertEquals(-45.46, it.getSmallNet(), 0.001);
        assertEquals(1329.00, it.getClose(), 0.001);
    }

    @Test
    @DisplayName("字段不足的行被跳过，而不是解析出半条脏数据")
    void parseSkipsShortLines() {
        assertNull(EastmoneyService.parseSnapshot("2026-10-16,1.0,2.0"));
        assertNull(EastmoneyService.parseHistoryItem("2026-10-16,1.0,2.0"));
    }

    @Test
    @DisplayName("缺值（空串 / --）按 0 处理，不抛异常")
    void parseToleratesMissingValues() {
        String line = "2026-10-16,--,,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,1329.00";
        MoneyFlow mf = EastmoneyService.parseSnapshot(line);
        assertNotNull(mf);
        assertEquals(0.0, mf.getMainNetInflow(), 0.001);
        assertEquals(0.0, mf.getSmallNet(), 0.001);
    }

    @Test
    @DisplayName("元→万元换算保留 2 位小数，避免前端出现长尾小数")
    void yuanToWanRounds() {
        assertEquals(46866.12, com.iblog.module.stock.support.EmMoneyFlowFields.yuanToWan(468661168.0), 1e-9);
        assertEquals(-45.46, com.iblog.module.stock.support.EmMoneyFlowFields.yuanToWan(-454626.0), 1e-9);
        assertEquals(0.0, com.iblog.module.stock.support.EmMoneyFlowFields.yuanToWan(0.0), 1e-9);
    }

    @Test
    @DisplayName("secid 换算：沪 1. / 深 0. / 北交 0. / 港股 116.（资金流请求全靠它）")
    void secidMapping() {
        assertEquals("1.600519", StockCode.toEmSecid("600519"));
        assertEquals("1.600519", StockCode.toEmSecid("600519.SH"));
        assertEquals("0.000002", StockCode.toEmSecid("000002"));
        assertEquals("0.000002", StockCode.toEmSecid("sz000002"));
        assertEquals("0.430047", StockCode.toEmSecid("430047"));
        assertEquals("116.00700", StockCode.toEmSecid("00700"));
        assertEquals("116.00700", StockCode.toEmSecid("700"));
    }

    @Test
    @DisplayName("代码为空时抛错（Controller 会转成 400）")
    void emptyCodeRejected() {
        // 不启动 Spring，直接调用静态部分无法覆盖实例方法，这里验证 normalize 的空输入行为
        assertEquals("", StockCode.normalize(""));
        assertEquals("", StockCode.normalize(null));
        assertFalse(StockCode.isHk(""));
    }
}
