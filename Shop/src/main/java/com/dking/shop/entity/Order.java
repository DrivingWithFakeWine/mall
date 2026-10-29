package com.dking.shop.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("orders")
public class Order {

    @TableId
    private Long id;

    /** 展示用订单号，全局唯一 */
    private String orderNo;

    private Long userId;

    /** 单商家阶段固定 0，多商家时用于拆单 */
    private Long merchantId;

    private BigDecimal totalAmount;
    private BigDecimal payAmount;

    /** 0 待支付 / 1 已支付 / 2 已发货 / 3 已完成 / 4 已取消 */
    private Integer status;

    private LocalDateTime expireTime;
    private LocalDateTime payTime;
    private LocalDateTime cancelTime;
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
