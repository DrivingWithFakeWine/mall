package com.dking.shop.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AddressSaveDTO {

    @NotBlank(message = "收货人不能为空")
    @Size(max = 50, message = "收货人不能超过 50 字")
    private String receiverName;

    @NotBlank(message = "收货电话不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String receiverPhone;

    @NotBlank(message = "省份不能为空")
    @Size(max = 50, message = "省份不能超过 50 字")
    private String province;

    @NotBlank(message = "城市不能为空")
    @Size(max = 50, message = "城市不能超过 50 字")
    private String city;

    @NotBlank(message = "区县不能为空")
    @Size(max = 50, message = "区县不能超过 50 字")
    private String district;

    @NotBlank(message = "详细地址不能为空")
    @Size(max = 255, message = "详细地址不能超过 255 字")
    private String detail;

    /** 1 设为默认地址，0 普通地址 */
    @NotNull(message = "是否默认不能为空")
    @Min(value = 0, message = "是否默认只能是 0 或 1")
    @Max(value = 1, message = "是否默认只能是 0 或 1")
    private Integer isDefault;
}
