package com.dking.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dking.shop.entity.Category;
import com.dking.shop.entity.ProductImage;
import com.dking.shop.entity.ProductSku;
import com.dking.shop.entity.ProductSpec;
import com.dking.shop.entity.ProductSpecValue;
import com.dking.shop.entity.ProductSpu;
import com.dking.shop.entity.ProductStock;
import com.dking.shop.exception.NotFoundException;
import com.dking.shop.mapper.CategoryMapper;
import com.dking.shop.mapper.ProductImageMapper;
import com.dking.shop.mapper.ProductSkuMapper;
import com.dking.shop.mapper.ProductSpecMapper;
import com.dking.shop.mapper.ProductSpecValueMapper;
import com.dking.shop.mapper.ProductSpuMapper;
import com.dking.shop.mapper.ProductStockMapper;
import com.dking.shop.service.ProductService;
import com.dking.shop.vo.PageVO;
import com.dking.shop.vo.ProductDetailVO;
import com.dking.shop.vo.ProductImageVO;
import com.dking.shop.vo.ProductListItemVO;
import com.dking.shop.vo.ProductSkuVO;
import com.dking.shop.vo.ProductSpecVO;
import com.dking.shop.vo.ProductSpuVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ProductServiceImpl implements ProductService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final Long TOP_CATEGORY_PARENT_ID = 0L;
    private static final Integer SPU_STATUS_ON = 1;
    private static final Integer SKU_STATUS_ON = 1;

    @Resource
    private ProductSpuMapper productSpuMapper;

    @Resource
    private ProductSkuMapper productSkuMapper;

    @Resource
    private ProductStockMapper productStockMapper;

    @Resource
    private ProductImageMapper productImageMapper;

    @Resource
    private ProductSpecMapper productSpecMapper;

    @Resource
    private ProductSpecValueMapper productSpecValueMapper;

    @Resource
    private CategoryMapper categoryMapper;

    @Override
    public PageVO<ProductListItemVO> list(int page, int size, Long categoryId) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        LambdaQueryWrapper<ProductSpu> wrapper = new LambdaQueryWrapper<ProductSpu>()
                .eq(ProductSpu::getStatus, SPU_STATUS_ON)
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

    @Override
    public ProductDetailVO detail(Long spuId) {
        ProductSpuVO spuVO = loadSpu(spuId);
        List<ProductImageVO> images = loadImages(spuId);
        List<ProductSpecVO> specs = loadSpecs(spuId);
        List<ProductSkuVO> skus = loadSkus(spuId);

        ProductDetailVO detailVO = new ProductDetailVO();
        detailVO.setSpu(spuVO);
        detailVO.setImages(images);
        detailVO.setSpecs(specs);
        detailVO.setSkus(skus);

        log.info("查询商品详情, spuId={}, imageCount={}, specCount={}, skuCount={}",
                spuId, images.size(), specs.size(), skus.size());
        return detailVO;
    }

    private ProductSpuVO loadSpu(Long spuId) {
        ProductSpu spu = productSpuMapper.selectById(spuId);
        // 草稿/下架对外一律表现为"不存在"，不暴露内部状态
        if (spu == null || !SPU_STATUS_ON.equals(spu.getStatus())) {
            throw new NotFoundException("商品不存在或已下架");
        }

        ProductSpuVO vo = new ProductSpuVO();
        vo.setId(spu.getId());
        vo.setName(spu.getName());
        vo.setSubtitle(spu.getSubtitle());
        vo.setDetail(spu.getDetail());
        vo.setPriceMin(spu.getPriceMin());
        vo.setSales(spu.getSales());
        return vo;
    }

    private List<ProductImageVO> loadImages(Long spuId) {
        return productImageMapper.selectList(
                        new LambdaQueryWrapper<ProductImage>()
                                .eq(ProductImage::getSpuId, spuId)
                                .orderByAsc(ProductImage::getSort)
                                .orderByAsc(ProductImage::getId))
                .stream()
                .map(image -> {
                    ProductImageVO vo = new ProductImageVO();
                    vo.setId(image.getId());
                    vo.setUrl(image.getUrl());
                    vo.setIsMain(image.getIsMain());
                    vo.setSkuId(image.getSkuId());
                    return vo;
                })
                .toList();
    }

    private List<ProductSpecVO> loadSpecs(Long spuId) {
        List<ProductSpec> specs = productSpecMapper.selectList(
                new LambdaQueryWrapper<ProductSpec>()
                        .eq(ProductSpec::getSpuId, spuId)
                        .orderByAsc(ProductSpec::getSort)
                        .orderByAsc(ProductSpec::getId));
        if (specs.isEmpty()) {
            return List.of();
        }

        List<Long> specIds = specs.stream().map(ProductSpec::getId).toList();
        Map<Long, List<ProductSpecValue>> valuesBySpec = productSpecValueMapper.selectList(
                        new LambdaQueryWrapper<ProductSpecValue>()
                                .in(ProductSpecValue::getSpecId, specIds)
                                .orderByAsc(ProductSpecValue::getSort)
                                .orderByAsc(ProductSpecValue::getId))
                .stream()
                .collect(Collectors.groupingBy(ProductSpecValue::getSpecId));

        return specs.stream()
                .map(spec -> {
                    ProductSpecVO vo = new ProductSpecVO();
                    vo.setId(spec.getId());
                    vo.setName(spec.getName());
                    vo.setValues(valuesBySpec.getOrDefault(spec.getId(), List.of())
                            .stream()
                            .map(value -> {
                                ProductSpecVO.SpecValueVO valueVO = new ProductSpecVO.SpecValueVO();
                                valueVO.setId(value.getId());
                                valueVO.setValue(value.getValue());
                                return valueVO;
                            })
                            .toList());
                    return vo;
                })
                .toList();
    }

    private List<ProductSkuVO> loadSkus(Long spuId) {
        List<ProductSku> skus = productSkuMapper.selectList(
                new LambdaQueryWrapper<ProductSku>()
                        .eq(ProductSku::getSpuId, spuId)
                        .eq(ProductSku::getStatus, SKU_STATUS_ON)
                        .orderByAsc(ProductSku::getPrice)
                        .orderByAsc(ProductSku::getId));
        if (skus.isEmpty()) {
            return List.of();
        }

        Map<Long, Integer> stockBySku = loadStockMap(skus.stream().map(ProductSku::getId).toList());

        return skus.stream()
                .map(sku -> {
                    ProductSkuVO vo = new ProductSkuVO();
                    vo.setId(sku.getId());
                    vo.setSpecDesc(sku.getSpecDesc());
                    vo.setImage(sku.getImage());
                    vo.setPrice(sku.getPrice());
                    vo.setOriginalPrice(sku.getOriginalPrice());
                    // 缺库存行时按售罄处理，不返回 null
                    vo.setAvailableStock(stockBySku.getOrDefault(sku.getId(), 0));
                    return vo;
                })
                .toList();
    }

    private Map<Long, Integer> loadStockMap(List<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        return productStockMapper.selectList(
                        new LambdaQueryWrapper<ProductStock>()
                                .in(ProductStock::getSkuId, skuIds)
                                .select(ProductStock::getSkuId, ProductStock::getAvailableStock))
                .stream()
                .collect(Collectors.toMap(ProductStock::getSkuId, ProductStock::getAvailableStock));
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
