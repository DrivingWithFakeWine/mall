package com.dking.marketing.dto;

/**
 * 秒杀建单消息：营销发布，交易服务消费。
 *
 * <p>消息里直接带完整的 {@link SeckillOrderCommand}（含价格与商品快照），
 * 消费方不需要回查活动表或商品服务——少一次跨服务调用，也避免「查到的是改价后的值」。</p>
 *
 * @param token     幂等令牌，同时作为订单的 order_token
 * @param command   建单命令
 * @param createdAt 生产时间（毫秒），排查积压用
 */
public record SeckillOrderMessage(
        String token,
        SeckillOrderCommand command,
        long createdAt
) {
}
