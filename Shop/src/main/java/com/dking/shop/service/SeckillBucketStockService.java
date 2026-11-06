package com.dking.shop.service;

import com.dking.shop.entity.SeckillActivity;

import java.util.List;
import java.util.Map;

/**
 * 库存分桶：DB 侧把单活动库存拆成 N 行，降低条件更新的行锁竞争。
 * Redis 闸门仍然先行；只有拿到预扣的请求才会走到这里。
 */
public interface SeckillBucketStockService {

    /** 幂等初始化；已存在则跳过 */
    void ensure(SeckillActivity activity);

    /** 在某个桶扣 1 件，返回桶号；全部售罄返回 -1 */
    int deduct(Long activityId, Long userId);

    /** 回补 n 件；优先指定桶，放不下时探测其他桶。返回是否成功 */
    boolean restore(Long activityId, Integer bucketNo, int quantity);

    /** 活动可售总量（各桶之和） */
    int totalAvailable(Long activityId);

    /** 批量查询可售总量，用于列表页 */
    Map<Long, Integer> totalAvailable(List<Long> activityIds);

    /** 把桶汇总写回活动行，仅作展示缓存；由对账任务调用 */
    void refreshActivityStock(Long activityId);
}
