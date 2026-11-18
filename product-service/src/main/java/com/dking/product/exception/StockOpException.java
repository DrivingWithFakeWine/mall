package com.dking.product.exception;

/**
 * 库存操作被拒绝（库存不足、数量冲突等）。
 *
 * <p>内部接口把它映射成 HTTP 409，让调用方用状态码就能区分
 * 「业务拒绝，别重试」和「网络/系统故障，可以重试」。</p>
 */
public class StockOpException extends RuntimeException {

    public StockOpException(String message) {
        super(message);
    }
}
