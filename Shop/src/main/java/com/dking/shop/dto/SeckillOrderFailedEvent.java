package com.dking.shop.dto;

/**
 * 秒杀建单失败事件：交易 → 营销。
 *
 * <p>建单失败时，营销扣掉的活动桶和 Redis 预扣都要还回去，否则这部分库存永远卖不出去。
 * 营销收到后：回补桶 + 释放 Redis 预扣（允许用户重试）。</p>
 *
 * @param token      幂等令牌，营销用它定位这次下单
 * @param userId     下单用户
 * @param activityId 活动 id
 * @param stockBucket 已扣减的桶号
 * @param reason     失败原因（写进结果缓存，前端展示）
 */
public record SeckillOrderFailedEvent(
        String token,
        Long userId,
        Long activityId,
        Integer stockBucket,
        String reason
) {
}
