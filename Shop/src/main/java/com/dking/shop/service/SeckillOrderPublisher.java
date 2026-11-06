package com.dking.shop.service;

import com.dking.shop.dto.SeckillOrderMessage;

/** 秒杀下单消息发布；实现由 MQ 提供，异步开关关闭时不存在该 Bean。 */
public interface SeckillOrderPublisher {

    void publish(SeckillOrderMessage message);
}
