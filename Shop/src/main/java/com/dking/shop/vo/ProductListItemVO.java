package com.dking.shop.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductListItemVO {

    private Long id;
    private String name;
    private String subtitle;
    private String mainImage;
    private BigDecimal priceMin;
    private Integer sales;
}
