package com.dking.shop.service.support;

import com.dking.shop.vo.OrderSubmitVO;
import com.dking.shop.vo.SeckillOrderSubmitVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 异步下单结果存 Redis，TTL 内可查询；过期后回落到 orders.order_token。
 */
@Slf4j
@Component
public class SeckillOrderResultStore {

    public record StoredResult(
            String userId,
            String status,
            Long orderId,
            String orderNo,
            BigDecimal payAmount,
            LocalDateTime expireTime,
            String message) {
    }

    @Resource
    private StringRedisTemplate redis;

    @Resource
    private JsonMapper jsonMapper;

    @Value("${shop.seckill.redis.prefix:doro:seckill}")
    private String keyPrefix;

    @Value("${shop.seckill.async.result-ttl-seconds:1800}")
    private long ttlSeconds;

    public void markPending(String token, Long userId) {
        save(token, new StoredResult(String.valueOf(userId), SeckillOrderSubmitVO.PENDING,
                null, null, null, null, null));
    }

    public void markSuccess(String token, Long userId, OrderSubmitVO order) {
        save(token, new StoredResult(String.valueOf(userId), SeckillOrderSubmitVO.SUCCESS,
                order.getOrderId(), order.getOrderNo(), order.getPayAmount(),
                order.getExpireTime(), null));
    }

    public void markFailed(String token, Long userId, String message) {
        save(token, new StoredResult(String.valueOf(userId), SeckillOrderSubmitVO.FAILED,
                null, null, null, null, message));
    }

    public StoredResult get(String token) {
        try {
            String value = redis.opsForValue().get(key(token));
            return value == null ? null : jsonMapper.readValue(value, StoredResult.class);
        } catch (RuntimeException e) {
            log.warn("读取秒杀下单结果失败, token={}", token, e);
            return null;
        }
    }

    private void save(String token, StoredResult result) {
        try {
            redis.opsForValue().set(key(token), jsonMapper.writeValueAsString(result),
                    Duration.ofSeconds(ttlSeconds));
        } catch (RuntimeException e) {
            log.error("写入秒杀下单结果失败, token={}, status={}", token, result.status(), e);
        }
    }

    private String key(String token) {
        String prefix = keyPrefix.endsWith(":") ? keyPrefix.substring(0, keyPrefix.length() - 1) : keyPrefix;
        return prefix + ":result:" + token;
    }
}
