package com.dking.shop.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单明细。商品名称、规格、图片、单价均为下单时的快照。
 */
@Data
@TableName("order_item")
public class OrderItem {

    @TableId
    private Long id;

    private Long orderId;
    private String orderNo;
    private Long spuId;
    private Long skuId;
    private String productName;
    private String specDesc;
    private String image;
    private BigDecimal price;
    private Integer quantity;
    private BigDecimal totalAmount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
