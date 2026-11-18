package com.dking.product.dto;

import java.math.BigDecimal;

/**
 * SKU 快照：给交易域（购物车 / 订单 / 秒杀）用的跨服务读模型。
 *
 * <p>一次调用把「SKU + 库存 + SPU 摘要」拼好返回，避免调用方发三次请求。
 * 字段刻意包含 spuName / spuMainImage / spuStatus：下单要校验 SPU 是否上架，
 * 商品列表也要展示 SPU 名称和主图。</p>
 *
 * <p>注意：这是**读时快照**，不是订单快照。订单落库时仍要把价格、名称写进
 * order_item，不能依赖这个对象长期有效。</p>
 */
public record SkuSnapshot(
        Long skuId,
        Long spuId,
        String skuCode,
        String specDesc,
        String image,
        BigDecimal price,
        BigDecimal originalPrice,
        Integer skuStatus,
        Integer availableStock,
        Integer lockedStock,
        String spuName,
        String spuMainImage,
        Integer spuStatus
) {
}
