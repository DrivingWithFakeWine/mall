package com.dking.shop.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 秒杀下单队列：主队列绑定死信交换机，消费重试耗尽后进入 DLQ。
 */
@Configuration
@ConditionalOnProperty(prefix = "shop.seckill.async", name = "enabled", havingValue = "true")
public class SeckillAsyncConfig {

    @Value("${shop.seckill.async.exchange:doroshop.seckill}")
    private String exchangeName;

    @Value("${shop.seckill.async.queue:doroshop.seckill.order}")
    private String queueName;

    @Value("${shop.seckill.async.routing-key:order}")
    private String routingKey;

    @Value("${shop.seckill.async.dead-letter-exchange:doroshop.seckill.dlx}")
    private String deadLetterExchangeName;

    @Value("${shop.seckill.async.dead-letter-queue:doroshop.seckill.order.dlq}")
    private String deadLetterQueueName;

    @Bean
    public DirectExchange seckillExchange() {
        return new DirectExchange(exchangeName, true, false);
    }

    @Bean
    public DirectExchange seckillDeadLetterExchange() {
        return new DirectExchange(deadLetterExchangeName, true, false);
    }

    @Bean
    public Queue seckillOrderQueue() {
        return QueueBuilder.durable(queueName)
                .deadLetterExchange(deadLetterExchangeName)
                .deadLetterRoutingKey(deadLetterQueueName)      // 保持与 Bing时一致
                .build();
    }

    @Bean
    public Queue seckillDeadLetterQueue() {
        return QueueBuilder.durable(deadLetterQueueName).build();
    }

    @Bean
    public Binding seckillOrderBinding(@Qualifier("seckillOrderQueue") Queue queue,
                                       @Qualifier("seckillExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(routingKey);
    }

    @Bean
    public Binding seckillDeadLetterBinding(@Qualifier("seckillDeadLetterQueue") Queue queue,
                                            @Qualifier("seckillDeadLetterExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(deadLetterQueueName);
    }
}
