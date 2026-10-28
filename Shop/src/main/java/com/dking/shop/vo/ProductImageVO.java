package com.dking.shop.vo;

import lombok.Data;

@Data
public class ProductImageVO {

    private Long id;
    private String url;
    private Integer isMain;

    /** null 表示 SPU 通用图（轮播）；非空表示该 SKU 专属图 */
    private Long skuId;
}
