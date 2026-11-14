package com.iblog.module.tool.weather;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 key（这里是 IP）的滑动窗口限流。
 *
 * <p>天气 Agent 是公开接口且会花模型的钱，没有限流等于把 Key 借给所有人。
 * 单机内存版足够：本站只有一个后端实例，重启丢失计数也可以接受。</p>
 */
public final class SimpleRateLimiter {

    private static final int MAX_KEYS = 10_000;

    private final Map<String, Deque<Long>> buckets = new ConcurrentHashMap<>();

    /** @return true 表示本次请求允许通过 */
    public boolean allow(String key, int maxRequests, long windowMs) {
        long now = System.currentTimeMillis();
        if (buckets.size() > MAX_KEYS) {
            buckets.clear();
        }
        Deque<Long> hits = buckets.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (hits) {
            long floor = now - windowMs;
            while (!hits.isEmpty() && hits.peekFirst() < floor) {
                hits.pollFirst();
            }
            if (hits.size() >= maxRequests) {
                return false;
            }
            hits.addLast(now);
            return true;
        }
    }
}
