package com.dking.shop.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dking.shop.dto.OrderSubmitDTO;
import com.dking.shop.entity.Cart;
import com.dking.shop.entity.OrderItem;
import com.dking.shop.entity.ProductSku;
import com.dking.shop.entity.ProductSpu;
import com.dking.shop.entity.ProductStock;
import com.dking.shop.entity.UserAddress;
import com.dking.shop.exception.BusinessException;
import com.dking.shop.mapper.CartMapper;
import com.dking.shop.mapper.OrderItemMapper;
import com.dking.shop.mapper.ProductSkuMapper;
import com.dking.shop.mapper.ProductSpuMapper;
import com.dking.shop.mapper.ProductStockMapper;
import com.dking.shop.mapper.UserAddressMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库存超卖：100 个不同用户同时抢 10 件库存，只能成功 10 单。
 * 并发测试不能用 @Transactional（工作线程看不到未提交夹具），夹具会留在 doro_mall_test。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class OrderSubmitTest {

    private static final int THREADS = 100;
    private static final int INITIAL_STOCK = 10;
    private static final int BUY_QUANTITY = 1;

    @Resource
    private OrderService orderService;

    @Resource
    private CartMapper cartMapper;

    @Resource
    private ProductSpuMapper productSpuMapper;

    @Resource
    private ProductSkuMapper productSkuMapper;

    @Resource
    private ProductStockMapper productStockMapper;

    @Resource
    private UserAddressMapper userAddressMapper;

    @Resource
    private OrderItemMapper orderItemMapper;

    private Long skuId;
    private Long stockId;
    private Long[] userIds;
    private OrderSubmitDTO[] dtos;

    @BeforeEach
    void setUp() {
        skuId = IdWorker.getId();
        stockId = IdWorker.getId();

        ProductSpu spu = new ProductSpu();
        spu.setId(IdWorker.getId());
        spu.setCategoryId(1L);
        spu.setName("超卖测试商品");
        spu.setCode("OVERSELL-SPU-" + spu.getId());
        spu.setPriceMin(new BigDecimal("9.90"));
        spu.setStatus(1);
        productSpuMapper.insert(spu);

        ProductSku sku = new ProductSku();
        sku.setId(skuId);
        sku.setSpuId(spu.getId());
        sku.setSkuCode("OVERSELL-SKU-" + skuId);
        sku.setSpecDesc("默认规格");
        sku.setPrice(new BigDecimal("9.90"));
        sku.setStatus(1);
        productSkuMapper.insert(sku);

        ProductStock stock = new ProductStock();
        stock.setId(stockId);
        stock.setSkuId(skuId);
        stock.setAvailableStock(INITIAL_STOCK);
        stock.setLockedStock(0);
        productStockMapper.insert(stock);

        userIds = new Long[THREADS];
        dtos = new OrderSubmitDTO[THREADS];
        for (int i = 0; i < THREADS; i++) {
            long userId = IdWorker.getId();
            userIds[i] = userId;

            UserAddress address = new UserAddress();
            address.setId(IdWorker.getId());
            address.setUserId(userId);
            address.setReceiverName("并发测试用户");
            address.setReceiverPhone("13800000000");
            address.setProvince("北京市");
            address.setCity("北京市");
            address.setDistrict("海淀区");
            address.setDetail("测试路 1 号");
            address.setIsDefault(1);
            userAddressMapper.insert(address);

            Cart cart = new Cart();
            cart.setUserId(userId);
            cart.setSkuId(skuId);
            cart.setQuantity(BUY_QUANTITY);
            cart.setSelected(1);
            cartMapper.insert(cart);

            OrderSubmitDTO dto = new OrderSubmitDTO();
            dto.setAddressId(address.getId());
            dto.setRemark("并发测试");
            dtos[i] = dto;
        }
    }

    @Test
    void concurrentSubmit_shouldNotOversell() throws Exception {
        AtomicInteger success = new AtomicInteger();
        List<String> expectedFailures = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        for (int i = 0; i < THREADS; i++) {
            int index = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    orderService.submit(userIds[index], dtos[index]);
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    // 抢不到库存的用户会走到这里，属于预期失败
                    expectedFailures.add(e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    end.countDown();
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "100 个线程未全部就位");
        start.countDown();
        assertTrue(end.await(60, TimeUnit.SECONDS), "提交没有在 60s 内结束");
        pool.shutdown();

        assertTrue(unexpected.isEmpty(), () -> "出现非预期异常: " + unexpected);
        assertTrue(expectedFailures.stream().allMatch(m -> m != null && m.contains("库存不足")),
                () -> "存在非库存不足的失败: " + expectedFailures);
        assertEquals(INITIAL_STOCK, success.get(), "成功下单数必须等于库存");
        assertEquals(THREADS - INITIAL_STOCK, expectedFailures.size(), "其余请求应因库存不足失败");

        ProductStock stock = productStockMapper.selectById(stockId);
        assertNotNull(stock, "库存行不存在");
        assertEquals(INITIAL_STOCK, stock.getLockedStock(), "锁定库存应等于成功下单量");
        assertEquals(0, stock.getAvailableStock(), "可用库存应扣到 0");

        Long orderItemCount = orderItemMapper.selectCount(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getSkuId, skuId));
        assertEquals(INITIAL_STOCK, orderItemCount.intValue(), "订单明细数应等于成功下单数");
    }
}
