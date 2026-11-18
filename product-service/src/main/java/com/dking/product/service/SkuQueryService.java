package com.dking.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dking.product.dto.SkuSnapshot;
import com.dking.product.entity.ProductSku;
import com.dking.product.entity.ProductSpu;
import com.dking.product.entity.ProductStock;
import com.dking.product.mapper.ProductSkuMapper;
import com.dking.product.mapper.ProductSpuMapper;
import com.dking.product.mapper.ProductStockMapper;
import com.dking.product.service.support.ProductSearchSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 给其他服务用的查询服务（内部 RPC 的实现体）。
 *
 * <p>和 {@link ProductService} 的区别：ProductService 面向 C 端页面（分页、过滤、详情组装），
 * 这里面向服务间调用（按 id 批量取、字段扁平、不带分页）。两者查询口径不同，刻意分开。</p>
 */
@Service
public class SkuQueryService {

    private static final Logger log = LoggerFactory.getLogger(SkuQueryService.class);

    /** 单次批量查询上限，防止调用方一次传上万个 id 把数据库打穿 */
    private static final int MAX_BATCH_SIZE = 200;
    private static final int MAX_SEARCH_LIMIT = 500;

    private final ProductSkuMapper skuMapper;
    private final ProductStockMapper stockMapper;
    private final ProductSpuMapper spuMapper;
    private final ProductSearchSupport searchSupport;

    public SkuQueryService(ProductSkuMapper skuMapper,
                           ProductStockMapper stockMapper,
                           ProductSpuMapper spuMapper,
                           ProductSearchSupport searchSupport) {
        this.skuMapper = skuMapper;
        this.stockMapper = stockMapper;
        this.spuMapper = spuMapper;
        this.searchSupport = searchSupport;
    }

    /**
     * 批量取 SKU 快照。
     *
     * <p>不存在的 id 直接不出现在结果里——调用方用「返回条数 &lt; 请求条数」判断有失效商品，
     * 不抛异常，因为购物车里混着下架商品是常态。</p>
     */
    public List<SkuSnapshot> batch(List<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = skuIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .limit(MAX_BATCH_SIZE)
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }

        List<ProductSku> skus = skuMapper.selectBatchIds(ids);
        if (skus.isEmpty()) {
            return List.of();
        }

        List<Long> spuIds = skus.stream().map(ProductSku::getSpuId).distinct().toList();
        Map<Long, ProductSpu> spuMap = spuMapper.selectBatchIds(spuIds).stream()
                .collect(Collectors.toMap(ProductSpu::getId, Function.identity()));

        Map<Long, ProductStock> stockMap = stockMapper.selectList(
                        new LambdaQueryWrapper<ProductStock>()
                                .in(ProductStock::getSkuId, ids)
                                .select(ProductStock::getSkuId,
                                        ProductStock::getAvailableStock,
                                        ProductStock::getLockedStock))
                .stream()
                .collect(Collectors.toMap(ProductStock::getSkuId, Function.identity()));

        List<SkuSnapshot> snapshots = skus.stream()
                .map(sku -> {
                    ProductSpu spu = spuMap.get(sku.getSpuId());
                    ProductStock stock = stockMap.get(sku.getId());
                    return new SkuSnapshot(
                            sku.getId(),
                            sku.getSpuId(),
                            sku.getSkuCode(),
                            sku.getSpecDesc(),
                            sku.getImage(),
                            sku.getPrice(),
                            sku.getOriginalPrice(),
                            sku.getStatus(),
                            stock == null ? 0 : stock.getAvailableStock(),
                            stock == null ? 0 : stock.getLockedStock(),
                            spu == null ? null : spu.getName(),
                            spu == null ? null : spu.getMainImage(),
                            spu == null ? null : spu.getStatus());
                })
                .toList();

        log.info("批量查询 SKU 快照, 请求={}, 命中={}", ids.size(), snapshots.size());
        return snapshots;
    }

    /** 关键字 → spuId 列表，给秒杀活动按商品名搜索用 */
    public List<Long> searchSpuIds(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        int safeLimit = Math.min(Math.max(limit, 1), MAX_SEARCH_LIMIT);
        List<Long> result = new LinkedHashSet<>(
                searchSupport.spuIdsByKeyword(keyword.trim(), safeLimit)).stream().toList();
        log.info("关键字搜索 SPU, keyword={}, limit={}, 命中={}", keyword, safeLimit, result.size());
        return result;
    }
}
