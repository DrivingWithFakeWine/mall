package com.dking.shop.dto;

import java.math.BigDecimal;

/**
 * 秒杀建单命令：营销域 → 交易域的建单契约。
 *
 * <p>字段全部是扁平的原语/字符串，可以直接作为 MQ 消息体。
 * 目前营销和交易还在同一个进程里，方法直接调用；拆成两个服务后，
 * 这个对象就是 {@code seckill.order.create} 消息的 payload，调用方不用改。</p>
 *
 * <p>为什么把商品名称/规格/图片也带进来：订单明细是**下单时的价格与商品快照**，
 * 建单方不该再回查商品服务——回查可能拿到改价后的新值，快照就失真了。</p>
 *
 * @param userId      下单用户
 * @param activityId  秒杀活动 id
 * @param stockBucket 命中的库存桶号（取消/超时回补要用）
 * @param orderToken  幂等令牌，和 orders.order_token 唯一索引配合
 * @param addressId   收货地址 id
 * @param spuId       商品 SPU
 * @param skuId       商品 SKU
 * @param productName 商品名称（快照）
 * @param specDesc    规格描述（快照）
 * @param image       图片（快照）
 * @param price       秒杀价
 * @param remark      买家备注
 */
public record SeckillOrderCommand(
        Long userId,
        Long activityId,
        Integer stockBucket,
        String orderToken,
        Long addressId,
        Long spuId,
        Long skuId,
        String productName,
        String specDesc,
        String image,
        BigDecimal price,
        String remark
) {
}
