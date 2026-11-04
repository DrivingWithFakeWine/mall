-- 2026-11-03 秒杀 MVP：已有部署（doro_mall / doro_mall_test）的增量迁移
-- 新库直接用 Shop/sql/schema.sql 即可，无需执行本文件。

ALTER TABLE `orders`
  ADD COLUMN `activity_id` BIGINT DEFAULT NULL COMMENT '秒杀活动id（普通订单为 NULL）' AFTER `merchant_id`,
  ADD UNIQUE KEY `uk_activity_user` (`activity_id`, `user_id`);

CREATE TABLE `seckill_activity` (
  `id`              BIGINT        NOT NULL COMMENT '主键（雪花ID）',
  `spu_id`          BIGINT        NOT NULL COMMENT '商品SPU id',
  `sku_id`          BIGINT        NOT NULL COMMENT '商品SKU id',
  `name`            VARCHAR(100)  NOT NULL COMMENT '活动名称（展示用）',
  `seckill_price`   DECIMAL(10,2) NOT NULL COMMENT '秒杀价',
  `total_stock`     INT           NOT NULL COMMENT '活动总库存',
  `available_stock` INT           NOT NULL COMMENT '可用库存（条件更新扣减）',
  `per_user_limit`  INT           NOT NULL DEFAULT 1 COMMENT '每人限购数量（MVP 固定 1）',
  `start_time`      DATETIME      NOT NULL COMMENT '活动开始时间',
  `end_time`        DATETIME      NOT NULL COMMENT '活动结束时间',
  `status`          TINYINT       NOT NULL DEFAULT 1 COMMENT '0下线 1上线',
  `create_time`     DATETIME      NOT NULL COMMENT '创建时间',
  `update_time`     DATETIME      NOT NULL COMMENT '更新时间',
  `deleted`         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删除 1已删除',
  PRIMARY KEY (`id`),
  KEY `idx_status_time` (`status`, `start_time`, `end_time`),
  KEY `idx_sku` (`sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀活动';
