package com.dking.marketing.client;

import com.dking.marketing.client.dto.SkuSnapshot;
import com.dking.marketing.common.Result;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 商品客户端替身：营销测试不连商品服务，用内存注册表提供 SKU 快照。
 *
 * <p>生产实现是 Feign（{@code ProductClientConfig} 带 {@code @Profile("!test")}，
 * 测试环境不启用）。这里只实现营销用到的两个方法。</p>
 */
@Component
@Profile("test")
public class LocalProductClient implements ProductClient {

    private final Map<Long, SkuSnapshot> skus = new ConcurrentHashMap<>();

    /** 注册一个可售 SKU（SKU/SPU 都是上架状态） */
    public SkuSnapshot register(Long skuId, Long spuId, String spuName, BigDecimal price, int availableStock) {
        SkuSnapshot snapshot = new SkuSnapshot(
                skuId, spuId, "SKU-" + skuId, "默认规格", null,
                price, price, 1, availableStock, 0, spuName, null, 1);
        skus.put(skuId, snapshot);
        return snapshot;
    }

    /** 改上下架状态：0 表示下架 */
    public void setStatus(Long skuId, int skuStatus, int spuStatus) {
        SkuSnapshot old = skus.get(skuId);
        if (old == null) {
            return;
        }
        skus.put(skuId, new SkuSnapshot(
                old.skuId(), old.spuId(), old.skuCode(), old.specDesc(), old.image(),
                old.price(), old.originalPrice(), skuStatus, old.availableStock(), old.lockedStock(),
                old.spuName(), old.spuMainImage(), spuStatus));
    }

    public void clear() {
        skus.clear();
    }

    @Override
    public Result<List<SkuSnapshot>> batchSkus(List<Long> skuIds) {
        List<SkuSnapshot> found = new ArrayList<>();
        for (Long id : skuIds) {
            SkuSnapshot snapshot = skus.get(id);
            if (snapshot != null) {
                found.add(snapshot);
            }
        }
        return Result.success(found);
    }

    @Override
    public Result<List<Long>> searchSpuIds(String keyword, int limit) {
        return Result.success(skus.values().stream()
                .filter(s -> s.spuName() != null && s.spuName().contains(keyword))
                .map(SkuSnapshot::spuId)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .limit(limit)
                .toList());
    }
}
