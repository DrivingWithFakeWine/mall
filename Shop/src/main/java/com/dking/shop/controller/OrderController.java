package com.dking.shop.controller;

import com.dking.shop.common.Result;
import com.dking.shop.dto.OrderSubmitDTO;
import com.dking.shop.service.OrderService;
import com.dking.shop.vo.OrderConfirmVO;
import com.dking.shop.vo.OrderDetailVO;
import com.dking.shop.vo.OrderListItemVO;
import com.dking.shop.vo.OrderPayVO;
import com.dking.shop.vo.OrderSubmitVO;
import com.dking.shop.vo.PageVO;
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
@RequestMapping("/order")
public class OrderController {

    @Resource
    private OrderService orderService;

    @GetMapping("/confirm")
    public Result<OrderConfirmVO> confirm(@AuthenticationPrincipal Jwt jwt) {
        return Result.success(orderService.confirm(Long.valueOf(jwt.getSubject())));
    }

    @PostMapping("/submit")
    public Result<OrderSubmitVO> submit(@AuthenticationPrincipal Jwt jwt,
                                        @RequestBody @Valid OrderSubmitDTO dto) {
        return Result.success(orderService.submit(Long.valueOf(jwt.getSubject()), dto));
    }

    @GetMapping("/list")
    public Result<PageVO<OrderListItemVO>> list(@AuthenticationPrincipal Jwt jwt,
                                                @RequestParam(defaultValue = "1") Integer page,
                                                @RequestParam(defaultValue = "10") Integer size,
                                                @RequestParam(required = false) Integer status) {
        return Result.success(orderService.list(Long.valueOf(jwt.getSubject()), page, size, status));
    }

    @GetMapping("/{id}")
    public Result<OrderDetailVO> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return Result.success(orderService.detail(Long.valueOf(jwt.getSubject()), id));
    }

    @PostMapping("/{id}/pay")
    public Result<OrderPayVO> pay(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return Result.success(orderService.pay(Long.valueOf(jwt.getSubject()), id));
    }

    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        orderService.cancel(Long.valueOf(jwt.getSubject()), id);
        return Result.success();
    }
}
