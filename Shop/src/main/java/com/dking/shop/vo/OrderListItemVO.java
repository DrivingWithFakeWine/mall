package com.dking.shop.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrderListItemVO {

    private Long id;
    private String orderNo;

    /** 0 待支付 / 1 已支付 / 2 已发货 / 3 已完成 / 4 已取消 */
    private Integer status;

    private BigDecimal payAmount;
    private LocalDateTime createTime;

    /** 列表展示用：首个商品的名称与图片（快照） */
    private String firstItemName;
    private String firstItemImage;

    /** 订单内商品总件数 */
    private Integer itemCount;
}
