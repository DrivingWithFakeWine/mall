package com.dking.shop.service;

import com.dking.shop.entity.SeckillActivity;
import com.dking.shop.mapper.SeckillActivityMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
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

/** Redis 预扣闸门的语义测试；测试库为 Redis DB 1。 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class SeckillStockServiceTest {

    @Resource
    private SeckillStockService seckillStockService;

    @Resource
    private SeckillActivityMapper seckillActivityMapper;

    @Resource
    private StringRedisTemplate redis;

    @Value("${shop.seckill.redis.prefix:doro:seckill}")
    private String redisPrefix;

    private final List<Long> activityIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        String prefix = redisPrefix.endsWith(":")
                ? redisPrefix.substring(0, redisPrefix.length() - 1)
                : redisPrefix;
        for (Long id : activityIds) {
            redis.delete(List.of(prefix + ":stock:" + id, prefix + ":users:" + id));
        }
        activityIds.clear();
    }

    @Test
    void reserve_shouldDecrementStockAndRejectDuplicate() {
        SeckillActivity activity = createActivity(5,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        seckillStockService.initialize(activity);

        assertEquals(SeckillStockService.ReserveResult.RESERVED,
                seckillStockService.reserve(activity, 1001L));
        assertEquals(4, seckillStockService.currentStock(activity.getId()).intValue());

        assertEquals(SeckillStockService.ReserveResult.DUPLICATE,
                seckillStockService.reserve(activity, 1001L));
        assertEquals(4, seckillStockService.currentStock(activity.getId()).intValue(),
                "重复用户不应继续扣库存");
    }

    @Test
    void reserve_shouldRejectSoldOutAndTimeWindow() {
        SeckillActivity soldOut = createActivity(0,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        seckillStockService.initialize(soldOut);
        assertEquals(SeckillStockService.ReserveResult.SOLD_OUT,
                seckillStockService.reserve(soldOut, 2001L));

        SeckillActivity notStarted = createActivity(5,
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2));
        seckillStockService.initialize(notStarted);
        assertEquals(SeckillStockService.ReserveResult.NOT_STARTED,
                seckillStockService.reserve(notStarted, 2002L));

        SeckillActivity ended = createActivity(5,
                LocalDateTime.now().minusHours(2), LocalDateTime.now().minusMinutes(1));
        seckillStockService.initialize(ended);
        assertEquals(SeckillStockService.ReserveResult.ENDED,
                seckillStockService.reserve(ended, 2003L));
    }

    @Test
    void reserve_shouldLazyInitializeFromDb() {
        SeckillActivity activity = createActivity(3,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));

        // 不显式初始化，Lua 返回未初始化后由服务从 DB 补 key 并重试
        assertEquals(SeckillStockService.ReserveResult.RESERVED,
                seckillStockService.reserve(activity, 3001L));
        assertEquals(2, seckillStockService.currentStock(activity.getId()).intValue());
    }

    @Test
    void release_removeMarker_allowsRetry() {
        SeckillActivity activity = createActivity(5,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        seckillStockService.initialize(activity);
        seckillStockService.reserve(activity, 4001L);

        seckillStockService.release(activity.getId(), 4001L, false);

        assertEquals(5, seckillStockService.currentStock(activity.getId()).intValue());
        assertEquals(SeckillStockService.ReserveResult.RESERVED,
                seckillStockService.reserve(activity, 4001L), "移除占位后应允许重试");
    }

    @Test
    void release_markUser_keepsLimit() {
        SeckillActivity activity = createActivity(5,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        seckillStockService.initialize(activity);
        seckillStockService.reserve(activity, 5001L);

        seckillStockService.release(activity.getId(), 5001L, true);

        assertEquals(5, seckillStockService.currentStock(activity.getId()).intValue());
        assertEquals(SeckillStockService.ReserveResult.DUPLICATE,
                seckillStockService.reserve(activity, 5001L), "保留占位后不可再次参与");
    }

    @Test
    void restoreStock_shouldIncreaseButKeepUserMarker() {
        SeckillActivity activity = createActivity(5,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        seckillStockService.initialize(activity);
        seckillStockService.reserve(activity, 6001L);

        seckillStockService.restoreStock(activity.getId(), 1);

        assertEquals(5, seckillStockService.currentStock(activity.getId()).intValue());
        assertEquals(SeckillStockService.ReserveResult.DUPLICATE,
                seckillStockService.reserve(activity, 6001L), "取消后不可复购");
    }

    @Test
    void initialize_shouldNotOverwriteExistingStock() {
        SeckillActivity activity = createActivity(5,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        seckillStockService.initialize(activity);

        SeckillActivity changed = new SeckillActivity();
        changed.setId(activity.getId());
        changed.setAvailableStock(2);
        seckillActivityMapper.updateById(changed);

        seckillStockService.initialize(activity);
        assertEquals(5, seckillStockService.currentStock(activity.getId()).intValue(),
                "初始化只补缺失 key，不覆盖已有库存");
    }

    private SeckillActivity createActivity(int stock, LocalDateTime start, LocalDateTime end) {
        SeckillActivity activity = new SeckillActivity();
        activity.setId(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());
        activity.setSpuId(1L);
        activity.setSkuId(1L);
        activity.setName("Redis 预扣测试活动");
        activity.setSeckillPrice(new BigDecimal("9.90"));
        activity.setTotalStock(stock);
        activity.setAvailableStock(stock);
        activity.setPerUserLimit(1);
        activity.setStartTime(start);
        activity.setEndTime(end);
        activity.setStatus(1);
        seckillActivityMapper.insert(activity);
        activityIds.add(activity.getId());
        return activity;
    }
}
