package com.dking.gateway.filter.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis 令牌桶（Lua 原子实现）。
 *
 * <p><b>为什么必须用 Lua：</b>「读令牌 → 算补充 → 判断 → 扣减 → 写回」如果拆成多次 Redis 调用，
 * 并发下会互相覆盖。Lua 在 Redis 里单线程执行，整段是原子的。</p>
 *
 * <p><b>为什么时间取 Redis 的 TIME：</b>网关多实例时各机器时钟可能有偏差，
 * 用客户端时间会让补充量算错（甚至出现负数）。统一用 Redis 服务端时间。</p>
 *
 * <p>数据结构用 Hash（tokens + ts）：一个桶一个 key，配 PEXPIRE 自动回收。</p>
 */
@Component
public class RedisTokenBucket {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucket.class);

    private static final String LUA = """
            local capacity = tonumber(ARGV[1])
            local limit = tonumber(ARGV[2])
            local period = tonumber(ARGV[3])
            local ttl = tonumber(ARGV[4])

            -- 统一用 Redis 服务端时间，避免多实例时钟偏差
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

            local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil or ts == nil then
                tokens = capacity
                ts = now
            end

            local refillPerMs = limit / (period * 1000)
            local elapsed = now - ts
            if elapsed < 0 then elapsed = 0 end
            tokens = math.min(capacity, tokens + elapsed * refillPerMs)

            local allowed = 0
            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            end

            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('PEXPIRE', KEYS[1], ttl)
            return allowed
            """;

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(LUA, Long.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;

    public RedisTokenBucket(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
        log.info("Redis 令牌桶已装配, keyPrefix={}", properties.getRedis().getKeyPrefix());
    }

    /**
     * @return true 放行，false 超限
     * @throws RuntimeException Redis 不可用时抛出，由调用方决定降级策略
     */
    public boolean tryAcquire(String key, int limitForPeriod, int capacity, int periodSeconds) {
        String redisKey = properties.getRedis().getKeyPrefix() + ":" + key;
        // 桶的空闲回收时间：至少两个周期，避免刚建的桶立刻消失
        long ttlMillis = Math.max(1_000L, periodSeconds * 2_000L);

        Long result = redis.execute(SCRIPT, List.of(redisKey),
                String.valueOf(capacity),
                String.valueOf(limitForPeriod),
                String.valueOf(periodSeconds),
                String.valueOf(ttlMillis));

        return result != null && result == 1L;
    }
}
