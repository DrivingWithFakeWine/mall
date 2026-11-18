package com.dking.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dking.product.entity.ProductSpu;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ProductSpuMapper extends BaseMapper<ProductSpu> {

    /** 支付成功后累加 SPU 销量 */
    @Update("""
            UPDATE product_spu
               SET sales = sales + #{quantity}, update_time = NOW()
             WHERE id = #{spuId} AND deleted = 0
            """)
    int addSales(@Param("spuId") Long spuId, @Param("quantity") int quantity);
}
