package com.dking.shop.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class CartItemVO {

    /** 购物车项 id（修改数量/勾选/删除时使用） */
    private Long id;

    private Long skuId;

    /** 所属 SPU id，前端用于跳转商品详情页 */
    private Long spuId;

    private String name;
    private String specDesc;
    private String image;

    /** 当前售价（仅供参考，结算以后端实时价格为准） */
    private BigDecimal price;

    private Integer quantity;

    /** 1 勾选结算，0 取消勾选 */
    private Integer selected;

    private Integer availableStock;

    /** true 表示商品已下架或库存不足，不可结算 */
    private Boolean invalid;
}
