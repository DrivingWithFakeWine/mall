package com.dking.shop.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProfileUpdateDTO {

    @NotBlank(message = "昵称不能为空")
    @Size(min = 2, max = 12, message = "昵称为2-12位")
    private String nickname;

    /** 空串或 null 表示清空手机号 */
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String telephone;
}
