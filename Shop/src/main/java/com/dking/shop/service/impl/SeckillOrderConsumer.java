package com.dking.shop.service.impl;

import com.dking.shop.dto.SeckillOrderMessage;
import com.dking.shop.service.SeckillService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "shop.seckill.async", name = "enabled", havingValue = "true")
public class SeckillOrderConsumer {

    @Resource
    private SeckillService seckillService;

    @Resource
    private JsonMapper jsonMapper;

    @RabbitListener(queues = "${shop.seckill.async.queue:doroshop.seckill.order}")
    public void onMessage(String payload) {
        SeckillOrderMessage message = jsonMapper.readValue(payload, SeckillOrderMessage.class);
        log.info("收到秒杀下单消息, token={}, userId={}, activityId={}",
                message.token(), message.userId(), message.activityId());
        seckillService.processQueuedOrder(message);
    }
}
