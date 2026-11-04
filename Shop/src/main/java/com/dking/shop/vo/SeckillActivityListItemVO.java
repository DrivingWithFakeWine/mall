package com.dking.shop.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SeckillActivityListItemVO {

    private Long id;
    private Long spuId;
    private Long skuId;

    /** 活动名称 */
    private String name;
    /** 商品名称（商品被删除时回退为活动名称） */
    private String productName;

    private String image;
    private String specDesc;

    private BigDecimal seckillPrice;
    /** 商品原价 */
    private BigDecimal originalPrice;

    private Integer totalStock;
    private Integer availableStock;

    private LocalDateTime startTime;
    private LocalDateTime endTime;

    /** 0 未开始 / 1 进行中 / 2 已结束 / 3 已售罄 */
    private Integer phase;
}
