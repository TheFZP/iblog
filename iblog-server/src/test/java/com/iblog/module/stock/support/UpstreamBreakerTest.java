package com.iblog.module.stock.support;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上游熔断器的纯逻辑测试（注入假时钟，不 sleep）。
 */
class UpstreamBreakerTest {

    @Test
    void opensOnlyAfterThresholdConsecutiveFailures() {
        UpstreamBreaker breaker = new UpstreamBreaker(3, 1000, () -> 0L);
        assertFalse(breaker.recordFailure());
        assertFalse(breaker.recordFailure());
        assertFalse(breaker.isOpen());
        // 第 3 次失败触发熔断，并返回 true 让调用方打日志
        assertTrue(breaker.recordFailure());
        assertTrue(breaker.isOpen());
    }

    @Test
    void successResetsTheStreak() {
        UpstreamBreaker breaker = new UpstreamBreaker(3, 1000, () -> 0L);
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        assertEquals(0, breaker.failureStreak());
        // 隔断被打断后，要重新连续失败 3 次才会熔断
        assertFalse(breaker.recordFailure());
        assertFalse(breaker.recordFailure());
        assertTrue(breaker.recordFailure());
        assertTrue(breaker.isOpen());
    }

    @Test
    void closesAutomaticallyAfterCooldown() {
        AtomicLong now = new AtomicLong(0L);
        UpstreamBreaker breaker = new UpstreamBreaker(2, 60_000, now::get);
        breaker.recordFailure();
        breaker.recordFailure();
        assertTrue(breaker.isOpen());

        now.set(59_999L);
        assertTrue(breaker.isOpen());
        // 冷却结束自动闭合，放行下一个请求当探测
        now.set(60_000L);
        assertFalse(breaker.isOpen());
        assertFalse(breaker.isOpen());
    }

    @Test
    void failureAfterCooldownReopensImmediately() {
        AtomicLong now = new AtomicLong(0L);
        UpstreamBreaker breaker = new UpstreamBreaker(2, 1000, now::get);
        breaker.recordFailure();
        breaker.recordFailure();
        now.set(1000L);
        assertFalse(breaker.isOpen());
        // 闭合后计数已清零，需要重新累积
        assertFalse(breaker.recordFailure());
        assertFalse(breaker.isOpen());
        assertTrue(breaker.recordFailure());
        assertTrue(breaker.isOpen());
    }

    @Test
    void degenerateArgumentsAreClamped() {
        // threshold<=0 / cooldown<=0 都应被夹到 1，不能出现「一次失败都熔断不了」
        AtomicLong now = new AtomicLong(0L);
        UpstreamBreaker breaker = new UpstreamBreaker(0, 0, now::get);
        assertTrue(breaker.recordFailure());
        assertTrue(breaker.isOpen());
        // cooldown 被夹到 1ms：推进 1ms 后立刻闭合
        now.set(1L);
        assertFalse(breaker.isOpen());
    }
}
