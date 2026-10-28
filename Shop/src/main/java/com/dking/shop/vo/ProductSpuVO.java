package com.dking.shop.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductSpuVO {

    private Long id;
    private String name;
    private String subtitle;
    private String detail;
    private BigDecimal priceMin;
    private Integer sales;
}
