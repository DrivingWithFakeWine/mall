package com.dking.shop.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dking.shop.entity.Category;
import com.dking.shop.entity.ProductSpu;
import com.dking.shop.mapper.CategoryMapper;
import com.dking.shop.mapper.ProductSpuMapper;
import com.dking.shop.vo.PageVO;
import com.dking.shop.vo.ProductListItemVO;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商品关键字搜索：名称/副标题匹配、分类组合、通配符转义。
 * 每个用例用独立分类 id 隔离，事务结束回滚。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
@Transactional
class ProductSearchTest {

    @Resource
    private ProductService productService;

    @Resource
    private ProductSpuMapper productSpuMapper;

    @Resource
    private CategoryMapper categoryMapper;

    @Test
    void search_shouldMatchNameAndSubtitle() {
        long categoryId = createCategory();
        insertSpu(categoryId, "无线耳机 搜索甲", "主动降噪", 1);
        insertSpu(categoryId, "蓝牙音箱", "随身 搜索乙", 1);
        insertSpu(categoryId, "普通商品", "无关键词", 1);
        insertSpu(categoryId, "下架商品 搜索", "下架商品", 0);

        PageVO<ProductListItemVO> result = productService.list(1, 50, categoryId, "搜索");

        assertEquals(2, result.getTotal(), "只统计上架且命中的商品");
        List<String> names = result.getRecords().stream().map(ProductListItemVO::getName).toList();
        assertTrue(names.contains("无线耳机 搜索甲"));
        assertTrue(names.contains("蓝牙音箱"));
        assertTrue(names.stream().noneMatch(n -> n.contains("下架")));
    }

    @Test
    void search_shouldCombineWithCategory() {
        long categoryA = createCategory();
        long categoryB = createCategory();
        insertSpu(categoryA, "组合搜索 分类内", "描述", 1);
        insertSpu(categoryB, "组合搜索 分类外", "描述", 1);

        PageVO<ProductListItemVO> result = productService.list(1, 50, categoryA, "组合搜索");

        assertEquals(1, result.getTotal());
        assertEquals("组合搜索 分类内", result.getRecords().get(0).getName());
    }

    @Test
    void search_wildcardsShouldBeEscaped() {
        long categoryId = createCategory();
        insertSpu(categoryId, "转义测试商品", "不含通配符", 1);

        assertEquals(0, productService.list(1, 50, categoryId, "%").getTotal(),
                "% 应按字面量匹配");
        assertEquals(0, productService.list(1, 50, categoryId, "_").getTotal(),
                "_ 应按字面量匹配");
        assertEquals(1, productService.list(1, 50, categoryId, "不含通配符").getTotal(),
                "正常关键字仍应命中");
    }

    @Test
    void blankKeyword_shouldBehaveLikeNoFilter() {
        long categoryId = createCategory();
        insertSpu(categoryId, "空白关键字商品A", "描述", 1);
        insertSpu(categoryId, "空白关键字商品B", "描述", 1);
        insertSpu(categoryId, "下架商品", "描述", 0);

        assertEquals(2, productService.list(1, 50, categoryId, "   ").getTotal());
        assertEquals(2, productService.list(1, 50, categoryId, null).getTotal());
    }

    @Test
    void search_shouldMatchCategoryName() {
        String suffix = Long.toString(IdWorker.getId(), 36);
        long parentId = createCategory("数码分类" + suffix, 0L);
        long childId = createCategory("手机子类" + suffix, parentId);
        insertSpu(childId, "名称无关的商品A", "描述", 1);
        insertSpu(childId, "名称无关的商品B", "描述", 1);

        assertEquals(2, productService.list(1, 50, null, "数码分类" + suffix).getTotal(),
                "一级分类命中应包含子分类下的商品");
        assertEquals(2, productService.list(1, 50, null, "手机子类" + suffix).getTotal(),
                "二级分类命中应包含该分类下的商品");
    }

    private long createCategory() {
        return createCategory("商品测试分类", 1L);
    }

    private long createCategory(String name, long parentId) {
        Category category = new Category();
        category.setId(IdWorker.getId());
        category.setParentId(parentId);
        category.setName(name);
        category.setSort(0);
        category.setStatus(1);
        category.setCreateTime(LocalDateTime.now());
        category.setUpdateTime(LocalDateTime.now());
        categoryMapper.insert(category);
        return category.getId();
    }

    private void insertSpu(long categoryId, String name, String subtitle, int status) {
        ProductSpu spu = new ProductSpu();
        spu.setId(IdWorker.getId());
        spu.setCategoryId(categoryId);
        spu.setName(name);
        spu.setCode("SEARCH-" + spu.getId());
        spu.setSubtitle(subtitle);
        spu.setPriceMin(new BigDecimal("9.90"));
        spu.setStatus(status);
        productSpuMapper.insert(spu);
    }
}
