package com.dking.product.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存操作台账。
 *
 * <p>每次 reserve / release / confirm / sales 先在这里落一条记录，
 * 唯一键 (order_no, sku_id, op) 保证同一操作重复调用只生效一次。</p>
 */
@Data
@TableName("stock_op_log")
public class StockOpLog {

    @TableId
    private Long id;

    private String orderNo;

    private Long skuId;

    /** RESERVE / RELEASE / CONFIRM / SALES */
    private String op;

    private Integer quantity;

    private LocalDateTime createTime;
}
