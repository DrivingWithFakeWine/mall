package com.dking.shop.vo;

import lombok.Data;

@Data
public class UserAddressVO {

    private Long id;
    private String receiverName;
    private String receiverPhone;
    private String province;
    private String city;
    private String district;
    private String detail;

    /** 1 默认地址，0 普通地址 */
    private Integer isDefault;
}
