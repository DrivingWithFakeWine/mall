package com.dking.shop.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SKU 与规格值的关联（结构数据：改规格时物理删除重建，不做逻辑删除）。
 */
@Data
@TableName("product_sku_spec")
public class ProductSkuSpec {

    @TableId
    private Long id;

    private Long skuId;
    private Long specValueId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
