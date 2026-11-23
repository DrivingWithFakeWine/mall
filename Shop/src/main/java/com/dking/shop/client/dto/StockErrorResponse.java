package com.dking.shop.client.dto;

/**
 * 库存操作的错误响应体（HTTP 409）。
 *
 * @param code 机器可读错误码，例如 INSUFFICIENT_STOCK、NOT_RESERVED
 */
public record StockErrorResponse(String code, String message) {
}
