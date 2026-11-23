package com.dking.shop.client;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dking.shop.client.dto.SkuSnapshot;
import com.dking.shop.client.dto.StockItem;
import com.dking.shop.client.dto.StockOpRequest;
import com.dking.shop.client.dto.StockOpResult;
import com.dking.shop.common.Result;
import com.dking.shop.entity.ProductSku;
import com.dking.shop.entity.ProductSpu;
import com.dking.shop.entity.ProductStock;
import com.dking.shop.mapper.ProductSkuMapper;
import com.dking.shop.mapper.ProductSpuMapper;
import com.dking.shop.mapper.ProductStockMapper;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 测试用的 {@link ProductClient} 本地实现（只在 test profile 生效）。
 *
 * <p><b>为什么需要它：</b>订单并发测试（100 线程抢 10 件库存）跑的是真库、断言真实库存值，
 * 不能靠 mock。如果走 Feign，`mvn test` 就得先起一个 product-service，测试不再是自包含的。</p>
 *
 * <p><b>为什么不放进 src/main：</b>它只是测试替身，不该进发布包；
 * 放在 src/test 下由组件扫描发现，打包时天然不包含。</p>
 *
 * <p><b>它和生产实现的差别：</b>没有幂等台账、没有状态机——这些由 product-service 保证，
 * 本地替身只保证「条件更新不会扣成负数」这一条，足够订单侧用例使用。</p>
 */
@Component
@Profile("test")
public class LocalProductClient implements ProductClient {

    @Resource
    private ProductSkuMapper skuMapper;

    @Resource
    private ProductSpuMapper spuMapper;

    @Resource
    private ProductStockMapper stockMapper;

    /**
     * 预占记录：orderNo:skuId → 数量。
     *
     * <p>只用于 reserve 幂等。**release / confirm 不依赖它**：测试里有些订单是直接插库
     * 造的（模拟历史数据），没有预占记录，真实服务会返回 NOT_RESERVED 而这里必须放行，
     * 否则这些用例没法验证「取消回补」。</p>
     *
     * <p>代价：替身不校验状态机。「库存不足不该补偿释放」这条约束由
     * {@code OrderServiceImpl.submit} 的异常分支保证，不在替身里。</p>
     */
    private final java.util.Map<String, Integer> reservations = new java.util.concurrent.ConcurrentHashMap<>();

    private static String reservationKey(String orderNo, Long skuId) {
        return orderNo + ":" + skuId;
    }

    @Override
    public Result<List<SkuSnapshot>> batchSkus(List<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return Result.success(List.of());
        }
        List<ProductSku> skus = skuMapper.selectBatchIds(skuIds);
        if (skus.isEmpty()) {
            return Result.success(List.of());
        }

        Map<Long, ProductSpu> spuMap = spuMapper.selectBatchIds(
                        skus.stream().map(ProductSku::getSpuId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(ProductSpu::getId, Function.identity()));

        Map<Long, ProductStock> stockMap = stockMapper.selectList(
                        new LambdaQueryWrapper<ProductStock>().in(ProductStock::getSkuId, skuIds))
                .stream()
                .collect(Collectors.toMap(ProductStock::getSkuId, Function.identity()));

        List<SkuSnapshot> snapshots = skus.stream()
                .map(sku -> {
                    ProductSpu spu = spuMap.get(sku.getSpuId());
                    ProductStock stock = stockMap.get(sku.getId());
                    return new SkuSnapshot(
                            sku.getId(), sku.getSpuId(), sku.getSkuCode(), sku.getSpecDesc(), sku.getImage(),
                            sku.getPrice(), sku.getOriginalPrice(), sku.getStatus(),
                            stock == null ? 0 : stock.getAvailableStock(),
                            stock == null ? 0 : stock.getLockedStock(),
                            spu == null ? null : spu.getName(),
                            spu == null ? null : spu.getMainImage(),
                            spu == null ? null : spu.getStatus());
                })
                .toList();
        return Result.success(snapshots);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<StockOpResult> reserve(StockOpRequest request) {
        for (StockItem item : request.items()) {
            String key = reservationKey(request.orderNo(), item.skuId());
            if (reservations.containsKey(key)) {
                continue;   // 幂等：已经预占过
            }
            int rows = stockMapper.update(null, new LambdaUpdateWrapper<ProductStock>()
                    .eq(ProductStock::getSkuId, item.skuId())
                    .ge(ProductStock::getAvailableStock, item.quantity())
                    .setSql("available_stock = available_stock - " + item.quantity())
                    .setSql("locked_stock = locked_stock + " + item.quantity()));
            if (rows == 0) {
                throw new StockOpRejectedException("INSUFFICIENT_STOCK",
                        "库存不足: skuId=" + item.skuId());
            }
            reservations.put(key, item.quantity());
        }
        return Result.success(new StockOpResult(request.orderNo(), "RESERVE", request.items().size(), 0, "ok"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<StockOpResult> release(StockOpRequest request) {
        for (StockItem item : request.items()) {
            reservations.remove(reservationKey(request.orderNo(), item.skuId()));
            stockMapper.update(null, new LambdaUpdateWrapper<ProductStock>()
                    .eq(ProductStock::getSkuId, item.skuId())
                    .ge(ProductStock::getLockedStock, item.quantity())
                    .setSql("available_stock = available_stock + " + item.quantity())
                    .setSql("locked_stock = locked_stock - " + item.quantity()));
        }
        return Result.success(new StockOpResult(request.orderNo(), "RELEASE", request.items().size(), 0, "ok"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<StockOpResult> confirm(StockOpRequest request) {
        for (StockItem item : request.items()) {
            reservations.remove(reservationKey(request.orderNo(), item.skuId()));
            stockMapper.update(null, new LambdaUpdateWrapper<ProductStock>()
                    .eq(ProductStock::getSkuId, item.skuId())
                    .ge(ProductStock::getLockedStock, item.quantity())
                    .setSql("locked_stock = locked_stock - " + item.quantity()));
        }
        return Result.success(new StockOpResult(request.orderNo(), "CONFIRM", request.items().size(), 0, "ok"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<StockOpResult> sales(StockOpRequest request) {
        for (StockItem item : request.items()) {
            ProductSku sku = skuMapper.selectById(item.skuId());
            if (sku == null) {
                continue;
            }
            skuMapper.update(null, new LambdaUpdateWrapper<ProductSku>()
                    .eq(ProductSku::getId, item.skuId())
                    .setSql("sales = sales + " + item.quantity()));
            spuMapper.update(null, new LambdaUpdateWrapper<ProductSpu>()
                    .eq(ProductSpu::getId, sku.getSpuId())
                    .setSql("sales = sales + " + item.quantity()));
        }
        return Result.success(new StockOpResult(request.orderNo(), "SALES", request.items().size(), 0, "ok"));
    }
}
