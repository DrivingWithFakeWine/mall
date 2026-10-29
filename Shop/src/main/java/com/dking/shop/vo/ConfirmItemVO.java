package com.dking.shop.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ConfirmItemVO {

    /** 购物车项 id */
    private Long cartId;

    private Long skuId;
    private Long spuId;
    private String name;
    private String specDesc;
    private String image;

    /** 当前售价（提交订单时以服务端价格为准） */
    private BigDecimal price;

    private Integer quantity;

    /** price × quantity */
    private BigDecimal subtotal;
}
