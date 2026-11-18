package com.dking.product;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 商品服务：分类、SPU、SKU、库存、规格、图片。
 *
 * <p>从单体里抽出来的第一个域。选择它作为第一刀的原因：读最热、无状态、表结构最成熟，
 * 拆坏了不影响下单。</p>
 */
@MapperScan("com.dking.product.mapper")
@SpringBootApplication
public class ProductApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductApplication.class, args);
    }
}
