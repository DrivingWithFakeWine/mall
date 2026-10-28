package com.dking.shop.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CartSelectDTO {

    @NotNull(message = "购物车项 id 不能为空")
    private Long id;

    /** 1 勾选结算，0 取消勾选 */
    @NotNull(message = "勾选状态不能为空")
    @Min(value = 0, message = "勾选状态只能是 0 或 1")
    @Max(value = 1, message = "勾选状态只能是 0 或 1")
    private Integer selected;
}
