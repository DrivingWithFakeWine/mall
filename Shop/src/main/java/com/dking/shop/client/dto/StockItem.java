package com.dking.shop.client.dto;

import java.util.List;

/** 库存操作明细，与 product-service 约定一致 */
public record StockItem(Long skuId, Integer quantity) {
}
