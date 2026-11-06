package com.dking.shop.controller;

import com.dking.shop.common.Result;
import com.dking.shop.dto.SeckillOrderDTO;
import com.dking.shop.service.SeckillService;
import com.dking.shop.vo.PageVO;
import com.dking.shop.vo.SeckillActivityDetailVO;
import com.dking.shop.vo.SeckillActivityListItemVO;
import com.dking.shop.vo.SeckillOrderSubmitVO;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/seckill")
public class SeckillController {

    @Resource
    private SeckillService seckillService;

    @GetMapping("/list")
    public Result<PageVO<SeckillActivityListItemVO>> list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String keyword) {
        return Result.success(seckillService.list(page, size, keyword));
    }

    @GetMapping("/{id}")
    public Result<SeckillActivityDetailVO> detail(@PathVariable Long id) {
        return Result.success(seckillService.detail(id));
    }

    @PostMapping("/{id}/order")
    public Result<SeckillOrderSubmitVO> order(@AuthenticationPrincipal Jwt jwt,
                                              @PathVariable Long id,
                                              @RequestBody @Valid SeckillOrderDTO dto) {
        return Result.success(seckillService.submitOrder(Long.valueOf(jwt.getSubject()), id, dto));
    }

    /** 异步下单结果：前端拿到 PENDING 后轮询 */
    @GetMapping("/order/result")
    public Result<SeckillOrderSubmitVO> orderResult(@AuthenticationPrincipal Jwt jwt,
                                                    @RequestParam String token) {
        return Result.success(seckillService.orderResult(Long.valueOf(jwt.getSubject()), token));
    }
}
