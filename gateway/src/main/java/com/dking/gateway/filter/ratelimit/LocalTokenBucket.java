package com.dking.gateway.filter.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地（单机）令牌桶。
 *
 * <p>两个用途：① Redis 开关关闭时的唯一实现；② Redis 故障时的降级实现。
 * 降级后配额是「每实例各算各的」，会放大 N 倍——这是降级，不是常态。</p>
 */
@Component
public class LocalTokenBucket {

    private static final Logger log = LoggerFactory.getLogger(LocalTokenBucket.class);

    private static final int CLEANUP_THRESHOLD = 50_000;
    private static final long IDLE_MILLIS = 10 * 60 * 1000L;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong requestCounter = new AtomicLong();

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;
        private long lastAccessMillis;

        private Bucket(double tokens, long nowNanos) {
            this.tokens = tokens;
            this.lastRefillNanos = nowNanos;
            this.lastAccessMillis = System.currentTimeMillis();
        }
    }

    public boolean tryAcquire(String key, int limitForPeriod, int capacity, int periodSeconds) {
        if (requestCounter.incrementAndGet() % 1000 == 0) {
            cleanupIfNeeded();
        }

        long nowNanos = System.nanoTime();
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, nowNanos));

        synchronized (bucket) {
            double refillPerSecond = (double) limitForPeriod / Math.max(1, periodSeconds);
            double elapsedSeconds = (nowNanos - bucket.lastRefillNanos) / 1_000_000_000.0;

            bucket.tokens = Math.min(capacity, bucket.tokens + elapsedSeconds * refillPerSecond);
            bucket.lastRefillNanos = nowNanos;
            bucket.lastAccessMillis = System.currentTimeMillis();

            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    public int bucketCount() {
        return buckets.size();
    }

    public void clear() {
        buckets.clear();
    }

    /** 桶数量增长到阈值时清理长时间不活跃的 key，防止内存泄漏 */
    private void cleanupIfNeeded() {
        if (buckets.size() < CLEANUP_THRESHOLD) {
            return;
        }
        long deadline = System.currentTimeMillis() - IDLE_MILLIS;
        Iterator<Map.Entry<String, Bucket>> iterator = buckets.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().lastAccessMillis < deadline) {
                iterator.remove();
            }
        }
        log.info("本地限流桶清理完成, 剩余={}", buckets.size());
    }
}
