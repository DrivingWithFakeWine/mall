package com.dking.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dking.product.entity.ProductSku;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ProductSkuMapper extends BaseMapper<ProductSku> {

    /** 支付成功后累加销量（幂等由调用方的台账保证） */
    @Update("""
            UPDATE product_sku
               SET sales = sales + #{quantity}, update_time = NOW()
             WHERE id = #{skuId} AND deleted = 0
            """)
    int addSales(@Param("skuId") Long skuId, @Param("quantity") int quantity);
}
