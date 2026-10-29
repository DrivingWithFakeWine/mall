package com.dking.shop.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class OrderItemVO {

    private Long id;
    private Long spuId;
    private Long skuId;

    /** 以下均为下单时的快照 */
    private String productName;
    private String specDesc;
    private String image;
    private BigDecimal price;
    private Integer quantity;
    private BigDecimal totalAmount;
}
