package com.dking.shop.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dking.shop.entity.Order;
import com.dking.shop.entity.OrderItem;
import com.dking.shop.entity.ProductSku;
import com.dking.shop.entity.ProductSpu;
import com.dking.shop.entity.ProductStock;
import com.dking.shop.exception.BusinessException;
import com.dking.shop.mapper.OrderItemMapper;
import com.dking.shop.mapper.OrderMapper;
import com.dking.shop.mapper.ProductSkuMapper;
import com.dking.shop.mapper.ProductSpuMapper;
import com.dking.shop.mapper.ProductStockMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单超时取消：直接调用 OrderService，不依赖定时调度（测试 profile 已关闭定时任务）。
 * 同现有并发测试，不使用 @Transactional，夹具会留在 doro_mall_test。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class OrderTimeoutCancelTest {

    private static final int INITIAL_STOCK = 10;
    private static final int ORDER_STATUS_WAIT_PAY = 0;
    private static final int ORDER_STATUS_PAID = 1;
    private static final int ORDER_STATUS_CANCELLED = 4;
    private static final BigDecimal PRICE = new BigDecimal("9.90");

    @Resource
    private OrderService orderService;

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private OrderItemMapper orderItemMapper;

    @Resource
    private ProductSpuMapper productSpuMapper;

    @Resource
    private ProductSkuMapper productSkuMapper;

    @Resource
    private ProductStockMapper productStockMapper;

    private Long spuId;
    private Long skuId;
    private Long stockId;

    @BeforeEach
    void setUp() {
        ProductSpu spu = new ProductSpu();
        spu.setId(IdWorker.getId());
        spu.setCategoryId(1L);
        spu.setName("超时取消测试商品");
        spu.setCode("TIMEOUT-SPU-" + spu.getId());
        spu.setPriceMin(PRICE);
        spu.setStatus(1);
        productSpuMapper.insert(spu);
        spuId = spu.getId();

        ProductSku sku = new ProductSku();
        sku.setId(IdWorker.getId());
        sku.setSpuId(spuId);
        sku.setSkuCode("TIMEOUT-SKU-" + sku.getId());
        sku.setSpecDesc("默认规格");
        sku.setPrice(PRICE);
        sku.setStatus(1);
        productSkuMapper.insert(sku);
        skuId = sku.getId();

        ProductStock stock = new ProductStock();
        stock.setId(IdWorker.getId());
        stock.setSkuId(skuId);
        stock.setAvailableStock(INITIAL_STOCK);
        stock.setLockedStock(0);
        productStockMapper.insert(stock);
        stockId = stock.getId();
    }

    @Test
    void expiredOrder_shouldCancelAndReleaseStock() {
        Long orderId = createOrder(ORDER_STATUS_WAIT_PAY, LocalDateTime.now().minusMinutes(1), 2);

        assertTrue(orderService.cancelExpiredOrder(orderId), "过期订单应取消成功");

        Order order = orderMapper.selectById(orderId);
        assertEquals(ORDER_STATUS_CANCELLED, order.getStatus());
        assertNotNull(order.getCancelTime(), "取消时间应写入");

        ProductStock stock = productStockMapper.selectById(stockId);
        assertEquals(INITIAL_STOCK, stock.getAvailableStock(), "可用库存应全部归还");
        assertEquals(0, stock.getLockedStock(), "锁定库存应清零");
    }

    @Test
    void notExpiredOrder_shouldNotCancel() {
        Long orderId = createOrder(ORDER_STATUS_WAIT_PAY, LocalDateTime.now().plusMinutes(10), 2);

        assertFalse(orderService.cancelExpiredOrder(orderId), "未过期订单不应被取消");

        assertEquals(ORDER_STATUS_WAIT_PAY, orderMapper.selectById(orderId).getStatus());
        ProductStock stock = productStockMapper.selectById(stockId);
        assertEquals(INITIAL_STOCK - 2, stock.getAvailableStock());
        assertEquals(2, stock.getLockedStock());
    }

    @Test
    void paidOrder_shouldNotCancel() {
        Long orderId = createOrder(ORDER_STATUS_PAID, LocalDateTime.now().minusMinutes(1), 2);

        assertFalse(orderService.cancelExpiredOrder(orderId), "已支付订单不应被取消");

        assertEquals(ORDER_STATUS_PAID, orderMapper.selectById(orderId).getStatus());
        ProductStock stock = productStockMapper.selectById(stockId);
        assertEquals(INITIAL_STOCK - 2, stock.getAvailableStock(), "库存不应被重复归还");
        assertEquals(2, stock.getLockedStock(), "锁定库存不变");
    }

    @Test
    void cancelTwice_shouldReleaseStockOnce() {
        Long orderId = createOrder(ORDER_STATUS_WAIT_PAY, LocalDateTime.now().minusMinutes(1), 1);

        assertTrue(orderService.cancelExpiredOrder(orderId));
        assertFalse(orderService.cancelExpiredOrder(orderId), "第二次执行应识别为已处理");

        ProductStock stock = productStockMapper.selectById(stockId);
        assertEquals(INITIAL_STOCK, stock.getAvailableStock(), "库存只能归还一次");
        assertEquals(0, stock.getLockedStock());
    }

    @Test
    void expiredOrder_cannotPay() {
        Long orderId = createOrder(ORDER_STATUS_WAIT_PAY, LocalDateTime.now().minusMinutes(1), 1);
        Order order = orderMapper.selectById(orderId);

        assertThrows(BusinessException.class, () -> orderService.pay(order.getUserId(), orderId));

        assertEquals(ORDER_STATUS_WAIT_PAY, orderMapper.selectById(orderId).getStatus());
        ProductStock stock = productStockMapper.selectById(stockId);
        assertEquals(INITIAL_STOCK - 1, stock.getAvailableStock());
        assertEquals(1, stock.getLockedStock(), "支付失败不应释放锁定库存");
    }

    @Test
    void concurrentCancel_shouldReleaseStockOnce() throws Exception {
        Long orderId = createOrder(ORDER_STATUS_WAIT_PAY, LocalDateTime.now().minusMinutes(1), 1);
        Long userId = orderMapper.selectById(orderId).getUserId();

        int threads = 20;
        AtomicInteger timeoutWins = new AtomicInteger();
        AtomicInteger userCancelWins = new AtomicInteger();
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            boolean timeoutSide = i % 2 == 0;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (timeoutSide) {
                        if (orderService.cancelExpiredOrder(orderId)) {
                            timeoutWins.incrementAndGet();
                        }
                    } else {
                        orderService.cancel(userId, orderId);
                        userCancelWins.incrementAndGet();
                    }
                } catch (BusinessException e) {
                    // 竞争失败的一方：订单状态已被另一条路径修改，属于预期
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    end.countDown();
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未全部就位");
        start.countDown();
        assertTrue(end.await(60, TimeUnit.SECONDS), "并发取消未在 60s 内结束");
        pool.shutdown();

        assertTrue(unexpected.isEmpty(), () -> "出现非预期异常: " + unexpected);
        assertEquals(1, timeoutWins.get() + userCancelWins.get(), "只能有一条路径取消成功");
        assertEquals(ORDER_STATUS_CANCELLED, orderMapper.selectById(orderId).getStatus());

        ProductStock stock = productStockMapper.selectById(stockId);
        assertEquals(INITIAL_STOCK, stock.getAvailableStock(), "库存只能归还一次");
        assertEquals(0, stock.getLockedStock());
    }

    @Test
    void findExpiredWaitPayOrderIds_shouldRespectLimitAndFilter() {
        Long first = createOrder(ORDER_STATUS_WAIT_PAY, LocalDateTime.now().minusMinutes(3), 1);
        Long second = createOrder(ORDER_STATUS_WAIT_PAY, LocalDateTime.now().minusMinutes(2), 1);
        Long third = createOrder(ORDER_STATUS_WAIT_PAY, LocalDateTime.now().minusMinutes(1), 1);
        Long paid = createOrder(ORDER_STATUS_PAID, LocalDateTime.now().minusMinutes(1), 1);
        Long future = createOrder(ORDER_STATUS_WAIT_PAY, LocalDateTime.now().plusMinutes(10), 1);

        List<Long> all = orderService.findExpiredWaitPayOrderIds(1000);
        assertTrue(all.containsAll(List.of(first, second, third)));
        assertFalse(all.contains(paid), "已支付订单不应入选");
        assertFalse(all.contains(future), "未过期订单不应入选");
        assertTrue(all.indexOf(first) < all.indexOf(second));
        assertTrue(all.indexOf(second) < all.indexOf(third));

        List<Long> limited = orderService.findExpiredWaitPayOrderIds(2);
        assertEquals(2, limited.size(), "limit 应生效");
        for (Long id : limited) {
            Order order = orderMapper.selectById(id);
            assertEquals(ORDER_STATUS_WAIT_PAY, order.getStatus());
            assertTrue(order.getExpireTime().isBefore(LocalDateTime.now()));
        }
    }

    /**
     * 直接插入待支付订单并同步库存，模拟“下单已锁定、尚未支付”的中间态。
     */
    private Long createOrder(int status, LocalDateTime expireTime, int quantity) {
        String orderNo = "TIMEOUT" + IdWorker.getId();
        BigDecimal amount = PRICE.multiply(BigDecimal.valueOf(quantity));

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(IdWorker.getId());
        order.setMerchantId(0L);
        order.setTotalAmount(amount);
        order.setPayAmount(amount);
        order.setStatus(status);
        order.setExpireTime(expireTime);
        orderMapper.insert(order);

        OrderItem item = new OrderItem();
        item.setOrderId(order.getId());
        item.setOrderNo(orderNo);
        item.setSpuId(spuId);
        item.setSkuId(skuId);
        item.setProductName("超时取消测试商品");
        item.setSpecDesc("默认规格");
        item.setPrice(PRICE);
        item.setQuantity(quantity);
        item.setTotalAmount(amount);
        orderItemMapper.insert(item);

        productStockMapper.update(null, new LambdaUpdateWrapper<ProductStock>()
                .eq(ProductStock::getSkuId, skuId)
                .setSql("available_stock = available_stock - " + quantity)
                .setSql("locked_stock = locked_stock + " + quantity));

        return order.getId();
    }
}
