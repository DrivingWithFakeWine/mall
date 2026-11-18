package com.dking.product.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ProductDetailVO {

    private ProductSpuVO spu;
    private List<ProductImageVO> images = new ArrayList<>();
    private List<ProductSpecVO> specs = new ArrayList<>();
    private List<ProductSkuVO> skus = new ArrayList<>();
}
