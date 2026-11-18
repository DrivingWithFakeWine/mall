package com.dking.product.controller;

import com.dking.product.common.Result;
import com.dking.product.dto.StockOpRequest;
import com.dking.product.dto.StockOpResult;
import com.dking.product.exception.StockOpException;
import com.dking.product.service.StockCommandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;

/**
 * 库存写操作（内部 RPC）。
 *
 * <p><b>状态码约定</b>（和 C 端接口的「HTTP 200 + Result.code」不同）：</p>
 * <ul>
 *   <li>200 —— 成功，包括幂等重试（skipped &gt; 0）</li>
 *   <li>409 —— 业务拒绝：库存不足、数量冲突、SKU 不存在。**不要重试**</li>
 *   <li>5xx —— 系统故障。**可以重试**，幂等键保证不重复扣</li>
 * </ul>
 *
 * <p>跨进程调用必须让调用方靠状态码就能判断「该不该重试」，
 * 否则每次都要解析响应体里的 code，很容易写成无脑重试。</p>
 */
@RestController
@RequestMapping("/internal/product/stock")
public class InternalStockController {

    private static final Logger log = LoggerFactory.getLogger(InternalStockController.class);

    private final StockCommandService stockCommandService;

    public InternalStockController(StockCommandService stockCommandService) {
        this.stockCommandService = stockCommandService;
    }

    @PostMapping("/reserve")
    public ResponseEntity<Result<StockOpResult>> reserve(@RequestBody StockOpRequest request) {
        return execute(() -> stockCommandService.reserve(request));
    }

    @PostMapping("/release")
    public ResponseEntity<Result<StockOpResult>> release(@RequestBody StockOpRequest request) {
        return execute(() -> stockCommandService.release(request));
    }

    @PostMapping("/confirm")
    public ResponseEntity<Result<StockOpResult>> confirm(@RequestBody StockOpRequest request) {
        return execute(() -> stockCommandService.confirm(request));
    }

    @PostMapping("/sales")
    public ResponseEntity<Result<StockOpResult>> sales(@RequestBody StockOpRequest request) {
        return execute(() -> stockCommandService.sales(request));
    }

    private ResponseEntity<Result<StockOpResult>> execute(Supplier<StockOpResult> action) {
        try {
            return ResponseEntity.ok(Result.success(action.get()));
        } catch (StockOpException e) {
            log.warn("库存操作被拒绝: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Result.error(HttpStatus.CONFLICT.value(), e.getMessage()));
        }
    }
}
