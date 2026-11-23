package com.dking.shop.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dking.shop.entity.Order;
import com.dking.shop.entity.OrderItem;
import com.dking.shop.mapper.OrderItemMapper;
import com.dking.shop.mapper.OrderMapper;
import com.dking.shop.service.support.SeckillFeedbackPublisher;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 秒杀订单取消时，交易侧只发事件，不直接回补活动库存。
 *
 * <p>这条边界很重要：库存回补的唯一入口是营销消费 {@code order.cancelled}。
 * 交易侧如果偷偷自己扣了桶，两边就会各算一遍。</p>
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class OrderCancelFeedbackTest {

    /** 每个用例用独立活动 id：orders 是逻辑删除，物理唯一键 uk_activity_user 不会因为 deleted=1 释放 */
    private Long activityId;
    private static final Integer STOCK_BUCKET = 3;

    @Resource
    private OrderService orderService;

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private OrderItemMapper orderItemMapper;

    @MockitoBean
    private SeckillFeedbackPublisher feedbackPublisher;

    private Long orderId;
    private String orderNo;

    @BeforeEach
    void setUp() {
        activityId = IdWorker.getId();

        orderNo = "SK" + IdWorker.getIdStr();
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(7770001L);
        order.setMerchantId(0L);
        order.setActivityId(activityId);
        order.setOrderToken("token-" + IdWorker.getIdStr());
        order.setStockBucket(STOCK_BUCKET);
        order.setTotalAmount(new BigDecimal("19.90"));
        order.setPayAmount(new BigDecimal("19.90"));
        order.setStatus(0);
        order.setExpireTime(LocalDateTime.now().plusMinutes(30));
        orderMapper.insert(order);
        orderId = order.getId();

        OrderItem item = new OrderItem();
        item.setOrderId(orderId);
        item.setOrderNo(orderNo);
        item.setSpuId(5550001L);
        item.setSkuId(5550002L);
        item.setProductName("秒杀测试商品");
        item.setSpecDesc("默认");
        item.setPrice(new BigDecimal("19.90"));
        item.setQuantity(1);
        item.setTotalAmount(new BigDecimal("19.90"));
        orderItemMapper.insert(item);
    }

    @Test
    void cancelSeckillOrder_shouldPublishCancelledEvent() {
        orderService.cancel(7770001L, orderId);

        assertEquals(4, orderMapper.selectById(orderId).getStatus(), "订单应变为已取消");
        verify(feedbackPublisher).orderCancelled(eq(orderNo), eq(activityId), eq(STOCK_BUCKET), eq(1));
    }

    @Test
    void cancelNormalOrder_shouldNotPublishSeckillEvent() {
        Order normal = new Order();
        normal.setOrderNo("N" + IdWorker.getIdStr());
        normal.setUserId(7770002L);
        normal.setMerchantId(0L);
        normal.setTotalAmount(new BigDecimal("9.90"));
        normal.setPayAmount(new BigDecimal("9.90"));
        normal.setStatus(0);
        normal.setExpireTime(LocalDateTime.now().plusMinutes(30));
        orderMapper.insert(normal);

        OrderItem item = new OrderItem();
        item.setOrderId(normal.getId());
        item.setOrderNo(normal.getOrderNo());
        item.setSpuId(5550003L);
        item.setSkuId(5550004L);
        item.setProductName("普通商品");
        item.setSpecDesc("默认");
        item.setPrice(new BigDecimal("9.90"));
        item.setQuantity(1);
        item.setTotalAmount(new BigDecimal("9.90"));
        orderItemMapper.insert(item);

        orderService.cancel(7770002L, normal.getId());

        assertEquals(4, orderMapper.selectById(normal.getId()).getStatus());
        // 普通订单走商品库存回滚，不该发秒杀事件
        verify(feedbackPublisher, never()).orderCancelled(anyString(), eq(activityId), eq(STOCK_BUCKET), eq(1));
    }
}
