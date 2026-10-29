package com.dking.shop.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrderSubmitVO {

    private Long orderId;

    /** 展示用订单号 */
    private String orderNo;

    private BigDecimal payAmount;

    /** 0 待支付 / 1 已支付 / 2 已发货 / 3 已完成 / 4 已取消 */
    private Integer status;

    /** 支付截止时间 */
    private LocalDateTime expireTime;
}
