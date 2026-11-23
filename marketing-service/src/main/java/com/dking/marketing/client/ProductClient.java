package com.dking.marketing.client;

import com.dking.marketing.client.dto.SkuSnapshot;
import com.dking.marketing.common.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 商品服务的只读接口。
 *
 * <p><b>营销对商品是纯读者</b>：活动的商品名、规格、图片、原价都来自 SKU 快照；
 * 库存走的是营销自己的 {@code seckill_stock_bucket}，不碰 {@code product_stock}。
 * 所以这里只有两个读方法，没有 reserve / release 这些写接口——
 * 写接口的唯一消费者是交易服务。</p>
 */
@FeignClient(name = "product-service", url = "${marketing.product.url:http://localhost:8081}")
public interface ProductClient {

    /** 批量取 SKU 快照（含 SPU 摘要），缺数据不报错，返回条数可能小于请求条数 */
    @PostMapping("/internal/product/skus/batch")
    Result<List<SkuSnapshot>> batchSkus(@RequestBody List<Long> skuIds);

    /** 关键字 → spuId 列表，给活动列表按商品名搜索用 */
    @GetMapping("/internal/product/search/spu-ids")
    Result<List<Long>> searchSpuIds(@RequestParam("keyword") String keyword,
                                    @RequestParam("limit") int limit);
}
