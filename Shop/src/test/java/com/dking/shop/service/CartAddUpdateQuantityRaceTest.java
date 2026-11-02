package com.dking.shop.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dking.shop.dto.CartAddDTO;
import com.dking.shop.dto.CartQuantityDTO;
import com.dking.shop.entity.Cart;
import com.dking.shop.entity.ProductSku;
import com.dking.shop.entity.ProductSpu;
import com.dking.shop.entity.ProductStock;
import com.dking.shop.mapper.CartMapper;
import com.dking.shop.mapper.ProductSkuMapper;
import com.dking.shop.mapper.ProductSpuMapper;
import com.dking.shop.mapper.ProductStockMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * add 和 updateQuantity 并发：add 走 JVM 用户锁，updateQuantity 没有；两者都整行写回。
 * 串行结果只有两种：先 add 后 set → 10，先 set 后 add → 11。
 * 出现 6 之类的值，说明 add 基于旧读取覆盖了 set 的绝对赋值。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class CartAddUpdateQuantityRaceTest {

    private static final int ITERATIONS = 300;
    private static final int INITIAL_QUANTITY = 5;
    private static final int SET_QUANTITY = 10;
    private static final int STOCK = 100_000;

    @Resource
    private CartService cartService;

    @Resource
    private CartMapper cartMapper;

    @Resource
    private ProductSpuMapper productSpuMapper;

    @Resource
    private ProductSkuMapper productSkuMapper;

    @Resource
    private ProductStockMapper productStockMapper;

    private Long skuId;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        ProductSpu spu = new ProductSpu();
        spu.setCategoryId(1L);
        spu.setName("加减并发测试商品");
        spu.setCode("RACE-SPU-" + IdWorker.getId());
        spu.setPriceMin(new BigDecimal("9.90"));
        spu.setStatus(1);
        productSpuMapper.insert(spu);

        ProductSku sku = new ProductSku();
        sku.setSpuId(spu.getId());
        sku.setSkuCode("RACE-SKU-" + IdWorker.getId());
        sku.setSpecDesc("默认规格");
        sku.setPrice(new BigDecimal("9.90"));
        sku.setStatus(1);
        productSkuMapper.insert(sku);
        skuId = sku.getId();

        ProductStock stock = new ProductStock();
        stock.setSkuId(skuId);
        stock.setAvailableStock(STOCK);
        stock.setLockedStock(0);
        productStockMapper.insert(stock);

        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void concurrentAddAndUpdateQuantity_shouldNotLoseTheAbsoluteSet() throws Exception {
        List<String> violations = new ArrayList<>();
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < ITERATIONS; i++) {
            long userId = IdWorker.getId();
            Cart cart = new Cart();
            cart.setUserId(userId);
            cart.setSkuId(skuId);
            cart.setQuantity(INITIAL_QUANTITY);
            cart.setSelected(1);
            cartMapper.insert(cart);
            Long cartId = cart.getId();

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch end = new CountDownLatch(2);

            pool.submit(() -> runTask(ready, start, end, () -> {
                CartAddDTO dto = new CartAddDTO();
                dto.setSkuId(skuId);
                dto.setQuantity(1);
                cartService.add(userId, dto);
            }, unexpected));

            pool.submit(() -> runTask(ready, start, end, () -> {
                CartQuantityDTO dto = new CartQuantityDTO();
                dto.setId(cartId);
                dto.setQuantity(SET_QUANTITY);
                cartService.updateQuantity(userId, dto);
            }, unexpected));

            assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未全部就位");
            start.countDown();
            assertTrue(end.await(10, TimeUnit.SECONDS), "并发任务未在 10s 内结束");

            int finalQuantity = cartMapper.selectById(cartId).getQuantity();
            if (finalQuantity != SET_QUANTITY && finalQuantity != SET_QUANTITY + 1) {
                violations.add("iteration=" + i + ", finalQuantity=" + finalQuantity);
            }
        }

        assertTrue(unexpected.isEmpty(), () -> "出现异常: " + unexpected);
        assertTrue(violations.isEmpty(), () -> "出现不符合任何串行结果的数量: " + violations);
    }

    private void runTask(CountDownLatch ready, CountDownLatch start, CountDownLatch end,
                         Runnable action, List<Throwable> unexpected) {
        ready.countDown();
        try {
            start.await();
            action.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            unexpected.add(t);
        } finally {
            end.countDown();
        }
    }
}
