package com.dking.shop.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dking.shop.entity.ProductSku;
import com.dking.shop.entity.ProductSpu;
import com.dking.shop.entity.ProductStock;
import com.dking.shop.entity.SeckillActivity;
import com.dking.shop.exception.NotFoundException;
import com.dking.shop.mapper.ProductSkuMapper;
import com.dking.shop.mapper.ProductSpuMapper;
import com.dking.shop.mapper.ProductStockMapper;
import com.dking.shop.mapper.SeckillActivityMapper;
import com.dking.shop.service.support.SeckillActivityCache;
import com.dking.shop.vo.SeckillActivityDetailVO;
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

/** 活动详情缓存：元数据缓存、库存实时、空值缓存、Redis key 缺失回退 DB。 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class SeckillActivityDetailCacheTest {

    @Resource
    private SeckillService seckillService;

    @Resource
    private SeckillStockService seckillStockService;

    @Resource
    private SeckillActivityCache seckillActivityCache;

    @Resource
    private SeckillActivityMapper seckillActivityMapper;

    @Resource
    private ProductSpuMapper productSpuMapper;

    @Resource
    private ProductSkuMapper productSkuMapper;

    @Resource
    private ProductStockMapper productStockMapper;

    @Resource
    private StringRedisTemplate redis;

    @Value("${shop.seckill.redis.prefix:doro:seckill}")
    private String redisPrefix;

    private Long spuId;
    private Long skuId;
    private final List<Long> activityIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ProductSpu spu = new ProductSpu();
        spu.setId(IdWorker.getId());
        spu.setCategoryId(1L);
        spu.setName("详情缓存测试商品");
        spu.setCode("CACHE-SPU-" + spu.getId());
        spu.setMainImage("/images/cache.png");
        spu.setPriceMin(new BigDecimal("19.90"));
        spu.setStatus(1);
        productSpuMapper.insert(spu);
        spuId = spu.getId();

        ProductSku sku = new ProductSku();
        sku.setId(IdWorker.getId());
        sku.setSpuId(spuId);
        sku.setSkuCode("CACHE-SKU-" + sku.getId());
        sku.setSpecDesc("默认规格");
        sku.setPrice(new BigDecimal("19.90"));
        sku.setStatus(1);
        productSkuMapper.insert(sku);
        skuId = sku.getId();

        ProductStock stock = new ProductStock();
        stock.setId(IdWorker.getId());
        stock.setSkuId(skuId);
        stock.setAvailableStock(10);
        stock.setLockedStock(0);
        productStockMapper.insert(stock);
    }

    @AfterEach
    void cleanUp() {
        String prefix = redisPrefix.endsWith(":")
                ? redisPrefix.substring(0, redisPrefix.length() - 1)
                : redisPrefix;
        for (Long id : activityIds) {
            redis.delete(List.of(
                    prefix + ":activity:" + id,
                    prefix + ":stock:" + id,
                    prefix + ":users:" + id));
        }
        activityIds.clear();
    }

    @Test
    void detail_shouldCacheMetadataUntilEvicted() {
        Long activityId = createActivity("缓存前活动名", 5);
        assertEquals("缓存前活动名", seckillService.detail(activityId).getName());

        SeckillActivity rename = new SeckillActivity();
        rename.setId(activityId);
        rename.setName("缓存后活动名");
        seckillActivityMapper.updateById(rename);

        assertEquals("缓存前活动名", seckillService.detail(activityId).getName(),
                "TTL 内应命中缓存");

        seckillActivityCache.evict(activityId);
        assertEquals("缓存后活动名", seckillService.detail(activityId).getName(),
                "失效后应读到新值");
    }

    @Test
    void detail_shouldUseLiveRedisStock() {
        Long activityId = createActivity("实时库存活动", 5);
        SeckillActivity activity = seckillActivityMapper.selectById(activityId);
        seckillStockService.initialize(activity);
        seckillStockService.reserve(activity, 9001L);

        SeckillActivityDetailVO detail = seckillService.detail(activityId);
        assertEquals(4, detail.getAvailableStock(), "详情库存应读 Redis，而不是快照/DB");
        assertEquals(5, seckillActivityMapper.selectById(activityId).getAvailableStock(),
                "DB 库存尚未变化，证明读的是 Redis");
    }

    @Test
    void detail_shouldFallbackToDbWhenRedisStockMissing() {
        Long activityId = createActivity("无 Redis 库存 key", 5);
        SeckillActivityDetailVO detail = seckillService.detail(activityId);
        assertEquals(5, detail.getAvailableStock(), "Redis key 缺失时回退 DB 库存");
    }

    @Test
    void detail_shouldCacheMissingActivity() {
        Long missingId = IdWorker.getId();
        assertThrows(NotFoundException.class, () -> seckillService.detail(missingId));

        String prefix = redisPrefix.endsWith(":")
                ? redisPrefix.substring(0, redisPrefix.length() - 1)
                : redisPrefix;
        String key = prefix + ":activity:" + missingId;
        assertEquals("__NULL__", redis.opsForValue().get(key));
        assertNotNull(redis.getExpire(key), "空值标记应有 TTL");
        assertThrows(NotFoundException.class, () -> seckillService.detail(missingId));
        redis.delete(key);
    }

    private Long createActivity(String name, int stock) {
        SeckillActivity activity = new SeckillActivity();
        activity.setId(IdWorker.getId());
        activity.setSpuId(spuId);
        activity.setSkuId(skuId);
        activity.setName(name);
        activity.setSeckillPrice(new BigDecimal("9.90"));
        activity.setTotalStock(stock);
        activity.setAvailableStock(stock);
        activity.setPerUserLimit(1);
        activity.setStartTime(LocalDateTime.now().minusMinutes(1));
        activity.setEndTime(LocalDateTime.now().plusHours(1));
        activity.setStatus(1);
        seckillActivityMapper.insert(activity);
        activityIds.add(activity.getId());
        return activity.getId();
    }
}
