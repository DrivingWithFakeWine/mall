package com.dking.shop.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单收货地址快照：只在下单时写入一次，不可修改（故无 updateTime / deleted）。
 */
@Data
@TableName("order_address")
public class OrderAddress {

    @TableId
    private Long id;

    private Long orderId;
    private String receiverName;
    private String receiverPhone;
    private String province;
    private String city;
    private String district;
    private String detail;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
