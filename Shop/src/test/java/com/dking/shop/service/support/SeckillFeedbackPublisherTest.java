package com.dking.shop.service.support;

import com.dking.shop.dto.OrderCancelledEvent;
import com.dking.shop.dto.SeckillOrderCommand;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 反馈事件发布器：MQ 挂了不能影响调用方。
 *
 * <p>取消订单已经把状态改掉了，这时如果发布器把异常抛出去，
 * 用户会看到「取消失败」，但订单其实已经取消——两种状态打架。
 * 所以发布失败只记日志，漏掉的回补交给对账。</p>
 */
class SeckillFeedbackPublisherTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);

    private SeckillFeedbackPublisher newPublisher() {
        SeckillFeedbackPublisher publisher = new SeckillFeedbackPublisher();
        inject(publisher, "rabbitTemplate", rabbitTemplate);
        inject(publisher, "jsonMapper", JsonMapper.builder().build());
        inject(publisher, "exchange", "doroshop.seckill.feedback");
        inject(publisher, "failedRoutingKey", "failed");
        inject(publisher, "cancelledRoutingKey", "cancelled");
        return publisher;
    }

    private static void inject(Object target, String field, Object value) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void orderCancelled_shouldPublish() {
        SeckillFeedbackPublisher publisher = newPublisher();

        publisher.orderCancelled("SK123", 1L, 2, 1);

        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    void orderCancelled_whenBrokerDown_shouldNotThrow() {
        SeckillFeedbackPublisher publisher = newPublisher();
        doThrow(new AmqpConnectException(new RuntimeException("broker down")))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        assertDoesNotThrow(() -> publisher.orderCancelled("SK123", 1L, 2, 1));
    }

    @Test
    void orderFailed_whenBrokerDown_shouldNotThrow() {
        SeckillFeedbackPublisher publisher = newPublisher();
        doThrow(new AmqpConnectException(new RuntimeException("broker down")))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        SeckillOrderCommand command = new SeckillOrderCommand(1L, 2L, 1, "token", 3L,
                4L, 5L, "商品", "规格", null, BigDecimal.TEN, null);

        assertDoesNotThrow(() -> publisher.orderFailed(command, "库存不足"));
    }

    @Test
    void events_shouldBeRoutable() {
        // 只要能序列化并带上路由键，消费端就能按 key 分发
        SeckillFeedbackPublisher publisher = newPublisher();
        publisher.orderCancelled("SK1", 1L, 0, 1);

        Mockito.verify(rabbitTemplate).convertAndSend(
                Mockito.eq("doroshop.seckill.feedback"), Mockito.eq("cancelled"), Mockito.any(Object.class));
    }
}
