package com.dking.shop.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("product_spu")
public class ProductSpu {

    @TableId
    private Long id;

    private Long categoryId;
    private String name;
    private String code;
    private String subtitle;

    /** 大字段：列表查询切勿查它 */
    private String detail;

    private String mainImage;
    private BigDecimal priceMin;
    private Integer status;
    private Integer sales;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
