package com.dking.marketing.service;

import com.dking.marketing.dto.OrderCancelledEvent;
import com.dking.marketing.dto.SeckillOrderFailedEvent;
import com.dking.marketing.entity.SeckillActivity;
import com.dking.marketing.mapper.SeckillActivityMapper;
import com.dking.marketing.mapper.SeckillStockBucketMapper;
import com.dking.marketing.service.impl.SeckillFeedbackListener;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 场景 B 的另一半：营销收到了交易发来的反馈事件后，要把库存还回去。
 *
 * <p>配合 Shop 侧的 {@code OrderCancelFeedbackTest} 一起看：
 * 那边证明「交易只发事件、不发就不回补」，这边证明「营销收到了就会回补」。
 * 中间丢消息的那一段，只能靠对账。</p>
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class SeckillFeedbackListenerTest {

    private static final long USER_ID = 9501L;
    private static final int STOCK = 5;

    @Resource
    private SeckillFeedbackListener listener;

    @Resource
    private SeckillStockService seckillStockService;

    @Resource
    private SeckillBucketStockService bucketStockService;

    @Resource
    private SeckillActivityMapper seckillActivityMapper;

    @Resource
    private SeckillStockBucketMapper seckillStockBucketMapper;

    @Resource
    private StringRedisTemplate redis;

    @Resource
    private JsonMapper jsonMapper;

    @Value("${shop.seckill.redis.prefix:doro:seckill}")
    private String redisPrefix;

    private final List<Long> activityIds = new ArrayList<>();
    private Long activityId;
    private int bucketNo;

    @BeforeEach
    void setUp() {
        SeckillActivity activity = new SeckillActivity();
        activity.setId(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());
        activity.setSpuId(6600000000000000001L);
        activity.setSkuId(6600000000000000002L);
        activity.setName("反馈事件测试活动");
        activity.setSeckillPrice(new BigDecimal("9.90"));
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

        // 模拟一次已扣减的下单：Redis 预扣 + 数据库桶各减 1
        seckillStockService.reserve(activity, USER_ID);
        bucketNo = bucketStockService.deduct(activityId, USER_ID);
    }

    @AfterEach
    void cleanUp() {
        String prefix = redisPrefix.endsWith(":") ? redisPrefix.substring(0, redisPrefix.length() - 1) : redisPrefix;
        for (Long id : activityIds) {
            redis.delete(List.of(prefix + ":stock:" + id, prefix + ":users:" + id));
            seckillStockBucketMapper.deleteByActivity(id);
            seckillActivityMapper.deleteById(id);
        }
        activityIds.clear();
    }

    @Test
    void orderCancelled_shouldRestoreBucketAndRedis() {
        assertEquals(STOCK - 1, bucketStockService.totalAvailable(activityId), "前置：桶已扣 1");
        assertEquals(STOCK - 1, seckillStockService.currentStock(activityId).intValue(), "前置：Redis 已扣 1");

        String payload = jsonMapper.writeValueAsString(
                new OrderCancelledEvent("SK-feedback-1", activityId, bucketNo, 1));
        listener.onOrderCancelled(payload);

        assertEquals(STOCK, bucketStockService.totalAvailable(activityId), "取消后桶应回补");
        assertEquals(STOCK, seckillStockService.currentStock(activityId).intValue(), "取消后 Redis 应回补");
    }

    @Test
    void orderFailed_shouldRestoreBucketAndReleaseGate() {
        String payload = jsonMapper.writeValueAsString(
                new SeckillOrderFailedEvent("token-feedback-1", USER_ID, activityId, bucketNo, "地址不存在"));
        listener.onOrderFailed(payload);

        assertEquals(STOCK, bucketStockService.totalAvailable(activityId), "建单失败桶应回补");
        assertEquals(STOCK, seckillStockService.currentStock(activityId).intValue(), "建单失败 Redis 应释放");

        // 用户占位被清掉，可以重试
        assertEquals(SeckillStockService.ReserveResult.RESERVED,
                seckillStockService.reserve(seckillActivityMapper.selectById(activityId), USER_ID),
                "失败后同一用户应能重新下单");
    }

    @Test
    void duplicateCancelEvent_shouldNotExceedBucketTotal() {
        String payload = jsonMapper.writeValueAsString(
                new OrderCancelledEvent("SK-feedback-2", activityId, bucketNo, 1));

        listener.onOrderCancelled(payload);
        listener.onOrderCancelled(payload);   // 重复投递

        assertEquals(STOCK, bucketStockService.totalAvailable(activityId),
                "重复回补不能突破桶总量（available + n <= total 兜底）");
    }
}
