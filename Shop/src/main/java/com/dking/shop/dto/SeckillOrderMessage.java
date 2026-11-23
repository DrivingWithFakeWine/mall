package com.dking.shop.dto;

/**
 * 秒杀建单消息：营销 → 交易。
 *
 * <p>带完整 {@link SeckillOrderCommand}（价格与商品快照都在里面），
 * 消费方不需要回查活动或商品服务。</p>
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
