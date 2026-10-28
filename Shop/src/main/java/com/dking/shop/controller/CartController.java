package com.dking.shop.controller;

import com.dking.shop.common.Result;
import com.dking.shop.dto.CartAddDTO;
import com.dking.shop.dto.CartQuantityDTO;
import com.dking.shop.dto.CartSelectDTO;
import com.dking.shop.service.CartService;
import com.dking.shop.vo.CartListVO;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/cart")
public class CartController {

    @Resource
    private CartService cartService;

    @PostMapping("/add")
    public Result<Void> add(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid CartAddDTO dto) {
        cartService.add(Long.valueOf(jwt.getSubject()), dto);
        return Result.success();
    }

    @GetMapping("/list")
    public Result<CartListVO> list(@AuthenticationPrincipal Jwt jwt) {
        return Result.success(cartService.list(Long.valueOf(jwt.getSubject())));
    }

    @PutMapping("/quantity")
    public Result<Void> updateQuantity(@AuthenticationPrincipal Jwt jwt,
                                       @RequestBody @Valid CartQuantityDTO dto) {
        cartService.updateQuantity(Long.valueOf(jwt.getSubject()), dto);
        return Result.success();
    }

    @PutMapping("/select")
    public Result<Void> updateSelected(@AuthenticationPrincipal Jwt jwt,
                                       @RequestBody @Valid CartSelectDTO dto) {
        cartService.updateSelected(Long.valueOf(jwt.getSubject()), dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        cartService.remove(Long.valueOf(jwt.getSubject()), id);
        return Result.success();
    }
}
