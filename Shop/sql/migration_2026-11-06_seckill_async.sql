-- 2026-11-06 秒杀异步下单：订单增加异步令牌
-- 新库直接用 Shop/sql/schema.sql 即可，无需执行本文件。

ALTER TABLE `orders`
  ADD COLUMN `order_token` VARCHAR(32) DEFAULT NULL COMMENT '秒杀异步下单令牌（结果查询/消息幂等）' AFTER `activity_id`,
  ADD UNIQUE KEY `uk_order_token` (`order_token`);
