package com.dking.product.exception;

/**
 * 库存操作被拒绝（库存不足、状态非法等）。
 *
 * <p>内部接口把它映射成 HTTP 409 + {@code {"code":"INSUFFICIENT_STOCK","message":"..."}}，
 * 调用方用 {@code code} 做分支，用状态码判断"该不该重试"。</p>
 */
public class StockOpException extends RuntimeException {

    private final StockErrorCode code;

    public StockOpException(StockErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public StockErrorCode code() {
        return code;
    }
}
