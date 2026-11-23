package com.dking.shop.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("product_stock")
public class ProductStock {

    @TableId
    private Long id;

    private Long skuId;

    /** 可用库存：下单时校验并扣减 */
    private Integer availableStock;

    /** 锁定库存：下单未支付期间占用 */
    private Integer lockedStock;

    /** 乐观锁版本号：注册 OptimisticLockerInnerInterceptor 后配合 @Version 使用 */
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
