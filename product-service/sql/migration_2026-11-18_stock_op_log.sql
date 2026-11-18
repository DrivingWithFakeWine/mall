-- 库存操作台账（幂等）
-- 背景：库存写入从单体迁到商品服务后，调用变成跨进程，会重试。
-- 用 (order_no, sku_id, op) 唯一键保证同一次操作的重复请求只生效一次。
CREATE TABLE IF NOT EXISTS `stock_op_log` (
  `id`          BIGINT      NOT NULL COMMENT '主键（雪花ID）',
  `order_no`    VARCHAR(32) NOT NULL COMMENT '订单号（幂等键）',
  `sku_id`      BIGINT      NOT NULL COMMENT 'SKU id',
  `op`          VARCHAR(16) NOT NULL COMMENT 'RESERVE / RELEASE / CONFIRM / SALES',
  `quantity`    INT         NOT NULL COMMENT '本次操作数量',
  `create_time` DATETIME    NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_sku_op` (`order_no`, `sku_id`, `op`),
  KEY `idx_order_no` (`order_no`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存操作台账（幂等）';
