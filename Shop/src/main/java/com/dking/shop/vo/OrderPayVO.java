package com.dking.shop.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrderPayVO {

    private Long orderId;
    private String orderNo;
    private BigDecimal payAmount;

    /** 支付后状态，正常为 1 已支付 */
    private Integer status;

    private LocalDateTime payTime;
}
