package com.dking.shop.service;

import com.dking.shop.vo.CategoryVO;

import java.util.List;

public interface CategoryService {

    /** 分类树：一级分类 + 其下二级分类，按 sort 升序 */
    List<CategoryVO> tree();
}
