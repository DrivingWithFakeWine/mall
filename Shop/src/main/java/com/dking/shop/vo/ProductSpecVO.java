package com.dking.shop.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ProductSpecVO {

    private Long id;
    private String name;
    private List<SpecValueVO> values = new ArrayList<>();

    @Data
    public static class SpecValueVO {
        private Long id;
        private String value;
    }
}
