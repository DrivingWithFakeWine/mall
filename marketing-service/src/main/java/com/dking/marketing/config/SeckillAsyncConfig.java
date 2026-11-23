package com.dking.marketing.config;

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

    @Value("${shop.seckill.feedback.exchange:doroshop.seckill.feedback}")
    private String feedbackExchangeName;

    @Value("${shop.seckill.feedback.failed-queue:doroshop.seckill.feedback.failed}")
    private String failedQueueName;

    @Value("${shop.seckill.feedback.cancelled-queue:doroshop.seckill.feedback.cancelled}")
    private String cancelledQueueName;

    @Value("${shop.seckill.feedback.failed-routing-key:failed}")
    private String failedRoutingKey;

    @Value("${shop.seckill.feedback.cancelled-routing-key:cancelled}")
    private String cancelledRoutingKey;

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

    // ------------------------------------------------------------------
    // 交易 → 营销 的反馈事件（建单失败、订单取消），营销消费后还库存
    // ------------------------------------------------------------------

    @Bean
    public DirectExchange seckillFeedbackExchange() {
        return new DirectExchange(feedbackExchangeName, true, false);
    }

    @Bean
    public Queue seckillOrderFailedQueue() {
        return QueueBuilder.durable(failedQueueName).build();
    }

    @Bean
    public Queue seckillOrderCancelledQueue() {
        return QueueBuilder.durable(cancelledQueueName).build();
    }

    @Bean
    public Binding seckillOrderFailedBinding(@Qualifier("seckillOrderFailedQueue") Queue queue,
                                             @Qualifier("seckillFeedbackExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(failedRoutingKey);
    }

    @Bean
    public Binding seckillOrderCancelledBinding(@Qualifier("seckillOrderCancelledQueue") Queue queue,
                                                @Qualifier("seckillFeedbackExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(cancelledRoutingKey);
    }
}
