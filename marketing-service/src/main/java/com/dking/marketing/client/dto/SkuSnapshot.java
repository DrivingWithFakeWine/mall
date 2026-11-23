package com.dking.marketing.client.dto;

import java.math.BigDecimal;

/**
 * SKU 快照：product-service 内部接口 {@code /internal/product/skus/batch} 的响应元素。
 *
 * <p><b>和商品服务里的同名类保持字段一致</b>，改字段要两边同步。
 * 之所以不共享同一个 jar：当前是三个独立 Maven 工程，没有父 pom；
 * 为了几个 DTO 去建 parent + api 模块，反而增加构建复杂度。
 * 等接口稳定、或出现第二处复用，再抽 {@code product-api}。</p>
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
