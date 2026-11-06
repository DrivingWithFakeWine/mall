package com.dking.shop.service.impl;

import com.dking.shop.dto.SeckillOrderMessage;
import com.dking.shop.service.SeckillOrderPublisher;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "shop.seckill.async", name = "enabled", havingValue = "true")
public class RabbitSeckillOrderPublisher implements SeckillOrderPublisher {

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private JsonMapper jsonMapper;

    @Value("${shop.seckill.async.exchange:doroshop.seckill}")
    private String exchange;

    @Value("${shop.seckill.async.routing-key:order}")
    private String routingKey;

    @Override
    public void publish(SeckillOrderMessage message) {
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey,
                    jsonMapper.writeValueAsString(message));
        } catch (AmqpException e) {
            // 发送失败必须让调用方回滚 Redis 预扣，否则库存与占位泄漏
            throw new IllegalStateException("秒杀消息发送失败, token=" + message.token(), e);
        }
    }
}
