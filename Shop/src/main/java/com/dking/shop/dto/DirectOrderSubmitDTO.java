package com.dking.shop.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DirectOrderSubmitDTO {

    @NotNull(message = "商品不能为空")
    private Long skuId;

    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "购买数量至少为1")
    @Max(value = 99, message = "单个商品最多购买99件")
    private Integer quantity;

    @NotNull(message = "收货地址不能为空")
    private Long addressId;

    @Size(max = 255, message = "备注不能超过 255 字")
    private String remark;
}
