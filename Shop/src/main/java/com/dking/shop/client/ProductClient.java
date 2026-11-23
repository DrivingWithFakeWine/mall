package com.dking.shop.client;

import com.dking.shop.client.dto.SkuSnapshot;
import com.dking.shop.client.dto.StockOpRequest;
import com.dking.shop.client.dto.StockOpResult;
import com.dking.shop.common.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 商品服务的内部接口（RPC）。
 *
 * <p><b>这是 Shop 唯一允许访问商品库存的入口。</b>从 2026-11-22 起，
 * 订单链路不再直接读写 {@code product_sku / product_spu / product_stock}。</p>
 *
 * <p>错误处理：product-service 的业务拒绝返回 HTTP 409 + {@code {"code":"INSUFFICIENT_STOCK",...}}，
 * 由 {@link ProductClientErrorDecoder} 转成 {@link StockOpRejectedException}，
 * 上层按错误码分支，不解析中文 message。</p>
 */
@FeignClient(name = "product-service", url = "${shop.product.url:http://localhost:8081}")
public interface ProductClient {

    /** 批量取 SKU 快照（含库存与 SPU 摘要），缺数据不报错，返回条数可能小于请求条数 */
    @PostMapping("/internal/product/skus/batch")
    Result<List<SkuSnapshot>> batchSkus(@RequestBody List<Long> skuIds);

    /** 关键字 → spuId 列表，给秒杀活动按商品名搜索用 */
    @GetMapping("/internal/product/search/spu-ids")
    Result<List<Long>> searchSpuIds(@RequestParam("keyword") String keyword,
                                    @RequestParam("limit") int limit);

    /** 预占：available -n，locked +n。库存不足抛 {@link StockOpRejectedException} */
    @PostMapping("/internal/product/stock/reserve")
    Result<StockOpResult> reserve(@RequestBody StockOpRequest request);

    /** 释放：available +n，locked -n。必须存在预占记录 */
    @PostMapping("/internal/product/stock/release")
    Result<StockOpResult> release(@RequestBody StockOpRequest request);

    /** 确认（支付后）：locked -n */
    @PostMapping("/internal/product/stock/confirm")
    Result<StockOpResult> confirm(@RequestBody StockOpRequest request);

    /** 销量累加：SKU + SPU */
    @PostMapping("/internal/product/stock/sales")
    Result<StockOpResult> sales(@RequestBody StockOpRequest request);
}
