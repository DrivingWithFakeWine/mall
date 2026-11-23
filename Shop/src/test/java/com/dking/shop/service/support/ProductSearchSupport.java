package com.dking.shop.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dking.shop.entity.Category;
import com.dking.shop.entity.ProductSpu;
import com.dking.shop.mapper.CategoryMapper;
import com.dking.shop.mapper.ProductSpuMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 关键字搜索的公共匹配逻辑：分类名命中后展开到商品。
 * 分类只有两级，命中一级分类时自动带上其子分类。
 */
@Component
public class ProductSearchSupport {

    private static final int MAX_MATCHED_ROWS = 200;
    private static final Long TOP_CATEGORY_PARENT_ID = 0L;

    @Resource
    private CategoryMapper categoryMapper;

    @Resource
    private ProductSpuMapper productSpuMapper;

    /** 名称命中关键字的分类 id（含命中一级分类的子分类） */
    public List<Long> categoryIdsByKeyword(String kw) {
        List<Category> matched = categoryMapper.selectList(
                new LambdaQueryWrapper<Category>()
                        .like(Category::getName, kw)
                        .eq(Category::getStatus, 1)
                        .select(Category::getId, Category::getParentId)
                        .last("LIMIT " + MAX_MATCHED_ROWS));
        if (matched.isEmpty()) {
            return List.of();
        }

        Set<Long> ids = new HashSet<>();
        matched.forEach(category -> ids.add(category.getId()));

        List<Long> topIds = matched.stream()
                .filter(category -> TOP_CATEGORY_PARENT_ID.equals(category.getParentId()))
                .map(Category::getId)
                .toList();
        if (!topIds.isEmpty()) {
            categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                            .in(Category::getParentId, topIds)
                            .eq(Category::getStatus, 1)
                            .select(Category::getId))
                    .forEach(child -> ids.add(child.getId()));
        }
        return List.copyOf(ids);
    }

    /** 商品名/副标题或所属分类名命中关键字的 spuId，最多 limit 个 */
    public List<Long> spuIdsByKeyword(String kw, int limit) {
        List<Long> ids = new ArrayList<>(productSpuMapper.selectList(
                        new LambdaQueryWrapper<ProductSpu>()
                                .select(ProductSpu::getId)
                                .and(w -> w.like(ProductSpu::getName, kw)
                                        .or()
                                        .like(ProductSpu::getSubtitle, kw))
                                .last("LIMIT " + limit))
                .stream()
                .map(ProductSpu::getId)
                .toList());

        int remaining = limit - ids.size();
        List<Long> categoryIds = categoryIdsByKeyword(kw);
        if (remaining > 0 && !categoryIds.isEmpty()) {
            productSpuMapper.selectList(new LambdaQueryWrapper<ProductSpu>()
                            .select(ProductSpu::getId)
                            .in(ProductSpu::getCategoryId, categoryIds)
                            .last("LIMIT " + remaining))
                    .forEach(spu -> ids.add(spu.getId()));
        }
        return ids.stream().distinct().toList();
    }
}
