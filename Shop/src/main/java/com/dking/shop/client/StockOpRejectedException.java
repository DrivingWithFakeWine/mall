package com.dking.shop.client;

import com.dking.shop.exception.BusinessException;

/**
 * 库存操作被商品服务拒绝（HTTP 409）。
 *
 * <p>继承 {@link BusinessException}，所以会被全局异常处理器转成 {@code Result.code=400}，
 * 前端看到的仍是「库存不足」这类业务提示，契约不变。</p>
 *
 * <p>额外带上机器可读的 {@code code}，让后端代码能区分「库存不足」和「顺序错了」。</p>
 */
public class StockOpRejectedException extends BusinessException {

    private final String code;

    public StockOpRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
