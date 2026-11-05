package com.dking.shop.service;

import com.dking.shop.entity.SeckillActivity;

public interface SeckillStockService {

    enum ReserveResult {
        RESERVED,
        SOLD_OUT,
        NOT_STARTED,
        ENDED,
        DUPLICATE
    }

    /** Redis 不可用时抛 ServiceUnavailableException */
    ReserveResult reserve(SeckillActivity activity, Long userId);

    /** 下单失败归还预扣；markUser=true 时保留（重新写入）用户占位 */
    void release(Long activityId, Long userId, boolean markUser);

    /** 取消/超时回补库存，保留用户占位 */
    void restoreStock(Long activityId, int quantity);

    /** 只补缺失 key，不覆盖已有库存 */
    void initialize(SeckillActivity activity);

    /** Redis 中剩余库存；key 不存在返回 null */
    Integer currentStock(Long activityId);
}
