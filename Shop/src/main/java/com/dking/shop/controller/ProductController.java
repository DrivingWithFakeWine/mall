package com.dking.shop.controller;

import com.dking.shop.common.Result;
import com.dking.shop.service.ProductService;
import com.dking.shop.vo.PageVO;
import com.dking.shop.vo.ProductListItemVO;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/product")
public class ProductController {

    @Resource
    private ProductService productService;

    @GetMapping("/list")
    public Result<PageVO<ProductListItemVO>> list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long categoryId) {
        return Result.success(productService.list(page, size, categoryId));
    }
}
