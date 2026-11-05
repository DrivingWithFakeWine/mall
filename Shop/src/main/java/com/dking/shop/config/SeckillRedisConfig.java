package com.dking.shop.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 秒杀闸门的 Lua 脚本。
 * DB 条件更新仍是最终真相，脚本只负责在进入事务前做原子准入。
 */
@Configuration
public class SeckillRedisConfig {
    // 在 Redis 里原子地检查库存、扣减、记录用户，把这份库存“预留”给这个用户
    private static final String RESERVE_SCRIPT = """
            local stock = redis.call('GET', KEYS[1])
            if not stock then return -1 end
            local now = tonumber(ARGV[2])
            if now < tonumber(ARGV[3]) then return -2 end
            if now >= tonumber(ARGV[4]) then return -3 end
            if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -4 end
            if tonumber(stock) <= 0 then return 0 end
            redis.call('SADD', KEYS[2], ARGV[1])
            redis.call('EXPIRE', KEYS[2], tonumber(ARGV[5]))
            redis.call('DECR', KEYS[1])
            return 1
            """;
    // 释放 “预扣” 的库存
    private static final String RELEASE_SCRIPT = """
            if redis.call('EXISTS', KEYS[1]) == 1 then
              redis.call('INCR', KEYS[1])
            end
            if ARGV[2] == 'mark' then
              redis.call('SADD', KEYS[2], ARGV[1])
            else
              redis.call('SREM', KEYS[2], ARGV[1])
            end
            return 1
            """;

    private static final String RESTORE_SCRIPT = """
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            return redis.call('INCRBY', KEYS[1], tonumber(ARGV[1]))
            """;

    @Bean
    public DefaultRedisScript<Long> seckillReserveScript() {
        return new DefaultRedisScript<>(RESERVE_SCRIPT, Long.class);
    }

    @Bean
    public DefaultRedisScript<Long> seckillReleaseScript() {
        return new DefaultRedisScript<>(RELEASE_SCRIPT, Long.class);
    }

    @Bean
    public DefaultRedisScript<Long> seckillRestoreScript() {
        return new DefaultRedisScript<>(RESTORE_SCRIPT, Long.class);
    }
}
