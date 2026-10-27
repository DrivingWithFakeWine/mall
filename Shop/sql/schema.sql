CREATE TABLE `category` (
                            `id`          BIGINT      NOT NULL COMMENT '主键（雪花ID）',
                            `parent_id`   BIGINT      NOT NULL DEFAULT 0 COMMENT '父分类ID，一级分类为0',
                            `name`        VARCHAR(50) NOT NULL COMMENT '分类名',
                            `sort`        INT         NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
                            `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
                            `create_time` DATETIME    NOT NULL COMMENT '创建时间',
                            `update_time` DATETIME    NOT NULL COMMENT '更新时间',
                            `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删除 1已删除',
                            PRIMARY KEY (`id`),
                            KEY `idx_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类';

CREATE TABLE `product_spu` (
                               `id`          BIGINT        NOT NULL COMMENT '主键（雪花ID）',
                               `category_id` BIGINT        NOT NULL COMMENT '所属二级分类ID',
                               `name`        VARCHAR(100)  NOT NULL COMMENT '商品名称',
                               `code`        VARCHAR(50)   NOT NULL COMMENT 'SPU编码',
                               `subtitle`    VARCHAR(200)  DEFAULT NULL COMMENT '副标题/卖点',
                               `detail`      TEXT          COMMENT '商品详情（大字段，列表查询勿查）',
                               `main_image`  VARCHAR(255)  DEFAULT NULL COMMENT '主图URL（冗余，来源 product_image.is_main）',
                               `price_min`   DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '最低售价（冗余，来源 product_sku.price）',
                               `status`      TINYINT       NOT NULL DEFAULT 0 COMMENT '0草稿 1上架 2下架',
                               `sales`       INT           NOT NULL DEFAULT 0 COMMENT '累计销量',
                               `create_time` DATETIME      NOT NULL COMMENT '创建时间',
                               `update_time` DATETIME      NOT NULL COMMENT '更新时间',
                               `deleted`     TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删除 1已删除',
                               PRIMARY KEY (`id`),
                               UNIQUE KEY `uk_code` (`code`),
                               KEY `idx_category_status` (`category_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品SPU';

CREATE TABLE `product_sku` (
                               `id`             BIGINT        NOT NULL COMMENT '主键（雪花ID）',
                               `spu_id`         BIGINT        NOT NULL COMMENT '所属SPU的id',
                               `sku_code`       VARCHAR(50)   NOT NULL COMMENT 'SKU编码（SPU内唯一）',
                               `spec_desc`      VARCHAR(100)  NOT NULL COMMENT '规格描述，如"红色/128G"（冗余展示，写入时生成）',
                               `image`          VARCHAR(255)  DEFAULT NULL COMMENT '该规格主图',
                               `price`          DECIMAL(10,2) NOT NULL COMMENT '售价',
                               `original_price` DECIMAL(10,2) DEFAULT NULL COMMENT '原价/划线价',
                               `status`         TINYINT       NOT NULL DEFAULT 0 COMMENT '1上架 0下架',
                               `sales`          INT           NOT NULL DEFAULT 0 COMMENT '累计销量',
                               `publish_time`   DATETIME      DEFAULT NULL COMMENT '上架时间',
                               `create_time`    DATETIME      NOT NULL COMMENT '创建时间',
                               `update_time`    DATETIME      NOT NULL COMMENT '更新时间',
                               `deleted`        TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
                               PRIMARY KEY (`id`),
                               UNIQUE KEY `uk_spu_code` (`spu_id`, `sku_code`),
                               KEY `idx_spu_status` (`spu_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品SKU';

CREATE TABLE `product_stock` (
                                 `id`              BIGINT   NOT NULL COMMENT '主键（雪花ID）',
                                 `sku_id`          BIGINT   NOT NULL COMMENT '所属SKU的id',
                                 `available_stock` INT      NOT NULL DEFAULT 0 COMMENT '可用库存',
                                 `locked_stock`    INT      NOT NULL DEFAULT 0 COMMENT '锁定库存（下单未支付）',
                                 `version`         INT      NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
                                 `create_time`     DATETIME NOT NULL COMMENT '创建时间',
                                 `update_time`     DATETIME NOT NULL COMMENT '更新时间',
                                 `deleted`         TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除',
                                 PRIMARY KEY (`id`),
                                 UNIQUE KEY `uk_sku` (`sku_id`),
                                 CONSTRAINT `chk_stock_non_negative` CHECK (`available_stock` >= 0 AND `locked_stock` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SKU库存';

CREATE TABLE `product_image` (
                                 `id`          BIGINT       NOT NULL COMMENT '主键（雪花ID）',
                                 `spu_id`      BIGINT       NOT NULL COMMENT '所属SPU的id',
                                 `sku_id`      BIGINT       DEFAULT NULL COMMENT '所属SKU的id；NULL表示SPU通用图',
                                 `url`         VARCHAR(255) NOT NULL COMMENT '图片地址',
                                 `sort`        INT          NOT NULL DEFAULT 0 COMMENT '排序，越小越靠前',
                                 `is_main`     TINYINT      NOT NULL DEFAULT 0 COMMENT '1主图 0非主图（作用域内唯一）',
                                 `create_time` DATETIME     NOT NULL COMMENT '创建时间',
                                 `update_time` DATETIME     NOT NULL COMMENT '更新时间',
                                 `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
                                 PRIMARY KEY (`id`),
                                 KEY `idx_spu` (`spu_id`),
                                 KEY `idx_sku` (`sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品图片';

CREATE TABLE `product_spec` (
                                `id`          BIGINT      NOT NULL COMMENT '主键（雪花ID）',
                                `spu_id`      BIGINT      NOT NULL COMMENT '所属SPU的id',
                                `name`        VARCHAR(30) NOT NULL COMMENT '规格项名称，如 颜色/容量',
                                `sort`        INT         NOT NULL DEFAULT 0 COMMENT '排序，越小越靠前',
                                `create_time` DATETIME    NOT NULL COMMENT '创建时间',
                                `update_time` DATETIME    NOT NULL COMMENT '更新时间',
                                `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
                                PRIMARY KEY (`id`),
                                KEY `idx_spu` (`spu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品规格项';

CREATE TABLE `product_spec_value` (
                                      `id`          BIGINT      NOT NULL COMMENT '主键（雪花ID）',
                                      `spec_id`     BIGINT      NOT NULL COMMENT '所属规格项的id',
                                      `value`       VARCHAR(50) NOT NULL COMMENT '规格值，如 红色/128G',
                                      `sort`        INT         NOT NULL DEFAULT 0 COMMENT '排序，越小越靠前',
                                      `create_time` DATETIME    NOT NULL COMMENT '创建时间',
                                      `update_time` DATETIME    NOT NULL COMMENT '更新时间',
                                      `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
                                      PRIMARY KEY (`id`),
                                      KEY `idx_spec` (`spec_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品规格值';

CREATE TABLE `product_sku_spec` (
                                    `id`            BIGINT   NOT NULL COMMENT '主键（雪花ID）',
                                    `sku_id`        BIGINT   NOT NULL COMMENT 'SKU的id',
                                    `spec_value_id` BIGINT   NOT NULL COMMENT '规格值的id',
                                    `create_time`   DATETIME NOT NULL COMMENT '创建时间',
                                    PRIMARY KEY (`id`),
                                    UNIQUE KEY `uk_sku_value` (`sku_id`, `spec_value_id`),
                                    KEY `idx_value` (`spec_value_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SKU与规格值关联';