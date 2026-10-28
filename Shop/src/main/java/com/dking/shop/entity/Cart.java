package com.dking.shop.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 购物车。表无 deleted 字段：购物车是临时数据，删除即物理删除，
 * 避免逻辑删除后 uk(user_id, sku_id) 残留导致重新加购冲突。
 */
@Data
@TableName("cart")
public class Cart {

    @TableId
    private Long id;

    private Long userId;
    private Long skuId;
    private Integer quantity;

    /** 1 勾选结算，0 取消勾选 */
    private Integer selected;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
