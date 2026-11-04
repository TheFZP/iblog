package com.iblog.module.paper.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsScoreRuleTest {

    @Test
    void positiveScaledByConfidence() {
        // 官方披露确信利好：45 原始分 × 0.9 置信 × 15 上限 = 6.075 ≈ +6
        assertEquals(6, NewsScoreRule.bonus(15, 10, 45, 0.9));
        // 满分利好打满加分上限
        assertEquals(15, NewsScoreRule.bonus(15, 10, 100, 1.0));
    }

    @Test
    void negativeScaledByConfidence() {
        // 利空 -60 × 0.8 × 10 上限 ≈ -5
        assertEquals(-5, NewsScoreRule.bonus(15, 10, -60, 0.8));
        // 满分利空打满扣分上限
        assertEquals(-10, NewsScoreRule.bonus(15, 10, -100, 1.0));
    }

    @Test
    void lowConfidenceDampens() {
        // 传闻利好：90 原始分 × 0.3 置信 × 15 ≈ +4，而不是 +14
        assertEquals(4, NewsScoreRule.bonus(15, 10, 90, 0.3));
    }

    @Test
    void neutralAndEdge() {
        assertEquals(0, NewsScoreRule.bonus(15, 10, 0, 0.8));
        assertEquals(0, NewsScoreRule.bonus(15, 10, 20, 0.0));
        // 100 原始分但置信度只有 0.1 → 1.5 → round 2，说明低置信不会给高分
        assertEquals(2, NewsScoreRule.bonus(15, 10, 100, 0.1));
    }

    @Test
    void maxAndPenaltyCapped() {
        // 上限参数本身就是上限，原始分再高也不会超
        assertEquals(15, NewsScoreRule.bonus(15, 10, 200, 1.0));
        assertEquals(-10, NewsScoreRule.bonus(15, 10, -200, 1.0));
    }

    @Test
    void pricedInDiscountHalvesPositiveOnly() {
        // 涨幅到 7% 或昨日连板 >= 2，利好打五折
        assertEquals(5, NewsScoreRule.pricedInDiscount(10, 7.2, 0));
        assertEquals(5, NewsScoreRule.pricedInDiscount(10, 1.0, 2));
        // 低位/首板不打折
        assertEquals(10, NewsScoreRule.pricedInDiscount(10, 3.0, 1));
        // 利空不因高位打折（高位利空反而更危险）
        assertEquals(-8, NewsScoreRule.pricedInDiscount(-8, 9.5, 3));
        // 0 分保持 0
        assertEquals(0, NewsScoreRule.pricedInDiscount(0, 9.5, 3));
    }

    @Test
    void hardFilterOnlyWhenConfidenceEnough() {
        // 置信足够且分低于阈值 → 剔除
        assertTrue(NewsScoreRule.filtered(-60, 0.8, 0.6, -50));
        // 置信不足不剔除
        assertFalse(NewsScoreRule.filtered(-60, 0.3, 0.6, -50));
        // 分未低于阈值不剔除
        assertFalse(NewsScoreRule.filtered(-40, 0.8, 0.6, -50));
    }
}