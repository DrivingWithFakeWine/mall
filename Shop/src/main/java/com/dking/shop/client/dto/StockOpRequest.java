package com.dking.shop.client.dto;

import java.util.List;

/**
 * 库存操作请求。
 *
 * @param orderNo 幂等键：同一订单号 + 同一 SKU + 同一操作只生效一次
 */
public record StockOpRequest(String orderNo, List<StockItem> items) {
}
