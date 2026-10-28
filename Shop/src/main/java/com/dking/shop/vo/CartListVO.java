package com.dking.shop.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CartListVO {

    private List<CartItemVO> items = new ArrayList<>();
}
