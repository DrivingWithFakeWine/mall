package com.dking.shop.dto;

/**
 * 秒杀异步下单消息。热路径只带 id，地址与商品校验由消费者完成。
 */
public record SeckillOrderMessage(
        String token,
        Long userId,
        Long activityId,
        Long addressId,
        String remark,
        long enqueuedAt) {
}
