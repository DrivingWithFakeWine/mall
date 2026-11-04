package com.dking.shop.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀活动。库存独立于 product_stock，下单只扣 available_stock。
 */
@Data
@TableName("seckill_activity")
public class SeckillActivity {

    @TableId
    private Long id;

    private Long spuId;
    private Long skuId;

    /** 活动名称（展示用） */
    private String name;

    private BigDecimal seckillPrice;

    private Integer totalStock;

    /** 可用库存：条件更新扣减 */
    private Integer availableStock;

    /** 每人限购数量，MVP 固定 1 */
    private Integer perUserLimit;

    private LocalDateTime startTime;
    private LocalDateTime endTime;

    /** 0 下线 / 1 上线 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
