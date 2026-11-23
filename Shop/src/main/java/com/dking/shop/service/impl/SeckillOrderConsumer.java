package com.dking.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dking.shop.dto.SeckillOrderCommand;
import com.dking.shop.dto.SeckillOrderMessage;
import com.dking.shop.entity.Order;
import com.dking.shop.exception.BusinessException;
import com.dking.shop.exception.NotFoundException;
import com.dking.shop.mapper.OrderMapper;
import com.dking.shop.service.OrderService;
import com.dking.shop.service.support.SeckillFeedbackPublisher;
import com.dking.shop.service.support.SeckillOrderResultStore;
import com.dking.shop.vo.OrderSubmitVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 秒杀建单消费者：营销扣完活动库存后发消息，这里落订单。
 *
 * <p>失败分两类处理：</p>
 * <ul>
 *   <li><b>永久失败</b>（地址不存在、商品下架、重复下单）：写失败结果 + 发反馈事件，
 *       营销负责把桶和 Redis 预扣还回去；</li>
 *   <li><b>暂时失败</b>（DB 抖动）：直接抛出，交给 MQ 重试，预扣先留着，避免重复扣减。</li>
 * </ul>
 *
 * <p>幂等：同一 token 重复投递时先按 order_token 查一次，已建单就直接写成功结果，
 * 不依赖消费端只投一次。</p>
 */
@Slf4j
@Component
@Profile("!test")
public class SeckillOrderConsumer {

    @Resource
    private OrderService orderService;

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private SeckillOrderResultStore orderResultStore;

    @Resource
    private SeckillFeedbackPublisher feedbackPublisher;

    @Resource
    private JsonMapper jsonMapper;

    @RabbitListener(queues = "${shop.seckill.async.queue:doroshop.seckill.order}")
    public void onMessage(String payload) {
        SeckillOrderMessage message = jsonMapper.readValue(payload, SeckillOrderMessage.class);
        SeckillOrderCommand command = message.command();
        log.info("收到秒杀建单消息, token={}, userId={}, activityId={}",
                message.token(), command.userId(), command.activityId());

        // 重投幂等：已建单就直接补写成功结果
        Order existing = findByToken(message.token());
        if (existing != null) {
            orderResultStore.markSuccess(message.token(), command.userId(), toOrderSubmitVO(existing));
            log.info("秒杀订单已存在，跳过建单, token={}, orderNo={}", message.token(), existing.getOrderNo());
            return;
        }

        try {
            OrderSubmitVO vo = orderService.createSeckillOrder(command);
            orderResultStore.markSuccess(message.token(), command.userId(), vo);
            log.info("秒杀建单成功, token={}, orderNo={}", message.token(), vo.getOrderNo());
        } catch (DuplicateKeyException e) {
            // 并发重复投递撞唯一键：以已存在的订单为准
            Order duplicated = findByToken(message.token());
            if (duplicated != null) {
                orderResultStore.markSuccess(message.token(), command.userId(), toOrderSubmitVO(duplicated));
                log.info("秒杀订单唯一键冲突，按已建单处理, token={}", message.token());
            } else {
                orderResultStore.markFailed(message.token(), command.userId(), "您已参与过该活动，请勿重复提交");
                feedbackPublisher.orderFailed(command, "您已参与过该活动，请勿重复提交");
            }
        } catch (BusinessException | NotFoundException e) {
            // 永久失败：写失败结果 + 让营销还库存，用户可重试
            log.warn("秒杀建单失败, token={}, reason={}", message.token(), e.getMessage());
            orderResultStore.markFailed(message.token(), command.userId(), e.getMessage());
            feedbackPublisher.orderFailed(command, e.getMessage());
        }
        // 其余异常（DB 暂时不可用等）直接抛出，交给 MQ 重试
    }

    private Order findByToken(String token) {
        return orderMapper.selectOne(new LambdaQueryWrapper<Order>().eq(Order::getOrderToken, token));
    }

    private OrderSubmitVO toOrderSubmitVO(Order order) {
        OrderSubmitVO vo = new OrderSubmitVO();
        vo.setOrderId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setPayAmount(order.getPayAmount());
        vo.setStatus(order.getStatus());
        vo.setExpireTime(order.getExpireTime());
        return vo;
    }
}
