package com.dking.shop.service.impl;

import com.dking.shop.entity.SeckillActivity;
import com.dking.shop.exception.ServiceUnavailableException;
import com.dking.shop.mapper.SeckillActivityMapper;
import com.dking.shop.service.SeckillStockService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Service
public class SeckillStockServiceImpl implements SeckillStockService {

    private static final long ONE_DAY_SECONDS = 24 * 60 * 60;

    @Resource
    private StringRedisTemplate redis;

    @Resource(name = "seckillReserveScript")
    private DefaultRedisScript<Long> reserveScript;

    @Resource(name = "seckillReleaseScript")
    private DefaultRedisScript<Long> releaseScript;

    @Resource(name = "seckillRestoreScript")
    private DefaultRedisScript<Long> restoreScript;

    @Resource
    private SeckillActivityMapper seckillActivityMapper;

    @Value("${shop.seckill.redis.prefix:doro:seckill}")
    private String keyPrefix;

    @Override
    public ReserveResult reserve(SeckillActivity activity, Long userId) {
        long now = System.currentTimeMillis();
        long start = toEpochMilli(activity.getStartTime());
        long end = toEpochMilli(activity.getEndTime());
        long ttl = ttlSeconds(activity.getEndTime());

        Long result = executeReserve(activity.getId(), userId, now, start, end, ttl);
        if (result != null && result == -1L) {
            // Redis 重启或首次部署：从 DB 重新初始化后重试一次
            initialize(activity);
            result = executeReserve(activity.getId(), userId, now, start, end, ttl);
        }
        if (result == null) {
            throw unavailable();
        }
        return switch (result.intValue()) {
            case 1 -> ReserveResult.RESERVED;
            case 0 -> ReserveResult.SOLD_OUT;
            case -2 -> ReserveResult.NOT_STARTED;
            case -3 -> ReserveResult.ENDED;
            case -4 -> ReserveResult.DUPLICATE;
            default -> {
                log.warn("秒杀预扣返回未知结果, activityId={}, result={}", activity.getId(), result);
                throw unavailable();
            }
        };
    }

    @Override
    public void release(Long activityId, Long userId, boolean markUser) {
        try {
            redis.execute(releaseScript,
                    List.of(stockKey(activityId), usersKey(activityId)),
                    String.valueOf(userId),
                    markUser ? "mark" : "remove");
        } catch (DataAccessException e) {
            throw unavailable();
        }
    }

    @Override
    public void restoreStock(Long activityId, int quantity) {
        try {
            redis.execute(restoreScript, List.of(stockKey(activityId)), String.valueOf(quantity));
        } catch (DataAccessException e) {
            // 取消/超时以 DB 为准，Redis 少加由对账任务发现
            log.error("Redis 活动库存回补失败, activityId={}, quantity={}", activityId, quantity, e);
        }
    }

    @Override
    public void initialize(SeckillActivity activity) {
        // 重新读库，避免用调用方手里的旧快照覆盖新库存
        SeckillActivity fresh = seckillActivityMapper.selectById(activity.getId());
        if (fresh == null) {
            return;
        }
        try {
            redis.opsForValue().setIfAbsent(
                    stockKey(fresh.getId()),
                    String.valueOf(fresh.getAvailableStock()),
                    Duration.ofSeconds(ttlSeconds(fresh.getEndTime())));
        } catch (DataAccessException e) {
            throw unavailable();
        }
    }

    @Override
    public Integer currentStock(Long activityId) {
        String value = redis.opsForValue().get(stockKey(activityId));
        return value == null ? null : Integer.valueOf(value);
    }

    private Long executeReserve(Long activityId, Long userId,
                                long now, long start, long end, long ttlSeconds) {
        try {
            return redis.execute(reserveScript,
                    List.of(stockKey(activityId), usersKey(activityId)),
                    String.valueOf(userId),
                    String.valueOf(now),
                    String.valueOf(start),
                    String.valueOf(end),
                    String.valueOf(ttlSeconds));
        } catch (DataAccessException e) {
            throw unavailable();
        }
    }

    private String stockKey(Long activityId) {
        return normalizedPrefix() + ":seckill:stock:" + activityId;
    }

    private String usersKey(Long activityId) {
        return normalizedPrefix() + ":seckill:users:" + activityId;
    }

    private String normalizedPrefix() {
        return keyPrefix.endsWith(":") ? keyPrefix.substring(0, keyPrefix.length() - 1) : keyPrefix;
    }

    private long ttlSeconds(LocalDateTime endTime) {
        long untilEnd = Duration.between(Instant.now(),
                endTime.atZone(ZoneId.systemDefault()).toInstant()).getSeconds();
        return Math.max(ONE_DAY_SECONDS, untilEnd + ONE_DAY_SECONDS);
    }

    private long toEpochMilli(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private ServiceUnavailableException unavailable() {
        return new ServiceUnavailableException("秒杀服务暂不可用，请稍后重试");
    }
}
