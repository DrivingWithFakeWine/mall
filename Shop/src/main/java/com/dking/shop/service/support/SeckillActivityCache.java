package com.dking.shop.service.support;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 秒杀活动详情缓存。只缓存元数据，库存/phase/serverTime 由调用方实时计算。
 * Redis 异常时 fail-open：返回 miss，详情接口继续查 DB。
 */
@Slf4j
@Component
public class SeckillActivityCache {

    private static final String MISSING = "__NULL__";

    @Resource
    private StringRedisTemplate redis;

    @Resource
    private JsonMapper jsonMapper;

    @Value("${shop.seckill.redis.prefix:doro:seckill}")
    private String keyPrefix;

    @Value("${shop.seckill.redis.activity-cache-enabled:true}")
    private boolean enabled;

    @Value("${shop.seckill.redis.activity-cache-ttl-seconds:30}")
    private long ttlSeconds;

    @Value("${shop.seckill.redis.activity-cache-jitter-seconds:10}")
    private long jitterSeconds;

    @Value("${shop.seckill.redis.activity-cache-missing-ttl-seconds:10}")
    private long missingTtlSeconds;

    public Lookup lookup(Long activityId) {
        if (!enabled) {
            return Lookup.miss();
        }
        try {
            String value = redis.opsForValue().get(key(activityId));
            if (value == null) {
                return Lookup.miss();
            }
            // 防止缓存穿透，缓存"MISSING"标记
            if (MISSING.equals(value)) {
                return Lookup.missingEntry();
            }
            return Lookup.hit(jsonMapper.readValue(value, SeckillActivitySnapshot.class));
        } catch (RuntimeException e) {
            log.warn("读取秒杀活动缓存失败, activityId={}", activityId, e);
            return Lookup.miss();
        }
    }

    public void put(SeckillActivitySnapshot snapshot) {
        if (!enabled) {
            return;
        }
        try {
            // 随机抖动,基础过期时间上，额外叠加0-jitterSeconds之间的随机秒数
            long jitter = jitterSeconds > 0
                    ? ThreadLocalRandom.current().nextLong(jitterSeconds + 1)
                    : 0;
            redis.opsForValue().set(
                    key(snapshot.id()),
                    jsonMapper.writeValueAsString(snapshot),
                    // 过期时间随机，防止缓存雪崩
                    Duration.ofSeconds(ttlSeconds + jitter));
        } catch (RuntimeException e) {
            log.warn("写入秒杀活动缓存失败, activityId={}", snapshot.id(), e);
        }
    }

    public void putMissing(Long activityId) {
        if (!enabled) {
            return;
        }
        try {
            redis.opsForValue().set(key(activityId), MISSING,
                    Duration.ofSeconds(missingTtlSeconds));
        } catch (RuntimeException e) {
            log.warn("写入秒杀活动空缓存失败, activityId={}", activityId, e);
        }
    }

    public void evict(Long activityId) {
        try {
            redis.delete(key(activityId));
        } catch (RuntimeException e) {
            log.warn("删除秒杀活动缓存失败, activityId={}", activityId, e);
        }
    }

    private String key(Long activityId) {
        String prefix = keyPrefix.endsWith(":")
                ? keyPrefix.substring(0, keyPrefix.length() - 1)
                : keyPrefix;
        return prefix + ":activity:" + activityId;
    }

    public record Lookup(SeckillActivitySnapshot snapshot, boolean missing) {

        static Lookup miss() {
            return new Lookup(null, false);
        }

        static Lookup missingEntry() {
            return new Lookup(null, true);
        }

        static Lookup hit(SeckillActivitySnapshot snapshot) {
            return new Lookup(snapshot, false);
        }

        public boolean hit() {
            return snapshot != null;
        }
    }
}
