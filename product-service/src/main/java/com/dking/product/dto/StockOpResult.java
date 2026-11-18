package com.dking.product.dto;

/**
 * 库存操作结果。
 *
 * @param applied 本次真正生效的明细条数
 * @param skipped 因为幂等被跳过的条数（重试时会大于 0）
 */
public record StockOpResult(
        String orderNo,
        String op,
        int applied,
        int skipped,
        String message
) {
    public static StockOpResult ok(String op, String orderNo, int applied, int skipped) {
        return new StockOpResult(orderNo, op, applied, skipped, "ok");
    }
}
