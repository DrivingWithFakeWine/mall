package com.dking.shop.exception;

/** 秒杀库存已被抢完；与普通业务异常区分，避免外层误回滚 Redis 预扣。 */
public class SeckillSoldOutException extends BusinessException {

    public SeckillSoldOutException() {
        super("活动商品已售罄");
    }
}
