package com.dking.product.dto;

/**
 * 库存操作的单行明细。
 *
 * @param skuId    SKU id
 * @param quantity 数量，必须为正
 */
public record StockItem(Long skuId, Integer quantity) {
}
