package com.dking.shop.task;

import com.dking.shop.service.OrderService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 订单超时取消：每轮取一批过期未支付订单，逐单事务取消。
 * 单轮只处理一批，失败订单留给下一轮重试，避免同一轮内反复选中造成死循环。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "shop.order.timeout", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class OrderTimeoutTask {

    @Resource
    private OrderService orderService;

    @Value("${shop.order.timeout.batch-size:100}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${shop.order.timeout.scan-interval-ms:60000}",
            initialDelayString = "${shop.order.timeout.initial-delay-ms:30000}")
    public void cancelExpiredOrders() {
        List<Long> orderIds = orderService.findExpiredWaitPayOrderIds(batchSize);
        if (orderIds.isEmpty()) {
            return;
        }

        int cancelled = 0;
        int failed = 0;
        for (Long orderId : orderIds) {
            try {
                if (orderService.cancelExpiredOrder(orderId)) {
                    cancelled++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("超时取消订单失败, orderId={}", orderId, e);
            }
        }

        log.info("超时取消扫描完成, 候选={}, 取消={}, 失败={}", orderIds.size(), cancelled, failed);
    }
}
