package com.dking.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dking.shop.entity.Category;
import com.dking.shop.mapper.CategoryMapper;
import com.dking.shop.service.CategoryService;
import com.dking.shop.vo.CategoryVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CategoryServiceImpl implements CategoryService {

    @Resource
    private CategoryMapper categoryMapper;

    @Override
    public List<CategoryVO> tree() {
        // 1. 一次查出所有启用的分类，按 sort 升序（逻辑删除由全局配置自动过滤）
        List<Category> categories = categoryMapper.selectList(
                new LambdaQueryWrapper<Category>()
                        .eq(Category::getStatus, 1)
                        .orderByAsc(Category::getSort));
        // 2. 按 parentId 分组：key = 父分类 id，value = 它的子分类列表
        Map<Long, List<Category>> childrenByParent = categories.stream()
                .filter(c -> !isTop(c))
                .collect(Collectors.groupingBy(Category::getParentId));
        // 3. 取一级分类，挂上各自的子分类
        List<CategoryVO> result = categories.stream()
                .filter(this::isTop)
                .map(c -> {
                    CategoryVO vo = toVO(c);
                    vo.setChildren(childrenByParent
                            .getOrDefault(c.getId(), List.of())
                            .stream()
                            .map(this::toVO)
                            .toList());
                    return vo;
                })
                .toList();

        log.info("查询分类树，一级分类数={}", result.size());
        return result;

    }

    private boolean isTop(Category c) {
        return Long.valueOf(0L).equals(c.getParentId());
    }

    private CategoryVO toVO(Category c) {
        CategoryVO vo = new CategoryVO();
        vo.setId(c.getId());
        vo.setName(c.getName());
        vo.setSort(c.getSort());
        return vo;
    }

}
