package com.dking.product.dto;

import java.util.List;

/**
 * 库存操作请求。
 *
 * @param orderNo 订单号，**幂等键**：同一个订单号 + 同一个 SKU + 同一种操作只生效一次
 * @param items   明细
 */
public record StockOpRequest(String orderNo, List<StockItem> items) {
}
