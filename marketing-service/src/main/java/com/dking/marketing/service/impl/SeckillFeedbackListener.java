package com.dking.marketing.service.impl;

import com.dking.marketing.dto.OrderCancelledEvent;
import com.dking.marketing.dto.SeckillOrderFailedEvent;
import com.dking.marketing.service.SeckillBucketStockService;
import com.dking.marketing.service.SeckillStockService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 交易 → 营销 的反馈事件消费者。
 *
 * <p>两个场景都要「把库存还回去」：</p>
 * <ul>
 *   <li><b>建单失败</b>：营销扣了桶和 Redis 预扣，但订单没建成 → 两样都还，允许用户重试</li>
 *   <li><b>订单取消</b>：订单建成了又被取消 → 桶和 Redis 活动库存都还</li>
 * </ul>
 *
 * <p>幂等：桶回补带 {@code available + n <= total} 约束，重复消费到满桶就是空操作；
 * Redis 侧同理由 Lua 的库存上限兜底。</p>
 */
@Slf4j
@Component
public class SeckillFeedbackListener {

    @Resource
    private SeckillBucketStockService bucketStockService;

    @Resource
    private SeckillStockService seckillStockService;

    @Resource
    private JsonMapper jsonMapper;

    /** 建单失败：回补桶 + 释放 Redis 预扣（保留用户占位？不保留——失败要让用户能重试） */
    @RabbitListener(queues = "${shop.seckill.feedback.failed-queue:doroshop.seckill.feedback.failed}")
    public void onOrderFailed(String payload) {
        SeckillOrderFailedEvent event = jsonMapper.readValue(payload, SeckillOrderFailedEvent.class);
        log.warn("收到秒杀建单失败事件, token={}, activityId={}, bucket={}, reason={}",
                event.token(), event.activityId(), event.stockBucket(), event.reason());

        if (event.stockBucket() != null) {
            bucketStockService.restore(event.activityId(), event.stockBucket(), 1);
        }
        seckillStockService.release(event.activityId(), event.userId(), false);
        log.info("秒杀建单失败库存已回补, token={}", event.token());
    }

    /** 订单取消：回补桶 + Redis 活动库存 */
    @RabbitListener(queues = "${shop.seckill.feedback.cancelled-queue:doroshop.seckill.feedback.cancelled}")
    public void onOrderCancelled(String payload) {
        OrderCancelledEvent event = jsonMapper.readValue(payload, OrderCancelledEvent.class);
        log.info("收到秒杀订单取消事件, orderNo={}, activityId={}, bucket={}",
                event.orderNo(), event.activityId(), event.stockBucket());

        if (event.stockBucket() != null) {
            bucketStockService.restore(event.activityId(), event.stockBucket(), event.quantity());
        }
        seckillStockService.restoreStock(event.activityId(), event.quantity());
        log.info("秒杀订单取消库存已回补, orderNo={}", event.orderNo());
    }
}
