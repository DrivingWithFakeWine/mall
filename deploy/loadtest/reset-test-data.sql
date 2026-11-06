-- 重置压测数据：清掉压测用户（用户名 lt 开头）的订单，并恢复压测活动库存。
-- 用法：mysql -h <RDS> -u <user> -p doro_mall < reset-test-data.sql

CREATE TEMPORARY TABLE tmp_lt_order_ids AS
SELECT id FROM orders
WHERE user_id IN (SELECT user_id FROM doro_user WHERE user_name LIKE 'lt%');

DELETE FROM order_item WHERE order_id IN (SELECT id FROM tmp_lt_order_ids);
DELETE FROM order_address WHERE order_id IN (SELECT id FROM tmp_lt_order_ids);
DELETE FROM orders WHERE id IN (SELECT id FROM tmp_lt_order_ids);
DROP TEMPORARY TABLE tmp_lt_order_ids;

UPDATE seckill_activity
SET available_stock = total_stock,
    update_time = NOW()
WHERE id BETWEEN 3900000000000000001 AND 3900000000000000009;

-- 删除库存桶，让应用按恢复后的库存重新初始化
DELETE FROM seckill_stock_bucket
WHERE activity_id BETWEEN 3900000000000000001 AND 3900000000000000009;

-- Redis 不持久化：重置后重启 redis，让库存 key 与用户占位按 DB 重新初始化。
-- 复用同一活动 id 时如果不重启，Redis 会保留已扣减库存和用户占位，闸门会全部拒绝。

SELECT '剩余压测订单' AS metric, COUNT(*) AS value
FROM orders
WHERE user_id IN (SELECT user_id FROM doro_user WHERE user_name LIKE 'lt%');
