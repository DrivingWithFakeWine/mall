package com.dking.shop.service.impl;

import com.dking.shop.entity.SeckillActivity;
import com.dking.shop.mapper.SeckillStockBucketMapper;
import com.dking.shop.service.SeckillBucketStockService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
public class SeckillBucketStockServiceImpl implements SeckillBucketStockService {

    private static final int MAX_BUCKETS = 16;

    @Resource
    private SeckillStockBucketMapper bucketMapper;

    @Value("${shop.seckill.bucket.size:8}")
    private int configuredBuckets;

    @Override
    public void ensure(SeckillActivity activity) {
        if (activity == null || activity.getId() == null) {
            return;
        }
        if (bucketMapper.countByActivity(activity.getId()) > 0) {
            return;
        }
        int total = activity.getTotalStock() == null ? 0 : activity.getTotalStock();
        int available = activity.getAvailableStock() == null ? total : activity.getAvailableStock();
        bucketMapper.initialize(activity.getId(), total, available, buckets());
    }

    @Override
    public int deduct(Long activityId, Long userId) {
        int count = buckets();
        // 随机起始桶：雪花 id 的低位相关性会让 userId % N 聚集，随机能均匀打散行锁
        int start = ThreadLocalRandom.current().nextInt(count);
        for (int i = 0; i < count; i++) {
            int bucketNo = (start + i) % count;
            if (bucketMapper.deduct(activityId, bucketNo) == 1) {
                return bucketNo;
            }
        }
        return -1;
    }

    @Override
    public boolean restore(Long activityId, Integer bucketNo, int quantity) {
        if (quantity <= 0) {
            return true;
        }
        int count = buckets();
        int start = bucketNo == null ? 0 : Math.floorMod(bucketNo, count);
        for (int i = 0; i < count; i++) {
            int candidate = (start + i) % count;
            if (bucketMapper.restore(activityId, candidate, quantity) == 1) {
                return true;
            }
        }
        log.warn("秒杀库存回补失败：所有桶都无法容纳, activityId={}, bucket={}, quantity={}",
                activityId, bucketNo, quantity);
        return false;
    }

    @Override
    public int totalAvailable(Long activityId) {
        Integer total = bucketMapper.totalAvailable(activityId);
        return total == null ? 0 : total;
    }

    @Override
    public Map<Long, Integer> totalAvailable(List<Long> activityIds) {
        if (activityIds == null || activityIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> result = new HashMap<>();
        for (Map<String, Object> row : bucketMapper.sumByActivityIds(activityIds)) {
            Object id = valueOf(row, "activityId");
            Object total = valueOf(row, "total");
            if (id instanceof Number number) {
                result.put(number.longValue(), total instanceof Number t ? t.intValue() : 0);
            }
        }
        return result;
    }

    @Override
    public void refreshActivityStock(Long activityId) {
        bucketMapper.refreshActivityStock(activityId);
    }

    private Object valueOf(Map<String, Object> row, String key) {
        Object direct = row.get(key);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private int buckets() {
        return Math.min(Math.max(configuredBuckets, 1), MAX_BUCKETS);
    }
}
