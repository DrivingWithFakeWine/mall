package com.dking.shop.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dking.shop.dto.SeckillOrderDTO;
import com.dking.shop.dto.SeckillOrderMessage;
import com.dking.shop.entity.Category;
import com.dking.shop.entity.Order;
import com.dking.shop.entity.OrderItem;
import com.dking.shop.entity.ProductSku;
import com.dking.shop.entity.ProductSpu;
import com.dking.shop.entity.ProductStock;
import com.dking.shop.entity.SeckillActivity;
import com.dking.shop.entity.UserAddress;
import com.dking.shop.exception.BusinessException;
import com.dking.shop.exception.NotFoundException;
import com.dking.shop.mapper.CategoryMapper;
import com.dking.shop.mapper.OrderItemMapper;
import com.dking.shop.mapper.OrderMapper;
import com.dking.shop.mapper.ProductSkuMapper;
import com.dking.shop.mapper.ProductSpuMapper;
import com.dking.shop.mapper.ProductStockMapper;
import com.dking.shop.mapper.SeckillActivityMapper;
import com.dking.shop.mapper.UserAddressMapper;
import com.dking.shop.vo.OrderSubmitVO;
import com.dking.shop.vo.PageVO;
import com.dking.shop.vo.SeckillActivityDetailVO;
import com.dking.shop.vo.SeckillActivityListItemVO;
import com.dking.shop.vo.SeckillOrderSubmitVO;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 秒杀 MVP：活动库存条件扣减、唯一键幂等、与普通订单库存隔离。
 * 并发用例不用 @Transactional，夹具会留在 doro_mall_test。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class SeckillOrderTest {

    private static final int PRODUCT_STOCK = 10;
    private static final BigDecimal ORIGINAL_PRICE = new BigDecimal("99.00");
    private static final BigDecimal SECKILL_PRICE = new BigDecimal("9.90");

    @Resource
    private SeckillService seckillService;

    @Resource
    private SeckillStockService seckillStockService;

    @Resource
    private SeckillBucketStockService bucketStockService;

    @Resource
    private StringRedisTemplate redis;

    @Value("${shop.seckill.redis.prefix:doro:seckill}")
    private String redisPrefix;

    private final List<Long> createdActivityIds = new ArrayList<>();

    @Resource
    private OrderService orderService;

    @Resource
    private SeckillActivityMapper seckillActivityMapper;

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

    @Resource
    private UserAddressMapper userAddressMapper;

    @Resource
    private CategoryMapper categoryMapper;

    private Long spuId;
    private Long skuId;
    private Long stockId;

    @BeforeEach
    void setUp() {
        ProductSpu spu = new ProductSpu();
        spu.setId(IdWorker.getId());
        spu.setCategoryId(1L);
        spu.setName("秒杀测试商品");
        spu.setCode("SECKILL-SPU-" + spu.getId());
        spu.setPriceMin(SECKILL_PRICE);
        spu.setStatus(1);
        productSpuMapper.insert(spu);
        spuId = spu.getId();

        ProductSku sku = new ProductSku();
        sku.setId(IdWorker.getId());
        sku.setSpuId(spuId);
        sku.setSkuCode("SECKILL-SKU-" + sku.getId());
        sku.setSpecDesc("默认规格");
        sku.setPrice(ORIGINAL_PRICE);
        sku.setStatus(1);
        productSkuMapper.insert(sku);
        skuId = sku.getId();

        ProductStock stock = new ProductStock();
        stock.setId(IdWorker.getId());
        stock.setSkuId(skuId);
        stock.setAvailableStock(PRODUCT_STOCK);
        stock.setLockedStock(0);
        productStockMapper.insert(stock);
        stockId = stock.getId();
    }

    @AfterEach
    void cleanRedisKeys() {
        String prefix = redisPrefix.endsWith(":")
                ? redisPrefix.substring(0, redisPrefix.length() - 1)
                : redisPrefix;
        for (Long id : createdActivityIds) {
            redis.delete(List.of(prefix + ":stock:" + id, prefix + ":users:" + id));
        }
        createdActivityIds.clear();
    }

    @Test
    void concurrentSubmit_shouldNotOversell() throws Exception {
        int threads = 100;
        int activityStock = 10;
        Long activityId = createActivity(activityStock, 1,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));

        Long[] userIds = new Long[threads];
        SeckillOrderDTO[] dtos = new SeckillOrderDTO[threads];
        for (int i = 0; i < threads; i++) {
            long userId = IdWorker.getId();
            userIds[i] = userId;
            dtos[i] = dtoOf(createAddress(userId));
        }

        AtomicInteger success = new AtomicInteger();
        List<String> expectedFailures = Collections.synchronizedList(new ArrayList<>());
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
                    seckillService.submit(userIds[index], activityId, dtos[index]);
                    success.incrementAndGet();
                } catch (BusinessException e) {
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

        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未全部就位");
        start.countDown();
        assertTrue(end.await(60, TimeUnit.SECONDS), "秒杀下单未在 60s 内结束");
        pool.shutdown();

        assertTrue(unexpected.isEmpty(), () -> "出现非预期异常: " + unexpected);
        assertTrue(expectedFailures.stream().allMatch(m -> m != null && m.contains("售罄")),
                () -> "存在非售罄的失败: " + expectedFailures);
        assertEquals(activityStock, success.get(), "成功下单数必须等于活动库存");

        SeckillActivity activity = seckillActivityMapper.selectById(activityId);
        assertEquals(0, dbStock(activityId), "活动库存应扣到 0");
        assertEquals(0, seckillStockService.currentStock(activityId), "Redis 库存应扣到 0");

        Long orderCount = orderMapper.selectCount(
                new LambdaQueryWrapper<Order>().eq(Order::getActivityId, activityId));
        assertEquals(activityStock, orderCount.intValue(), "订单数应等于成功下单数");

        List<Order> orders = orderMapper.selectList(
                new LambdaQueryWrapper<Order>().eq(Order::getActivityId, activityId));
        for (Order order : orders) {
            assertEquals(SECKILL_PRICE, order.getPayAmount(), "订单金额应为秒杀价");
        }
        OrderItem item = orderItemMapper.selectOne(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orders.get(0).getId()));
        assertEquals(SECKILL_PRICE, item.getPrice(), "明细单价应为秒杀价");
        assertEquals(1, item.getQuantity());

        ProductStock stock = productStockMapper.selectById(stockId);
        assertEquals(PRODUCT_STOCK, stock.getAvailableStock(), "秒杀不占用 product_stock");
        assertEquals(0, stock.getLockedStock());
    }

    @Test
    void sameUserConcurrentSubmit_shouldCreateOneOrder() throws Exception {
        int threads = 10;
        Long activityId = createActivity(5, 1,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        long userId = IdWorker.getId();
        SeckillOrderDTO dto = dtoOf(createAddress(userId));

        AtomicInteger success = new AtomicInteger();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    seckillService.submit(userId, activityId, dto);
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

        start.countDown();
        assertTrue(end.await(60, TimeUnit.SECONDS), "同用户并发未在 60s 内结束");
        pool.shutdown();

        assertTrue(unexpected.isEmpty(), () -> "出现非预期异常: " + unexpected);
        assertEquals(1, success.get(), "同一用户只能成功一单");
        assertTrue(failures.stream().allMatch(m -> m != null && m.contains("请勿重复提交")),
                () -> "失败原因应为重复提交: " + failures);

        assertEquals(4, dbStock(activityId),
                "活动库存只能扣 1");
        assertEquals(4, seckillStockService.currentStock(activityId), "Redis 库存只能扣 1");
        Long orderCount = orderMapper.selectCount(
                new LambdaQueryWrapper<Order>().eq(Order::getActivityId, activityId));
        assertEquals(1, orderCount.intValue());
    }

    @Test
    void unavailableActivity_shouldRejectWithoutDeductingStock() {
        long userId = IdWorker.getId();
        SeckillOrderDTO dto = dtoOf(createAddress(userId));

        Long notStarted = createActivity(5, 1,
                LocalDateTime.now().plusMinutes(10), LocalDateTime.now().plusHours(1));
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> seckillService.submit(userId, notStarted, dto));
        assertTrue(e1.getMessage().contains("尚未开始"));
        assertEquals(5, dbStock(notStarted));

        Long ended = createActivity(5, 1,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().minusMinutes(1));
        BusinessException e2 = assertThrows(BusinessException.class,
                () -> seckillService.submit(userId, ended, dto));
        assertTrue(e2.getMessage().contains("已结束"));
        assertEquals(5, dbStock(ended));

        Long offline = createActivity(5, 0,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        assertThrows(NotFoundException.class, () -> seckillService.submit(userId, offline, dto));
        assertEquals(5, dbStock(offline));

        Long soldOut = createActivity(0, 1,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        BusinessException e3 = assertThrows(BusinessException.class,
                () -> seckillService.submit(userId, soldOut, dto));
        assertTrue(e3.getMessage().contains("售罄"));
        assertEquals(0, dbStock(soldOut));
    }

    @Test
    void submit_whenSkuOffShelf_shouldRollbackRedisReservation() {
        long userId = IdWorker.getId();
        Long activityId = createActivity(5, 1,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));

        ProductSku offShelf = new ProductSku();
        offShelf.setId(skuId);
        offShelf.setStatus(0);
        productSkuMapper.updateById(offShelf);
        try {
            assertThrows(BusinessException.class,
                    () -> seckillService.submit(userId, activityId, dtoOf(createAddress(userId))));
            assertEquals(5, seckillStockService.currentStock(activityId),
                    "事务内失败应归还 Redis 预扣");
            assertEquals(5, dbStock(activityId));
        } finally {
            ProductSku onShelf = new ProductSku();
            onShelf.setId(skuId);
            onShelf.setStatus(1);
            productSkuMapper.updateById(onShelf);
        }
    }

    @Test
    void paySeckillOrder_shouldOnlyIncreaseSales() {
        long userId = IdWorker.getId();
        Long activityId = createActivity(5, 1,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        OrderSubmitVO vo = seckillService.submit(userId, activityId, dtoOf(createAddress(userId)));

        Integer salesBefore = productSkuMapper.selectById(skuId).getSales();
        orderService.pay(userId, vo.getOrderId());

        assertEquals(1, orderMapper.selectById(vo.getOrderId()).getStatus());
        ProductStock stock = productStockMapper.selectById(stockId);
        assertEquals(PRODUCT_STOCK, stock.getAvailableStock(), "秒杀支付不应释放商品库存");
        assertEquals(0, stock.getLockedStock(), "秒杀支付不应操作 locked_stock");

        int salesAfter = productSkuMapper.selectById(skuId).getSales();
        assertEquals(salesBefore == null ? 1 : salesBefore + 1, salesAfter, "支付应累加销量");
        assertEquals(4, dbStock(activityId),
                "支付不影响活动库存");
        assertEquals(4, seckillStockService.currentStock(activityId), "支付不影响 Redis 库存");
    }

    @Test
    void timeoutCancelSeckillOrder_shouldRestoreActivityStockOnce() {
        long userId = IdWorker.getId();
        Long activityId = createActivity(5, 1,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        OrderSubmitVO vo = seckillService.submit(userId, activityId, dtoOf(createAddress(userId)));

        orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .eq(Order::getId, vo.getOrderId())
                .set(Order::getExpireTime, LocalDateTime.now().minusMinutes(1)));

        assertTrue(orderService.cancelExpiredOrder(vo.getOrderId()));
        assertFalse(orderService.cancelExpiredOrder(vo.getOrderId()), "重复执行应跳过");

        assertEquals(4, orderMapper.selectById(vo.getOrderId()).getStatus());
        assertEquals(5, dbStock(activityId),
                "活动库存应归还且只归还一次");
        assertEquals(5, seckillStockService.currentStock(activityId), "Redis 库存应归还且只归还一次");
        ProductStock stock = productStockMapper.selectById(stockId);
        assertEquals(PRODUCT_STOCK, stock.getAvailableStock());
        assertEquals(0, stock.getLockedStock());
    }

    @Test
    void userCancelSeckillOrder_shouldRestoreActivityStock() {
        long userId = IdWorker.getId();
        Long activityId = createActivity(5, 1,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        OrderSubmitVO vo = seckillService.submit(userId, activityId, dtoOf(createAddress(userId)));

        orderService.cancel(userId, vo.getOrderId());

        assertEquals(4, orderMapper.selectById(vo.getOrderId()).getStatus());
        assertEquals(5, dbStock(activityId));
        assertEquals(5, seckillStockService.currentStock(activityId), "Redis 库存应归还");
        ProductStock stock = productStockMapper.selectById(stockId);
        assertEquals(PRODUCT_STOCK, stock.getAvailableStock());
        assertEquals(0, stock.getLockedStock());
    }

    @Test
    void listAndDetail_shouldExposePhaseAndServerTime() {
        Long ongoing = createActivity(5, 1,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        Long upcoming = createActivity(5, 1,
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2));

        PageVO<SeckillActivityListItemVO> page = seckillService.list(1, 50, null);
        List<Long> ids = page.getRecords().stream().map(SeckillActivityListItemVO::getId).toList();
        assertTrue(ids.contains(ongoing));
        assertTrue(ids.contains(upcoming));

        SeckillActivityListItemVO ongoingItem = page.getRecords().stream()
                .filter(v -> v.getId().equals(ongoing))
                .findFirst().orElseThrow();
        assertEquals(1, ongoingItem.getPhase());
        assertEquals(SECKILL_PRICE, ongoingItem.getSeckillPrice());
        assertEquals(ORIGINAL_PRICE, ongoingItem.getOriginalPrice());

        SeckillActivityDetailVO detail = seckillService.detail(ongoing);
        assertEquals(1, detail.getPhase());
        assertEquals(1, detail.getPerUserLimit());
        assertNotNull(detail.getServerTime());
        assertEquals("秒杀测试商品", detail.getProductName());
    }

    @Test
    void list_shouldSearchByActivityOrProductName() {
        String productKeyword = "独有耳机" + IdWorker.getId();
        String activityKeyword = "独有活动" + IdWorker.getId();

        ProductSpu spu = new ProductSpu();
        spu.setId(IdWorker.getId());
        spu.setCategoryId(1L);
        spu.setName(productKeyword);
        spu.setCode("SEARCH-SPU-" + spu.getId());
        spu.setPriceMin(SECKILL_PRICE);
        spu.setStatus(1);
        productSpuMapper.insert(spu);

        ProductSku sku = new ProductSku();
        sku.setId(IdWorker.getId());
        sku.setSpuId(spu.getId());
        sku.setSkuCode("SEARCH-SKU-" + sku.getId());
        sku.setSpecDesc("默认规格");
        sku.setPrice(ORIGINAL_PRICE);
        sku.setStatus(1);
        productSkuMapper.insert(sku);

        Long activityId = createActivityFor(spu.getId(), sku.getId(), activityKeyword, 5, 1,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));

        assertTrue(idsOf(seckillService.list(1, 50, productKeyword)).contains(activityId),
                "应能按商品名搜到活动");
        assertTrue(idsOf(seckillService.list(1, 50, activityKeyword)).contains(activityId),
                "应能按活动名搜到活动");
        assertFalse(idsOf(seckillService.list(1, 50, "完全不匹配" + IdWorker.getId())).contains(activityId),
                "不匹配的关键字不应返回该活动");
    }

    @Test
    void list_shouldSearchByCategoryName() {
        String categoryName = "秒杀分类" + IdWorker.getId();
        Category category = new Category();
        category.setId(IdWorker.getId());
        category.setParentId(1L);
        category.setName(categoryName);
        category.setSort(0);
        category.setStatus(1);
        category.setCreateTime(LocalDateTime.now());
        category.setUpdateTime(LocalDateTime.now());
        categoryMapper.insert(category);

        ProductSpu spu = new ProductSpu();
        spu.setId(IdWorker.getId());
        spu.setCategoryId(category.getId());
        spu.setName("与分类名无关的商品");
        spu.setCode("CAT-SPU-" + spu.getId());
        spu.setPriceMin(SECKILL_PRICE);
        spu.setStatus(1);
        productSpuMapper.insert(spu);

        ProductSku sku = new ProductSku();
        sku.setId(IdWorker.getId());
        sku.setSpuId(spu.getId());
        sku.setSkuCode("CAT-SKU-" + sku.getId());
        sku.setSpecDesc("默认规格");
        sku.setPrice(ORIGINAL_PRICE);
        sku.setStatus(1);
        productSkuMapper.insert(sku);

        Long activityId = createActivityFor(spu.getId(), sku.getId(), "与分类名无关的活动", 5, 1,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));

        assertTrue(idsOf(seckillService.list(1, 50, categoryName)).contains(activityId),
                "应能按商品分类名搜到秒杀活动");
    }

    private List<Long> idsOf(PageVO<SeckillActivityListItemVO> page) {
        return page.getRecords().stream().map(SeckillActivityListItemVO::getId).toList();
    }

    @Test
    void asyncConsumer_shouldCreateOrderAndStoreResult() {
        long userId = IdWorker.getId();
        Long activityId = createActivity(5, 1,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        Long addressId = createAddress(userId);
        SeckillActivity activity = seckillActivityMapper.selectById(activityId);
        assertEquals(SeckillStockService.ReserveResult.RESERVED,
                seckillStockService.reserve(activity, userId));

        String token = IdWorker.getIdStr();
        seckillService.processQueuedOrder(new SeckillOrderMessage(token, userId, activityId,
                addressId, "秒杀测试", System.currentTimeMillis()));

        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>().eq(Order::getOrderToken, token));
        assertNotNull(order, "消费者应建单并写入 order_token");
        assertEquals(4, dbStock(activityId));
        assertEquals(4, seckillStockService.currentStock(activityId));

        SeckillOrderSubmitVO result = seckillService.orderResult(userId, token);
        assertEquals(SeckillOrderSubmitVO.SUCCESS, result.getStatus());
        assertEquals(order.getId(), result.getOrderId());
    }

    @Test
    void asyncConsumer_addressMissing_shouldReleaseReservation() {
        long userId = IdWorker.getId();
        Long activityId = createActivity(5, 1,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        SeckillActivity activity = seckillActivityMapper.selectById(activityId);
        assertEquals(SeckillStockService.ReserveResult.RESERVED,
                seckillStockService.reserve(activity, userId));

        String token = IdWorker.getIdStr();
        seckillService.processQueuedOrder(new SeckillOrderMessage(token, userId, activityId,
                IdWorker.getId(), null, System.currentTimeMillis()));

        assertNull(orderMapper.selectOne(
                        new LambdaQueryWrapper<Order>().eq(Order::getOrderToken, token)),
                "地址不存在时不应建单");
        assertEquals(5, seckillStockService.currentStock(activityId), "失败应归还 Redis 预扣");
        assertEquals(SeckillOrderSubmitVO.FAILED, seckillService.orderResult(userId, token).getStatus());
    }

    @Test
    void asyncConsumer_redelivery_shouldKeepSuccessAndNotDeductTwice() {
        long userId = IdWorker.getId();
        Long activityId = createActivity(5, 1,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        Long addressId = createAddress(userId);
        SeckillActivity activity = seckillActivityMapper.selectById(activityId);
        seckillStockService.reserve(activity, userId);

        String token = IdWorker.getIdStr();
        SeckillOrderMessage message = new SeckillOrderMessage(token, userId, activityId,
                addressId, null, System.currentTimeMillis());
        seckillService.processQueuedOrder(message);
        seckillService.processQueuedOrder(message);

        assertEquals(1, orderMapper.selectCount(
                new LambdaQueryWrapper<Order>().eq(Order::getActivityId, activityId)).intValue(),
                "重投不应重复建单");
        assertEquals(4, seckillStockService.currentStock(activityId), "重投不应二次扣减");
        assertEquals(SeckillOrderSubmitVO.SUCCESS, seckillService.orderResult(userId, token).getStatus(),
                "重投不应把成功覆盖成失败");
    }

    @Test
    void submitOrder_syncMode_shouldReturnSuccess() {
        long userId = IdWorker.getId();
        Long activityId = createActivity(5, 1,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));

        SeckillOrderSubmitVO result = seckillService.submitOrder(userId, activityId,
                dtoOf(createAddress(userId)));

        assertEquals(SeckillOrderSubmitVO.SUCCESS, result.getStatus());
        assertNotNull(result.getOrderId());
    }

    private int dbStock(Long activityId) {
        return bucketStockService.totalAvailable(activityId);
    }

    private Long createActivity(int stock, int status, LocalDateTime start, LocalDateTime end) {
        return createActivityFor(spuId, skuId, "秒杀测试活动", stock, status, start, end);
    }

    private Long createActivityFor(Long activitySpuId, Long activitySkuId, String name,
                                   int stock, int status, LocalDateTime start, LocalDateTime end) {
        SeckillActivity activity = new SeckillActivity();
        activity.setId(IdWorker.getId());
        activity.setSpuId(activitySpuId);
        activity.setSkuId(activitySkuId);
        activity.setName(name);
        activity.setSeckillPrice(SECKILL_PRICE);
        activity.setTotalStock(stock);
        activity.setAvailableStock(stock);
        activity.setPerUserLimit(1);
        activity.setStartTime(start);
        activity.setEndTime(end);
        activity.setStatus(status);
        seckillActivityMapper.insert(activity);
        createdActivityIds.add(activity.getId());
        seckillStockService.initialize(activity);
        return activity.getId();
    }

    private Long createAddress(long userId) {
        UserAddress address = new UserAddress();
        address.setId(IdWorker.getId());
        address.setUserId(userId);
        address.setReceiverName("秒杀测试用户");
        address.setReceiverPhone("13800000000");
        address.setProvince("北京市");
        address.setCity("北京市");
        address.setDistrict("海淀区");
        address.setDetail("测试路 1 号");
        address.setIsDefault(1);
        userAddressMapper.insert(address);
        return address.getId();
    }

    private SeckillOrderDTO dtoOf(Long addressId) {
        SeckillOrderDTO dto = new SeckillOrderDTO();
        dto.setAddressId(addressId);
        dto.setRemark("秒杀测试");
        return dto;
    }
}
