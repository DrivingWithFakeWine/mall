package com.dking.shop.client.dto;

/** 库存操作结果，applied=本次生效条数，skipped=幂等跳过条数 */
public record StockOpResult(String orderNo, String op, int applied, int skipped, String message) {
}
