package com.dking.marketing.service;

import com.dking.marketing.client.LocalProductClient;
import com.dking.marketing.dto.SeckillOrderDTO;
import com.dking.marketing.dto.SeckillOrderMessage;
import com.dking.marketing.entity.SeckillActivity;
import com.dking.marketing.exception.BusinessException;
import com.dking.marketing.exception.ServiceUnavailableException;
import com.dking.marketing.mapper.SeckillActivityMapper;
import com.dking.marketing.mapper.SeckillStockBucketMapper;
import com.dking.marketing.service.impl.RecordingSeckillOrderPublisher;
import com.dking.marketing.service.support.SeckillOrderResultStore;
import com.dking.marketing.vo.SeckillOrderSubmitVO;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 秒杀下单（营销侧）的流程测试。
 *
 * <p>营销只做三件事：Redis 闸门预扣、扣数据库桶、发建单消息。
 * 订单落库在交易服务，这里用 {@link RecordingSeckillOrderPublisher} 截获消息做断言。</p>
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class SeckillSubmitTest {

    private static final long USER_ID = 9001L;
    private static final int STOCK = 5;
    private static final BigDecimal PRICE = new BigDecimal("19.90");

    @Resource
    private SeckillService seckillService;

    @Resource
    private SeckillStockService seckillStockService;

    @Resource
    private SeckillBucketStockService bucketStockService;

    @Resource
    private SeckillOrderResultStore orderResultStore;

    @Resource
    private SeckillActivityMapper seckillActivityMapper;

    @Resource
    private SeckillStockBucketMapper seckillStockBucketMapper;

    @Resource
    private RecordingSeckillOrderPublisher publisher;

    @Resource
    private LocalProductClient productClient;

    @Resource
    private StringRedisTemplate redis;

    @Value("${shop.seckill.redis.prefix:doro:seckill}")
    private String redisPrefix;

    private final List<Long> activityIds = new ArrayList<>();
    private final List<String> tokens = new ArrayList<>();

    private Long skuId;
    private Long activityId;

    @BeforeEach
    void setUp() {
        productClient.clear();
        publisher.clear();
        skuId = 8800000000000000001L;
        productClient.register(skuId, 8700000000000000001L, "秒杀测试商品", PRICE, 100);

        SeckillActivity activity = new SeckillActivity();
        activity.setId(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());
        activity.setSpuId(8700000000000000001L);
        activity.setSkuId(skuId);
        activity.setName("秒杀下单测试活动");
        activity.setSeckillPrice(PRICE);
        activity.setTotalStock(STOCK);
        activity.setAvailableStock(STOCK);
        activity.setPerUserLimit(1);
        activity.setStartTime(LocalDateTime.now().minusMinutes(1));
        activity.setEndTime(LocalDateTime.now().plusHours(1));
        activity.setStatus(1);
        seckillActivityMapper.insert(activity);
        activityIds.add(activity.getId());
        activityId = activity.getId();

        seckillStockService.initialize(activity);
        bucketStockService.ensure(activity);
    }

    @AfterEach
    void cleanUp() {
        String prefix = trimColon(redisPrefix);
        for (Long id : activityIds) {
            redis.delete(List.of(prefix + ":stock:" + id, prefix + ":users:" + id));
            seckillStockBucketMapper.deleteByActivity(id);
            seckillActivityMapper.deleteById(id);
        }
        for (String token : tokens) {
            redis.delete(prefix + ":result:" + token);
        }
        activityIds.clear();
        tokens.clear();
        publisher.clear();
    }

    // ------------------------------------------------------------------
    // 正常路径
    // ------------------------------------------------------------------

    @Test
    void submit_shouldReserveGateDeductBucketAndPublishMessage() {
        SeckillOrderSubmitVO vo = seckillService.submitOrder(USER_ID, activityId, dto());
        tokens.add(vo.getToken());

        assertEquals(SeckillOrderSubmitVO.PENDING, vo.getStatus(), "同步返回 PENDING，由结果接口轮询");
        assertNotNull(vo.getToken());

        // 两个库存源同时扣减
        assertEquals(STOCK - 1, seckillStockService.currentStock(activityId).intValue(), "Redis 预扣应减 1");
        assertEquals(STOCK - 1, bucketStockService.totalAvailable(activityId), "数据库桶应减 1");

        // 消息必须带着建单所需的一切
        assertEquals(1, publisher.published().size());
        SeckillOrderMessage message = publisher.published().get(0);
        assertEquals(vo.getToken(), message.token());
        assertEquals(skuId, message.command().skuId());
        assertEquals(USER_ID, message.command().userId());
        assertEquals(activityId, message.command().activityId());
        assertEquals(PRICE, message.command().price(), "价格随消息走，消费方不回查");
        assertTrue(message.command().stockBucket() >= 0);

        // 结果缓存先占位
        SeckillOrderResultStore.StoredResult stored = orderResultStore.get(vo.getToken());
        assertEquals(SeckillOrderSubmitVO.PENDING, stored.status());
    }

    @Test
    void submit_duplicateUser_shouldBeRejectedWithoutTouchingStock() {
        seckillService.submitOrder(USER_ID, activityId, dto());

        BusinessException e = assertThrows(BusinessException.class,
                () -> seckillService.submitOrder(USER_ID, activityId, dto()));
        assertTrue(e.getMessage().contains("请勿重复提交"));
        assertEquals(STOCK - 1, bucketStockService.totalAvailable(activityId), "重复请求不应再扣桶");
        assertEquals(1, publisher.published().size(), "重复请求不应再发消息");
    }

    // ------------------------------------------------------------------
    // 失败回滚
    // ------------------------------------------------------------------

    @Test
    void submit_offShelfSku_shouldReleaseGateAndLeaveBucketUntouched() {
        productClient.setStatus(skuId, 0, 1);

        assertThrows(BusinessException.class,
                () -> seckillService.submitOrder(USER_ID, activityId, dto()));

        assertEquals(STOCK, seckillStockService.currentStock(activityId).intValue(), "Redis 预扣应回滚");
        assertEquals(STOCK, bucketStockService.totalAvailable(activityId), "桶没扣过，不应变化");
        assertEquals(0, publisher.published().size());
    }

    @Test
    void submit_publishFailure_shouldRollbackBucketGateAndResult() {
        publisher.setFailing(true);

        assertThrows(ServiceUnavailableException.class,
                () -> seckillService.submitOrder(USER_ID, activityId, dto()));

        assertEquals(STOCK, bucketStockService.totalAvailable(activityId), "入队失败桶要还回去");
        assertEquals(STOCK, seckillStockService.currentStock(activityId).intValue(), "入队失败 Redis 也要还");
        assertTrue(publisher.published().isEmpty());
    }

    // ------------------------------------------------------------------
    // 场景 A：消息发出去，但订单服务没收到
    // ------------------------------------------------------------------

    /**
     * 消息已经进了 MQ，但交易服务宕机/队列积压，一直没消费。
     *
     * <p>此时营销侧的状态是：Redis 预扣了、桶扣了、结果停在 PENDING。
     * 这个测试把「丢失模式的代价」固定下来：**库存在两个源上都被占住，
     * 只能等对账把「超时未建单」的预占还回来**——没有消费方，营销无法自愈。</p>
     */
    @Test
    void messagePublishedButNeverConsumed_shouldKeepStockDeductedUntilReconcile() {
        SeckillOrderSubmitVO vo = seckillService.submitOrder(USER_ID, activityId, dto());
        tokens.add(vo.getToken());

        // 消息在队列里，但没有任何消费者
        assertEquals(1, publisher.published().size());

        // 库存被占住：既不能卖给别人，也还没变成订单
        assertEquals(STOCK - 1, seckillStockService.currentStock(activityId).intValue());
        assertEquals(STOCK - 1, bucketStockService.totalAvailable(activityId));

        // 用户视角：一直 PENDING
        assertEquals(SeckillOrderSubmitVO.PENDING, orderResultStore.get(vo.getToken()).status());
    }

    // ------------------------------------------------------------------

    private SeckillOrderDTO dto() {
        SeckillOrderDTO dto = new SeckillOrderDTO();
        dto.setAddressId(1L);
        dto.setRemark("测试");
        return dto;
    }

    private String trimColon(String prefix) {
        return prefix.endsWith(":") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }
}
