package com.dking.shop.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class OrderDetailVO {

    private Long id;
    private String orderNo;

    /** 0 待支付 / 1 已支付 / 2 已发货 / 3 已完成 / 4 已取消 */
    private Integer status;

    private BigDecimal totalAmount;
    private BigDecimal payAmount;
    private String remark;

    private LocalDateTime createTime;
    private LocalDateTime expireTime;
    private LocalDateTime payTime;
    private LocalDateTime cancelTime;

    private List<OrderItemVO> items = new ArrayList<>();
    private OrderAddressVO address;
}
