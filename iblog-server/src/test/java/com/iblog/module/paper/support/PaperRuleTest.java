package com.iblog.module.paper.support;

import com.iblog.config.IblogProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交易规则单测：费用、涨跌停。
 */
class PaperRuleTest {

    private FeeCalculator calculator() {
        return new FeeCalculator(new IblogProperties());
    }

    @Test
    void buyChargesCommissionAndTransferFeeOnly() {
        FeeCalculator.Fee fee = calculator().of(new BigDecimal("10000"), false);
        assertEquals(new BigDecimal("5.00"), fee.commission());
        assertEquals(new BigDecimal("0.00"), fee.stampTax());
        assertEquals(new BigDecimal("0.10"), fee.transferFee());
        assertEquals(new BigDecimal("5.10"), fee.total());
        assertEquals(new BigDecimal("10005.10"), fee.buyCost(new BigDecimal("10000")));
    }

    @Test
    void sellAlsoChargesStampTax() {
        FeeCalculator.Fee fee = calculator().of(new BigDecimal("10000"), true);
        assertEquals(new BigDecimal("5.00"), fee.stampTax());
        assertEquals(new BigDecimal("10.10"), fee.total());
        assertEquals(new BigDecimal("9989.90"), fee.sellIncome(new BigDecimal("10000")));
    }

    @Test
    void limitRateDependsOnBoard() {
        assertEquals(new BigDecimal("0.10"), PriceLimit.rate("600000", "浦发银行"));
        assertEquals(new BigDecimal("0.20"), PriceLimit.rate("300750", "宁德时代"));
        assertEquals(new BigDecimal("0.20"), PriceLimit.rate("688981", "中芯国际"));
        assertEquals(new BigDecimal("0.30"), PriceLimit.rate("430047", "诺思兰德"));
        assertEquals(new BigDecimal("0.05"), PriceLimit.rate("600000", "ST 某某"));
    }

    @Test
    void limitPricesRoundToCent() {
        assertEquals(new BigDecimal("11.00"), PriceLimit.limitUp(new BigDecimal("10"), "600000", "浦发银行"));
        assertEquals(new BigDecimal("9.00"), PriceLimit.limitDown(new BigDecimal("10"), "600000", "浦发银行"));
        assertTrue(PriceLimit.atLimitUp(new BigDecimal("11.00"), new BigDecimal("10"), "600000", "浦发银行"));
        assertFalse(PriceLimit.atLimitUp(new BigDecimal("10.99"), new BigDecimal("10"), "600000", "浦发银行"));
        assertEquals(null, PriceLimit.limitUp(BigDecimal.ZERO, "600000", "浦发银行"));
    }
}
