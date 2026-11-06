package com.dking.shop.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dking.shop.entity.SeckillActivity;
import com.dking.shop.mapper.SeckillActivityMapper;
import com.dking.shop.service.SeckillBucketStockService;
import com.dking.shop.service.SeckillStockService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀库存初始化与对账：只补缺失 key，不覆盖已有值；差异只告警，DB 为最终真相。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "shop.seckill.redis", name = "task-enabled",
        havingValue = "true", matchIfMissing = true)
public class SeckillStockTask {

    private static final Integer ACTIVITY_STATUS_ONLINE = 1;

    @Resource
    private SeckillActivityMapper seckillActivityMapper;

    @Resource
    private SeckillStockService seckillStockService;

    @Resource
    private SeckillBucketStockService bucketStockService;

    @Scheduled(fixedDelayString = "${shop.seckill.redis.reconcile-interval-ms:300000}",
            initialDelayString = "${shop.seckill.redis.reconcile-initial-delay-ms:30000}")
    public void reconcile() {
        List<SeckillActivity> activities;
        try {
            activities = seckillActivityMapper.selectList(
                    new LambdaQueryWrapper<SeckillActivity>()
                            .eq(SeckillActivity::getStatus, ACTIVITY_STATUS_ONLINE)
                            .gt(SeckillActivity::getEndTime, LocalDateTime.now()));
        } catch (RuntimeException e) {
            log.warn("秒杀库存对账查询活动失败: {}", e.getMessage());
            return;
        }

        int initialized = 0;
        int mismatched = 0;
        for (SeckillActivity activity : activities) {
            try {
                bucketStockService.ensure(activity);
                int dbStock = bucketStockService.totalAvailable(activity.getId());
                Integer before = seckillStockService.currentStock(activity.getId());
                seckillStockService.initialize(activity);
                // 活动行的 available_stock 只是展示缓存，用桶汇总刷新
                bucketStockService.refreshActivityStock(activity.getId());
                if (before == null) {
                    initialized++;
                    continue;
                }
                if (!before.equals(dbStock)) {
                    mismatched++;
                    log.warn("秒杀库存与 DB 不一致, activityId={}, redis={}, db={}",
                            activity.getId(), before, dbStock);
                }
            } catch (RuntimeException e) {
                log.warn("秒杀库存对账跳过, activityId={}, reason={}", activity.getId(), e.getMessage());
                return;
            }
        }

        if (initialized > 0 || mismatched > 0) {
            log.info("秒杀库存对账完成, online={}, initialized={}, mismatched={}",
                    activities.size(), initialized, mismatched);
        }
    }
}
