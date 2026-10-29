package com.dking.shop.vo;

import lombok.Data;

/** 订单收货地址快照 */
@Data
public class OrderAddressVO {

    private String receiverName;
    private String receiverPhone;
    private String province;
    private String city;
    private String district;
    private String detail;
}
