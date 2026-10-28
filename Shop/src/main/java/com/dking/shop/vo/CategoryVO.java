package com.dking.shop.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CategoryVO {

    private Long id;
    private String name;
    private Integer sort;

    /** 契约要求 children 始终为数组：一级分类没有子分类时返回空数组而非 null */
    private List<CategoryVO> children = new ArrayList<>();
}
