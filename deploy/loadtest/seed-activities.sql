-- 压测活动：1 个争抢活动（库存 10）+ 5 个容量活动（库存 500，供多轮复用）
-- 用法：mysql -h <RDS> -u <user> -p doro_mall < seed-activities.sql
-- 活动 id 固定在 3900000000000000001 ~ 3900000000000000006，重复执行会重置库存和时间窗。

SET @spu_id = (SELECT id FROM product_spu WHERE status = 1 AND deleted = 0 ORDER BY id LIMIT 1);
SET @sku_id = (SELECT id FROM product_sku WHERE spu_id = @spu_id AND status = 1 AND deleted = 0 ORDER BY id LIMIT 1);

INSERT INTO seckill_activity
    (id, spu_id, sku_id, name, seckill_price, total_stock, available_stock, per_user_limit,
     start_time, end_time, status, create_time, update_time, deleted)
VALUES
    (3900000000000000001, @spu_id, @sku_id, '压测-秒杀争抢-10', 9.90, 10, 10, 1,
     DATE_SUB(NOW(), INTERVAL 1 MINUTE), DATE_ADD(NOW(), INTERVAL 6 HOUR), 1, NOW(), NOW(), 0),
    (3900000000000000002, @spu_id, @sku_id, '压测-秒杀容量-500-A', 9.90, 500, 500, 1,
     DATE_SUB(NOW(), INTERVAL 1 MINUTE), DATE_ADD(NOW(), INTERVAL 6 HOUR), 1, NOW(), NOW(), 0),
    (3900000000000000003, @spu_id, @sku_id, '压测-秒杀容量-500-B', 9.90, 500, 500, 1,
     DATE_SUB(NOW(), INTERVAL 1 MINUTE), DATE_ADD(NOW(), INTERVAL 6 HOUR), 1, NOW(), NOW(), 0),
    (3900000000000000004, @spu_id, @sku_id, '压测-秒杀容量-500-C', 9.90, 500, 500, 1,
     DATE_SUB(NOW(), INTERVAL 1 MINUTE), DATE_ADD(NOW(), INTERVAL 6 HOUR), 1, NOW(), NOW(), 0),
    (3900000000000000005, @spu_id, @sku_id, '压测-秒杀容量-500-D', 9.90, 500, 500, 1,
     DATE_SUB(NOW(), INTERVAL 1 MINUTE), DATE_ADD(NOW(), INTERVAL 6 HOUR), 1, NOW(), NOW(), 0),
    (3900000000000000006, @spu_id, @sku_id, '压测-秒杀容量-500-E', 9.90, 500, 500, 1,
     DATE_SUB(NOW(), INTERVAL 1 MINUTE), DATE_ADD(NOW(), INTERVAL 6 HOUR), 1, NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE
    spu_id = VALUES(spu_id),
    sku_id = VALUES(sku_id),
    total_stock = VALUES(total_stock),
    available_stock = VALUES(available_stock),
    start_time = VALUES(start_time),
    end_time = VALUES(end_time),
    status = 1,
    deleted = 0;

SELECT id, name, total_stock, available_stock, start_time, end_time
FROM seckill_activity
WHERE id BETWEEN 3900000000000000001 AND 3900000000000000006
ORDER BY id;
