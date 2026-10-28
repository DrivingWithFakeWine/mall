package com.dking.shop.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductSkuVO {

    private Long id;
    private String specDesc;
    private String image;
    private BigDecimal price;
    private BigDecimal originalPrice;

    /** 可用库存，0 表示售罄（前端置灰） */
    private Integer availableStock;
}
