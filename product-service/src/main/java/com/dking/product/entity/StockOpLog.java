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
 *
 * <p><b>关于 *_before / *_after：</b>它们是「操作前读一次库存 + 按 op 推算」得到的近似值，
 * 并发下可能不是数据库里真实的瞬时值。只用于排查，**不能用于对账**。</p>
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

    /** 本次操作数量，恒为正；方向由 op 决定 */
    private Integer quantity;

    private Integer availableBefore;

    private Integer availableAfter;

    private Integer lockedBefore;

    private Integer lockedAfter;

    private LocalDateTime createTime;
}
