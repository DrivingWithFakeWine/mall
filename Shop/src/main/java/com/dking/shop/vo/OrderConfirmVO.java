package com.dking.shop.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class OrderConfirmVO {

    private List<ConfirmItemVO> items = new ArrayList<>();

    private BigDecimal totalAmount;

    /** 默认收货地址；null 表示用户尚未添加 */
    private UserAddressVO address;
}
