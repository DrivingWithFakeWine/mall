package com.dking.shop.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dking.shop.dto.DirectOrderSubmitDTO;
import com.dking.shop.entity.Cart;
import com.dking.shop.entity.Order;
import com.dking.shop.entity.OrderAddress;
import com.dking.shop.entity.OrderItem;
import com.dking.shop.entity.ProductSku;
import com.dking.shop.entity.ProductSpu;
import com.dking.shop.entity.ProductStock;
import com.dking.shop.entity.UserAddress;
import com.dking.shop.exception.BusinessException;
import com.dking.shop.mapper.CartMapper;
import com.dking.shop.mapper.OrderAddressMapper;
import com.dking.shop.mapper.OrderItemMapper;
import com.dking.shop.mapper.OrderMapper;
import com.dking.shop.mapper.ProductSkuMapper;
import com.dking.shop.mapper.ProductSpuMapper;
import com.dking.shop.mapper.ProductStockMapper;
import com.dking.shop.mapper.UserAddressMapper;
import com.dking.shop.vo.OrderSubmitVO;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 立即购买：单 SKU 直购，不经购物车。
 * 并发用例不用 @Transactional，夹具会留在 doro_mall_test。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class OrderDirectBuyTest {

    private static final int INITIAL_STOCK = 10;
    private static final BigDecimal PRICE = new BigDecimal("199.00");

    @Resource
    private OrderService orderService;

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private OrderItemMapper orderItemMapper;

    @Resource
    private OrderAddressMapper orderAddressMapper;

    @Resource
    private ProductSpuMapper productSpuMapper;

    @Resource
    private ProductSkuMapper productSkuMapper;

    @Resource
    private ProductStockMapper productStockMapper;

    @Resource
    private UserAddressMapper userAddressMapper;

    @Resource
    private CartMapper cartMapper;

    private Long spuId;
    private Long skuId;
    private Long stockId;

    @BeforeEach
    void setUp() {
        ProductSpu spu = new ProductSpu();
        spu.setId(IdWorker.getId());
        spu.setCategoryId(1L);
        spu.setName("直购测试商品");
        spu.setCode("DIRECT-SPU-" + spu.getId());
        spu.setPriceMin(PRICE);
        spu.setStatus(1);
        productSpuMapper.insert(spu);
        spuId = spu.getId();

        ProductSku sku = new ProductSku();
        sku.setId(IdWorker.getId());
        sku.setSpuId(spuId);
        sku.setSkuCode("DIRECT-SKU-" + sku.getId());
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
    void directBuy_shouldCreateOrderAndLockStock() {
        long userId = IdWorker.getId();
        Long addressId = createAddress(userId);
        // 购物车里放一件同 SKU，直购后不应被删除
        Cart cart = new Cart();
        cart.setUserId(userId);
        cart.setSkuId(skuId);
        cart.setQuantity(1);
        cart.setSelected(1);
        cartMapper.insert(cart);

        OrderSubmitVO vo = orderService.submitDirect(userId, dto(addressId, 2));

        assertNotNull(vo.getOrderId());
        assertEquals(0, new BigDecimal("398.00").compareTo(vo.getPayAmount()));
        assertEquals(0, vo.getStatus());

        Order order = orderMapper.selectById(vo.getOrderId());
        assertEquals(0, order.getStatus());
        assertEquals(null, order.getActivityId());
        assertEquals(0, new BigDecimal("398.00").compareTo(order.getPayAmount()));

        OrderItem item = orderItemMapper.selectOne(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, order.getId()));
        assertEquals(skuId, item.getSkuId());
        assertEquals(2, item.getQuantity());
        assertEquals(0, PRICE.compareTo(item.getPrice()));

        OrderAddress address = orderAddressMapper.selectOne(
                new LambdaQueryWrapper<OrderAddress>().eq(OrderAddress::getOrderId, order.getId()));
        assertNotNull(address);

        ProductStock stock = productStockMapper.selectById(stockId);
        assertEquals(INITIAL_STOCK - 2, stock.getAvailableStock());
        assertEquals(2, stock.getLockedStock());

        assertEquals(1, cartMapper.selectCount(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getUserId, userId)).intValue(), "直购不应删除购物车项");
    }

    @Test
    void directBuy_insufficientStock_shouldFail() {
        long userId = IdWorker.getId();
        Long addressId = createAddress(userId);

        BusinessException e = assertThrows(BusinessException.class,
                () -> orderService.submitDirect(userId, dto(addressId, INITIAL_STOCK + 1)));
        assertTrue(e.getMessage().contains("库存不足"));

        ProductStock stock = productStockMapper.selectById(stockId);
        assertEquals(INITIAL_STOCK, stock.getAvailableStock());
        assertEquals(0, stock.getLockedStock());
    }

    @Test
    void directBuy_offShelfSku_shouldFail() {
        long userId = IdWorker.getId();
        Long addressId = createAddress(userId);
        productSkuMapper.update(null, new LambdaUpdateWrapper<ProductSku>()
                .eq(ProductSku::getId, skuId)
                .set(ProductSku::getStatus, 0));

        BusinessException e = assertThrows(BusinessException.class,
                () -> orderService.submitDirect(userId, dto(addressId, 1)));
        assertTrue(e.getMessage().contains("已下架"));
        assertEquals(INITIAL_STOCK, productStockMapper.selectById(stockId).getAvailableStock());
    }

    @Test
    void directBuy_otherUsersAddress_shouldFail() {
        long owner = IdWorker.getId();
        Long otherAddressId = createAddress(owner);
        long buyer = IdWorker.getId();

        BusinessException e = assertThrows(BusinessException.class,
                () -> orderService.submitDirect(buyer, dto(otherAddressId, 1)));
        assertTrue(e.getMessage().contains("收货地址不存在"));
        assertEquals(INITIAL_STOCK, productStockMapper.selectById(stockId).getAvailableStock());
    }

    @Test
    void directBuy_concurrent_shouldNotOversell() throws Exception {
        int threads = 100;
        Long[] userIds = new Long[threads];
        DirectOrderSubmitDTO[] dtos = new DirectOrderSubmitDTO[threads];
        for (int i = 0; i < threads; i++) {
            long userId = IdWorker.getId();
            userIds[i] = userId;
            dtos[i] = dto(createAddress(userId), 1);
        }

        AtomicInteger success = new AtomicInteger();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            int index = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    orderService.submitDirect(userIds[index], dtos[index]);
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    failures.add(e.getMessage());
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
        assertTrue(end.await(60, TimeUnit.SECONDS), "直购未在 60s 内结束");
        pool.shutdown();

        assertTrue(unexpected.isEmpty(), () -> "出现非预期异常: " + unexpected);
        assertTrue(failures.stream().allMatch(m -> m != null && m.contains("库存不足")),
                () -> "存在非库存不足的失败: " + failures);
        assertEquals(INITIAL_STOCK, success.get());

        ProductStock stock = productStockMapper.selectById(stockId);
        assertEquals(0, stock.getAvailableStock());
        assertEquals(INITIAL_STOCK, stock.getLockedStock());
    }

    private DirectOrderSubmitDTO dto(Long addressId, int quantity) {
        DirectOrderSubmitDTO dto = new DirectOrderSubmitDTO();
        dto.setSkuId(skuId);
        dto.setAddressId(addressId);
        dto.setQuantity(quantity);
        dto.setRemark("直购测试");
        return dto;
    }

    private Long createAddress(long userId) {
        UserAddress address = new UserAddress();
        address.setId(IdWorker.getId());
        address.setUserId(userId);
        address.setReceiverName("直购测试用户");
        address.setReceiverPhone("13800000000");
        address.setProvince("北京市");
        address.setCity("北京市");
        address.setDistrict("海淀区");
        address.setDetail("测试路 1 号");
        address.setIsDefault(1);
        userAddressMapper.insert(address);
        return address.getId();
    }
}
