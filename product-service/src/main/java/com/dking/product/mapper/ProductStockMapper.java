package com.dking.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dking.product.entity.ProductStock;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ProductStockMapper extends BaseMapper<ProductStock> {

    /**
     * 预占：可用 -n，锁定 +n。影响 0 行 = 库存不足。
     * 条件放在 SQL 里，靠行锁保证并发下不会扣成负数。
     */
    @Update("""
            UPDATE product_stock
               SET available_stock = available_stock - #{quantity},
                   locked_stock    = locked_stock + #{quantity},
                   update_time     = NOW()
             WHERE sku_id = #{skuId}
               AND available_stock >= #{quantity}
               AND deleted = 0
            """)
    int reserve(@Param("skuId") Long skuId, @Param("quantity") int quantity);

    /**
     * 释放：可用 +n，锁定 -n。影响 0 行 = 锁定库存不足（可能重复释放）。
     */
    @Update("""
            UPDATE product_stock
               SET available_stock = available_stock + #{quantity},
                   locked_stock    = locked_stock - #{quantity},
                   update_time     = NOW()
             WHERE sku_id = #{skuId}
               AND locked_stock >= #{quantity}
               AND deleted = 0
            """)
    int release(@Param("skuId") Long skuId, @Param("quantity") int quantity);

    /**
     * 确认（支付后）：锁定转消耗，可用库存不变。
     */
    @Update("""
            UPDATE product_stock
               SET locked_stock = locked_stock - #{quantity},
                   update_time  = NOW()
             WHERE sku_id = #{skuId}
               AND locked_stock >= #{quantity}
               AND deleted = 0
            """)
    int confirm(@Param("skuId") Long skuId, @Param("quantity") int quantity);
}
