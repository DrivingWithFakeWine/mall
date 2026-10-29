package com.dking.shop.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class OrderSubmitDTO {

    @NotNull(message = "收货地址不能为空")
    private Long addressId;

    @Size(max = 255, message = "备注不能超过 255 字")
    private String remark;
}
