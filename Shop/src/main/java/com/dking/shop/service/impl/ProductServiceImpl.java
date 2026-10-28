package com.dking.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dking.shop.entity.Category;
import com.dking.shop.entity.ProductSpu;
import com.dking.shop.mapper.CategoryMapper;
import com.dking.shop.mapper.ProductSpuMapper;
import com.dking.shop.service.ProductService;
import com.dking.shop.vo.PageVO;
import com.dking.shop.vo.ProductListItemVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class ProductServiceImpl implements ProductService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final Long TOP_CATEGORY_PARENT_ID = 0L;

    @Resource
    private ProductSpuMapper productSpuMapper;

    @Resource
    private CategoryMapper categoryMapper;

    @Override
    public PageVO<ProductListItemVO> list(int page, int size, Long categoryId) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        LambdaQueryWrapper<ProductSpu> wrapper = new LambdaQueryWrapper<ProductSpu>()
                .eq(ProductSpu::getStatus, 1)
                // 列表不查 detail 大字段
                .select(ProductSpu::getId,
                        ProductSpu::getName,
                        ProductSpu::getSubtitle,
                        ProductSpu::getMainImage,
                        ProductSpu::getPriceMin,
                        ProductSpu::getSales)
                .orderByDesc(ProductSpu::getSales)
                // 销量相同的行需要稳定次序，否则翻页可能重复或漏数据
                .orderByDesc(ProductSpu::getId);

        if (categoryId != null && !applyCategoryFilter(wrapper, categoryId)) {
            return emptyPage();
        }

        Page<ProductSpu> result = productSpuMapper.selectPage(new Page<>(safePage, safeSize), wrapper);

        List<ProductListItemVO> records = result.getRecords().stream()
                .map(this::toListItemVO)
                .toList();

        PageVO<ProductListItemVO> pageVO = new PageVO<>();
        pageVO.setTotal(Math.toIntExact(result.getTotal()));
        pageVO.setRecords(records);

        log.info("查询商品列表, page={}, size={}, categoryId={}, total={}",
                safePage, safeSize, categoryId, result.getTotal());
        return pageVO;
    }

    /**
     * 一级分类展开为其下二级分类；二级分类直接用。
     *
     * @return false 表示该分类不存在/已禁用/没有子分类，调用方应返回空结果
     */
    private boolean applyCategoryFilter(LambdaQueryWrapper<ProductSpu> wrapper, Long categoryId) {
        Category category = categoryMapper.selectById(categoryId);
        if (category == null || category.getStatus() != 1) {
            return false;
        }

        if (!TOP_CATEGORY_PARENT_ID.equals(category.getParentId())) {
            // 二级分类：商品直接挂在这个分类上
            wrapper.eq(ProductSpu::getCategoryId, categoryId);
            return true;
        }

        // 一级分类：商品挂在二级分类上，先取出子分类 id
        List<Long> childIds = categoryMapper.selectList(
                        new LambdaQueryWrapper<Category>()
                                .eq(Category::getParentId, categoryId)
                                .eq(Category::getStatus, 1)
                                .select(Category::getId))
                .stream()
                .map(Category::getId)
                .toList();

        if (childIds.isEmpty()) {
            return false;
        }
        wrapper.in(ProductSpu::getCategoryId, childIds);
        return true;
    }

    private ProductListItemVO toListItemVO(ProductSpu spu) {
        ProductListItemVO vo = new ProductListItemVO();
        vo.setId(spu.getId());
        vo.setName(spu.getName());
        vo.setSubtitle(spu.getSubtitle());
        vo.setMainImage(spu.getMainImage());
        vo.setPriceMin(spu.getPriceMin());
        vo.setSales(spu.getSales());
        return vo;
    }

    private PageVO<ProductListItemVO> emptyPage() {
        PageVO<ProductListItemVO> pageVO = new PageVO<>();
        pageVO.setTotal(0);
        pageVO.setRecords(List.of());
        return pageVO;
    }
}
