package com.dking.marketing.controller;

import com.dking.marketing.common.Result;
import com.dking.marketing.dto.SeckillOrderDTO;
import com.dking.marketing.service.SeckillService;
import com.dking.marketing.vo.PageVO;
import com.dking.marketing.vo.SeckillActivityDetailVO;
import com.dking.marketing.vo.SeckillActivityListItemVO;
import com.dking.marketing.vo.SeckillOrderSubmitVO;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 秒杀接口。
 *
 * <p>用户身份来自网关注入的 {@code X-User-Id}：JWT 由网关校验一次，
 * 下游服务不再重复验签（服务不对外暴露，直连也拿不到有效头）。</p>
 */
@RestController
@RequestMapping("/seckill")
public class SeckillController {

    private static final String USER_ID_HEADER = "X-User-Id";

    @Resource
    private SeckillService seckillService;

    /** 活动列表：公开接口，不需要登录 */
    @GetMapping("/list")
    public Result<PageVO<SeckillActivityListItemVO>> list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String keyword) {
        return Result.success(seckillService.list(page, size, keyword));
    }

    /** 活动详情：公开接口 */
    @GetMapping("/{id}")
    public Result<SeckillActivityDetailVO> detail(@PathVariable Long id) {
        return Result.success(seckillService.detail(id));
    }

    @PostMapping("/{id}/order")
    public Result<SeckillOrderSubmitVO> order(@RequestHeader(USER_ID_HEADER) Long userId,
                                              @PathVariable Long id,
                                              @RequestBody @Valid SeckillOrderDTO dto) {
        return Result.success(seckillService.submitOrder(userId, id, dto));
    }

    /** 异步下单结果：前端拿到 PENDING 后轮询 */
    @GetMapping("/order/result")
    public Result<SeckillOrderSubmitVO> orderResult(@RequestHeader(USER_ID_HEADER) Long userId,
                                                    @RequestParam String token) {
        return Result.success(seckillService.orderResult(userId, token));
    }
}
