package com.iblog.module.stock.support;

import java.util.function.LongSupplier;

/**
 * 上游熔断器：连续失败到阈值后，在一段时间内直接判定「上游不可用」，不再发请求。
 *
 * <p><b>为什么需要它</b>：东方财富 push2 系列对同一出口的高频请求会直接断连
 * （{@code HTTP/1.1 header parser received no bytes}）。单看一次请求，重试几次就能成功，
 * 所以 {@code EastmoneyService} 一直保留 5 次重试；但选股一轮要问几十上百只票，
 * 一旦被限流，<b>每只票</b>都要白等 5 次重试 × 2 个域名，整轮从几十秒涨到几分钟，
 * 还会进一步加重限流，形成雪崩。熔断的意义是：认识到「现在整个上游都不可用」之后
 * 立刻放弃，把这些时间省下来给其它上游（腾讯行情/东财数据中心都不受影响）。</p>
 *
 * <p><b>为什么按「单次 HTTP 尝试」而不是「单次业务调用」计数</b>：一次业务调用内部就有
 * 5 次重试，按调用计数要连错 6 只票（约 36 秒）才熔断，太慢；按尝试计数约 6 秒就能判定。</p>
 *
 * <p><b>冷却结束后的行为</b>：自动闭合，放行下一个请求当探测。探测成功则彻底恢复，
 * 失败则重新累积——不做「半开状态只放一个请求」的复杂实现，因为调用方本来就是串行低频的，
 * 一个请求的代价可以接受。</p>
 *
 * <p>线程安全：{@code synchronized} 足够。调用频率是「每 15 秒一次 tick + 偶发手动刷新」，
 * 争用可以忽略。</p>
 */
public class UpstreamBreaker {

    private final int threshold;
    private final long cooldownMs;
    private final LongSupplier clock;

    private int consecutiveFailures;
    private long openUntil;

    public UpstreamBreaker(int threshold, long cooldownMs) {
        this(threshold, cooldownMs, System::currentTimeMillis);
    }

    /** 供单测注入假时钟，避免 sleep。 */
    UpstreamBreaker(int threshold, long cooldownMs, LongSupplier clock) {
        this.threshold = Math.max(1, threshold);
        this.cooldownMs = Math.max(1L, cooldownMs);
        this.clock = clock;
    }

    /** 当前是否处于熔断期（冷却结束后自动闭合）。 */
    public synchronized boolean isOpen() {
        if (openUntil == 0L) {
            return false;
        }
        if (clock.getAsLong() >= openUntil) {
            openUntil = 0L;
            return false;
        }
        return true;
    }

    /** 一次上游请求成功：清空失败计数并立即闭合。 */
    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        openUntil = 0L;
    }

    /**
     * 一次上游请求失败。
     *
     * @return true 表示这次失败刚好触发了熔断（调用方可以据此打一条 WARN）
     */
    public synchronized boolean recordFailure() {
        consecutiveFailures++;
        if (consecutiveFailures < threshold) {
            return false;
        }
        consecutiveFailures = 0;
        openUntil = clock.getAsLong() + cooldownMs;
        return true;
    }

    /** 当前连续失败次数（熔断期内恒为 0，因为触发时已清零）。 */
    public synchronized int failureStreak() {
        return consecutiveFailures;
    }

    /** 熔断到期时间戳（毫秒），0 表示未熔断。 */
    public synchronized long openUntil() {
        return openUntil;
    }
}
