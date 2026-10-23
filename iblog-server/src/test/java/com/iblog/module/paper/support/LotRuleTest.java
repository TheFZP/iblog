package com.iblog.module.paper.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 各板块最小申报单位的口径测试。
 *
 * <p>重点覆盖「3 万本金买科创板」这类边界：688 起买 200 股，
 * 200 股以上可以 1 股递增，主板/创业板必须 100 股整数倍。</p>
 */
class LotRuleTest {

    @Test
    void starMarketNeedsTwoHundredSharesMinimum() {
        assertTrue(LotRule.isStarMarket("688981"));
        assertTrue(LotRule.isStarMarket("689009"));
        assertEquals(200, LotRule.minLot("688981"));
        assertEquals(100, LotRule.minLot("600519"));
        assertEquals(100, LotRule.minLot("300750"));

        assertFalse(LotRule.isValidQuantity("688981", 100));
        assertFalse(LotRule.isValidQuantity("688981", 199));
        assertTrue(LotRule.isValidQuantity("688981", 200));
        // 科创板 200 股以上按 1 股递增，201 股是合法的
        assertTrue(LotRule.isValidQuantity("688981", 201));
        assertTrue(LotRule.isValidQuantity("688981", 250));
    }

    @Test
    void chinextAndMainBoardStayWholeLots() {
        assertTrue(LotRule.isValidQuantity("600519", 100));
        assertTrue(LotRule.isValidQuantity("600519", 300));
        assertFalse(LotRule.isValidQuantity("600519", 150));
        assertFalse(LotRule.isValidQuantity("600519", 99));
        // 创业板仍是 100 股整数倍（与科创板不同）
        assertTrue(LotRule.isValidQuantity("300750", 100));
        assertFalse(LotRule.isValidQuantity("300750", 150));
    }

    @Test
    void bseAllowsOddSharesFromOneHundred() {
        assertTrue(LotRule.allowsOddShare("430047"));
        assertEquals(100, LotRule.minLot("430047"));
        assertFalse(LotRule.isValidQuantity("430047", 99));
        assertTrue(LotRule.isValidQuantity("430047", 100));
        assertTrue(LotRule.isValidQuantity("430047", 137));
    }

    @Test
    void roundDownRespectsBoardRule() {
        // 3000 元预算买 12.5 元的科创板票只能买 240 股 → 240 股合法，原样保留
        assertEquals(240, LotRule.roundDown("688981", 240));
        // 科创板算出 150 股不够最小申报数量 → 返回 0，调用方应跳过
        assertEquals(0, LotRule.roundDown("688981", 150));
        // 主板 150 股向下取整成 100 股
        assertEquals(100, LotRule.roundDown("600519", 150));
        assertEquals(0, LotRule.roundDown("600519", 99));
        assertEquals(0, LotRule.roundDown("600519", 0));
    }

    @Test
    void describeExplainsBoardRule() {
        assertTrue(LotRule.describe("688981").contains("200 股"));
        assertTrue(LotRule.describe("430047").contains("北交所"));
        assertTrue(LotRule.describe("600519").contains("100 股的整数倍"));
    }
}
