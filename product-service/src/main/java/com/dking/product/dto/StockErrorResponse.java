package com.dking.product.dto;

import com.dking.product.exception.StockErrorCode;

/**
 * 库存操作的错误响应。
 *
 * <p>和 C 端统一的 {@code Result{code:int}} 不同：这里 {@code code} 是字符串枚举，
 * 因为调用方需要区分「库存不足」和「数量冲突」这类业务语义，靠中文 message 匹配不可靠。</p>
 *
 * @param code    机器可读错误码，见 {@link StockErrorCode}
 * @param message 给人看的说明，可能变化，**不要用它做判断**
 */
public record StockErrorResponse(String code, String message) {
}
