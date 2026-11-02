package com.dking.shop.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dking.shop.dto.CartAddDTO;
import com.dking.shop.entity.Cart;
import com.dking.shop.entity.ProductSku;
import com.dking.shop.entity.ProductSpu;
import com.dking.shop.entity.ProductStock;
import com.dking.shop.mapper.CartMapper;
import com.dking.shop.mapper.ProductSkuMapper;
import com.dking.shop.mapper.ProductSpuMapper;
import com.dking.shop.mapper.ProductStockMapper;
import com.dking.shop.service.impl.CartServiceImpl;
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

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles({"local","test"})
public class CartConcurrencyTest {
    int threads = 20;
    int tasks = 20;
    // 测试输入数据
    Long userId;
    Long skuId;
    Integer quantity = 1;
    CartAddDTO cartAddDTO = new CartAddDTO();

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


    @Test
    void concurrentAdd() throws Exception{
        CountDownLatch ready = new CountDownLatch(tasks);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        // 使用CountDownLatch协调所有线程开始
        CountDownLatch startLatch = new CountDownLatch(1);
        // 使用CountDownL 协调所有线程结束
        CountDownLatch endLatch = new CountDownLatch(tasks);
        // 提交任务
        for (int i = 0; i < tasks; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    startLatch.await();     // 所有线程在此等待
                    cartService.add(userId, cartAddDTO);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "20个线程未全部就位");
        startLatch.countDown();
        assertTrue(endLatch.await(30, TimeUnit.SECONDS), "任务没有在30s内结束");
        pool.shutdown();

        assertTrue(errors.isEmpty(), () -> "并发加购车抛出异常: " + errors);
        Cart cart = cartMapper.selectOne(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getUserId,userId)
                .eq(Cart::getSkuId,skuId));
        assertNotNull(cart, "购物车行不存在");
        assertEquals(tasks * quantity, cart.getQuantity(), "20次加购丢了更新");
    }

    // 造夹具
    @BeforeEach
    void setUp () {
        long runId = IdWorker.getId();
        userId = runId;
        skuId = runId + 1;

        ProductSpu spu = new ProductSpu();
        spu.setId(runId);
        spu.setCategoryId(1L);
        spu.setName("并发测试商品");
        spu.setCode("CONC-SPU-" + runId);
        spu.setPriceMin(new BigDecimal("9.90"));
        spu.setStatus(1);
        productSpuMapper.insert(spu);

        ProductSku sku = new ProductSku();
        sku.setId(skuId);
        sku.setSpuId(runId);
        sku.setSkuCode("CON-SKU-" + skuId);
        sku.setSpecDesc("默认价格");
        sku.setPrice(new BigDecimal("9.90"));
        sku.setStatus(1);
        productSkuMapper.insert(sku);

        ProductStock stock = new ProductStock();
        stock.setSkuId(sku.getId());
        stock.setAvailableStock(100);
        productStockMapper.insert(stock);

        cartAddDTO.setSkuId(skuId);
        cartAddDTO.setQuantity(quantity);
    }




}
