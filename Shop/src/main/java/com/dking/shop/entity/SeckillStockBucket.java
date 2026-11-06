package com.dking.shop.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 秒杀库存桶：把单个活动库存拆成 N 行，降低条件更新的单行锁竞争。
 * 各桶 available_stock 之和才是活动可售库存。
 */
@Data
@TableName("seckill_stock_bucket")
public class SeckillStockBucket {

    private Long activityId;

    private Integer bucketNo;

    private Integer totalStock;

    private Integer availableStock;
}
