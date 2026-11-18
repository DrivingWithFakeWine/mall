package com.dking.product.controller;

import com.dking.product.common.Result;
import com.dking.product.dto.SkuSnapshot;
import com.dking.product.service.SkuQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 服务间调用的内部接口（RPC）。
 *
 * <p><b>为什么单独一套 controller：</b>对外接口的契约是给浏览器的（分页、过滤、错误码面向用户），
 * 内部接口的契约是给其他服务的（按 id 批量、扁平结构、缺数据不报错）。两者混在一个
 * Controller 里，改一边就会影响另一边。</p>
 *
 * <p><b>路径以 /internal 开头：</b>网关只路由 /product/**、/category/** 这类业务前缀，
 * /internal/** 不会被转发到公网，等于天然隔离。</p>
 *
 * <p><b>当前没有鉴权</b>：依赖「8081 不对外暴露 + 网关不转发 /internal」这层网络隔离。
 * 上云后应该再加一层服务间凭证（mTLS 或内部 token）。</p>
 */
@RestController
@RequestMapping("/internal/product")
public class InternalProductController {

    private final SkuQueryService skuQueryService;

    public InternalProductController(SkuQueryService skuQueryService) {
        this.skuQueryService = skuQueryService;
    }

    /**
     * 批量取 SKU 快照（含库存与 SPU 摘要）。
     * 购物车结算、订单校验、秒杀活动展示都用这一个接口。
     */
    @PostMapping("/skus/batch")
    public Result<List<SkuSnapshot>> batchSkus(@RequestBody List<Long> skuIds) {
        return Result.success(skuQueryService.batch(skuIds));
    }

    /** 关键字 → spuId 列表，给秒杀按商品名搜索用 */
    @GetMapping("/search/spu-ids")
    public Result<List<Long>> searchSpuIds(@RequestParam String keyword,
                                           @RequestParam(defaultValue = "200") int limit) {
        return Result.success(skuQueryService.searchSpuIds(keyword, limit));
    }
}
