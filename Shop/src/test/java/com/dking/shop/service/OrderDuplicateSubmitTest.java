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
 * 重复提交：同一用户、同一份购物车，20 个请求同时提交，只应生成 1 张订单、只扣 1 次库存。
 * 当前实现没有幂等控制，这个测试预期是红的，用来复现缺陷。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class OrderDuplicateSubmitTest {

    private static final int THREADS = 20;
    private static final int INITIAL_STOCK = 100;
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

    private Long userId;
    private Long skuId;
    private Long stockId;
    private OrderSubmitDTO dto;

    @BeforeEach
    void setUp() {
        userId = IdWorker.getId();
        skuId = IdWorker.getId();
        stockId = IdWorker.getId();

        ProductSpu spu = new ProductSpu();
        spu.setId(IdWorker.getId());
        spu.setCategoryId(1L);
        spu.setName("重复提交测试商品");
        spu.setCode("DUP-SPU-" + spu.getId());
        spu.setPriceMin(new BigDecimal("9.90"));
        spu.setStatus(1);
        productSpuMapper.insert(spu);

        ProductSku sku = new ProductSku();
        sku.setId(skuId);
        sku.setSpuId(spu.getId());
        sku.setSkuCode("DUP-SKU-" + skuId);
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

        UserAddress address = new UserAddress();
        address.setId(IdWorker.getId());
        address.setUserId(userId);
        address.setReceiverName("重复提交用户");
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

        dto = new OrderSubmitDTO();
        dto.setAddressId(address.getId());
        dto.setRemark("重复提交测试");
    }

    @Test
    void concurrentSubmit_shouldCreateOnlyOneOrder() throws Exception {
        AtomicInteger success = new AtomicInteger();
        List<String> businessFailures = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    orderService.submit(userId, dto);
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    businessFailures.add(e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    end.countDown();
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "20 个线程未全部就位");
        start.countDown();
        assertTrue(end.await(60, TimeUnit.SECONDS), "提交没有在 60s 内结束");
        pool.shutdown();

        assertTrue(unexpected.isEmpty(), () -> "出现非预期异常: " + unexpected);
        assertEquals(1, success.get(),
                () -> "重复提交成功次数应为 1，实际 " + success.get() + "，失败信息: " + businessFailures);

        ProductStock stock = productStockMapper.selectById(stockId);
        assertNotNull(stock, "库存行不存在");
        assertEquals(INITIAL_STOCK - BUY_QUANTITY, stock.getAvailableStock(), "库存只应扣 1 次");
        assertEquals(BUY_QUANTITY, stock.getLockedStock(), "锁定库存只应有 1 件");

        Long orderItemCount = orderItemMapper.selectCount(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getSkuId, skuId));
        assertEquals(1, orderItemCount.intValue(), "只应生成 1 条订单明细");
    }
}
