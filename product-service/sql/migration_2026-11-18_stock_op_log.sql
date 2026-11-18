-- 库存操作台账（幂等）
-- 背景：库存写入从单体迁到商品服务后，调用变成跨进程，会重试。
-- 用 (order_no, sku_id, op) 唯一键保证同一次操作的重复请求只生效一次。
--
-- 关于 *_before / *_after：它们由「操作前读一次库存 + 按 op 推算」得到，
-- 并发场景下是「本事务看到的近似值」，**不能用于对账**，只作为排查线索。
-- 真正的对账要基于：台账 ops + 当前库存 + 业务单据 三者推算。
CREATE TABLE IF NOT EXISTS `stock_op_log` (
  `id`               BIGINT      NOT NULL COMMENT '主键（雪花ID）',
  `order_no`         VARCHAR(32) CHARACTER SET ascii NOT NULL COMMENT '订单号（幂等键）',
  `sku_id`           BIGINT      NOT NULL COMMENT 'SKU id',
  `op`               VARCHAR(16) CHARACTER SET ascii NOT NULL
                     COMMENT 'RESERVE / RELEASE / CONFIRM / SALES',
  `quantity`         INT         NOT NULL COMMENT '本次操作数量（恒为正，方向由 op 决定）',
  `available_before` INT         NULL     COMMENT '操作前可用库存（排查用，并发下为近似值）',
  `available_after`  INT         NULL     COMMENT '操作后可用库存（排查用，并发下为近似值）',
  `locked_before`    INT         NULL     COMMENT '操作前锁定库存（排查用，并发下为近似值）',
  `locked_after`     INT         NULL     COMMENT '操作后锁定库存（排查用，并发下为近似值）',
  `create_time`      DATETIME(3) NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_sku_op` (`order_no`, `sku_id`, `op`),
  KEY `idx_create_time` (`create_time`),
  CONSTRAINT `ck_op`       CHECK (`op` IN ('RESERVE','RELEASE','CONFIRM','SALES')),
  CONSTRAINT `ck_quantity` CHECK (`quantity` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='库存操作台账（幂等）；*_before/after 仅为排查线索，并发下为近似值，不可用于对账';
