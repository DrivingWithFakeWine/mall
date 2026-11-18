package com.dking.product.exception;

/**
 * 库存操作的机器可读错误码。
 *
 * <p>调用方靠它分支处理，不靠中文 message 做字符串匹配。</p>
 */
public enum StockErrorCode {

    /** 参数不合法：orderNo 为空、items 为空、数量越界等 */
    INVALID_REQUEST,

    /** 可用库存不足，预占失败 */
    INSUFFICIENT_STOCK,

    /** 该订单没有预占记录，不能释放/确认 */
    NOT_RESERVED,

    /** 同一订单同一 SKU 的数量与台账记录不一致 */
    QUANTITY_MISMATCH,

    /** 已释放的预占不能再确认 */
    ALREADY_RELEASED,

    /** 已确认的预占不能再释放 */
    ALREADY_CONFIRMED,

    /** SKU 不存在 */
    SKU_NOT_FOUND
}
