-- 2026-11-06 秒杀库存分桶：活动库存拆成 N 行，降低单行锁竞争
-- 新库直接用 Shop/sql/schema.sql 即可，无需执行本文件。
-- 桶数据由应用惰性初始化（启动对账任务 / 首次下单），无需手工回填。

CREATE TABLE `seckill_stock_bucket` (
  `activity_id`     BIGINT  NOT NULL COMMENT '秒杀活动id',
  `bucket_no`       TINYINT NOT NULL COMMENT '桶号 0..N-1',
  `total_stock`     INT     NOT NULL COMMENT '该桶总库存（回补上限）',
  `available_stock` INT     NOT NULL COMMENT '该桶可售库存',
  PRIMARY KEY (`activity_id`, `bucket_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀库存桶（各桶之和为活动可售库存）';

ALTER TABLE `orders`
  ADD COLUMN `stock_bucket` TINYINT DEFAULT NULL COMMENT '秒杀下单命中的库存桶号（取消按桶回补）' AFTER `order_token`;
