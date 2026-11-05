package com.dking.shop.service.support;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀活动详情的缓存快照：只存元数据，不含库存、phase、serverTime。
 */
public record SeckillActivitySnapshot(
        Long id,
        Long spuId,
        Long skuId,
        String name,
        BigDecimal seckillPrice,
        Integer totalStock,
        Integer perUserLimit,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Integer status,
        String productName,
        String image,
        String specDesc,
        BigDecimal originalPrice
) {
}
