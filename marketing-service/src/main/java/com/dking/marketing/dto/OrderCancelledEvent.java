package com.dking.marketing.dto;

/**
 * 秒杀订单取消事件：交易 → 营销。
 *
 * <p>普通订单取消只回补商品库存；秒杀订单还要回补活动桶和 Redis 活动库存，
 * 这两样归营销域所有，所以用事件通知，不做同步调用。</p>
 *
 * @param orderNo     订单号
 * @param activityId  活动 id
 * @param stockBucket 下单时命中的桶号
 * @param quantity    回补数量（秒杀固定 1）
 */
public record OrderCancelledEvent(
        String orderNo,
        Long activityId,
        Integer stockBucket,
        int quantity
) {
}
