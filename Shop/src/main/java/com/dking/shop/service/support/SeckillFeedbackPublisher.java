package com.dking.shop.service.support;

import com.dking.shop.dto.OrderCancelledEvent;
import com.dking.shop.dto.SeckillOrderCommand;
import com.dking.shop.dto.SeckillOrderFailedEvent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 交易 → 营销的反馈事件发布器。
 *
 * <p>两条事件：建单失败（还库存）、订单取消（还库存）。
 * 发布失败只记日志——库存回补丢了会导致少卖，由对账任务兜底，
 * 不能因为这个异常把用户已经成功的操作（比如取消）判成失败。</p>
 */
@Slf4j
@Component
public class SeckillFeedbackPublisher {

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private JsonMapper jsonMapper;

    @Value("${shop.seckill.feedback.exchange:doroshop.seckill.feedback}")
    private String exchange;

    @Value("${shop.seckill.feedback.failed-routing-key:failed}")
    private String failedRoutingKey;

    @Value("${shop.seckill.feedback.cancelled-routing-key:cancelled}")
    private String cancelledRoutingKey;

    /** 建单失败：营销回补桶 + 释放 Redis 预扣 */
    public void orderFailed(SeckillOrderCommand command, String reason) {
        try {
            SeckillOrderFailedEvent event = new SeckillOrderFailedEvent(
                    command.orderToken(), command.userId(), command.activityId(),
                    command.stockBucket(), reason);
            rabbitTemplate.convertAndSend(exchange, failedRoutingKey,
                    jsonMapper.writeValueAsString(event));
            log.info("已发送建单失败事件, token={}, activityId={}, reason={}",
                    command.orderToken(), command.activityId(), reason);
        } catch (RuntimeException e) {
            log.error("发送建单失败事件失败，等待对账, token={}", command.orderToken(), e);
        }
    }

    /** 秒杀订单取消：营销回补桶 + Redis 活动库存 */
    public void orderCancelled(String orderNo, Long activityId, Integer stockBucket, int quantity) {
        try {
            OrderCancelledEvent event = new OrderCancelledEvent(orderNo, activityId, stockBucket, quantity);
            rabbitTemplate.convertAndSend(exchange, cancelledRoutingKey,
                    jsonMapper.writeValueAsString(event));
            log.info("已发送订单取消事件, orderNo={}, activityId={}, bucket={}",
                    orderNo, activityId, stockBucket);
        } catch (RuntimeException e) {
            log.error("发送订单取消事件失败，等待对账, orderNo={}", orderNo, e);
        }
    }
}
