package com.dking.product.controller;

import com.dking.product.common.Result;
import com.dking.product.dto.StockErrorResponse;
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
 * <p><b>成功</b>：HTTP 200 + {@code {"code":200,"data":{...}}}</p>
 *
 * <p><b>业务拒绝</b>：HTTP 409 + {@code {"code":"INSUFFICIENT_STOCK","message":"..."}}</p>
 * <ul>
 *   <li>状态码告诉调用方「不要重试」</li>
 *   <li>字符串 code 告诉调用方「具体为什么」，**不要匹配 message**</li>
 * </ul>
 *
 * <p><b>系统故障</b>：5xx。可以重试，幂等键保证不重复扣。</p>
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
    public ResponseEntity<?> reserve(@RequestBody StockOpRequest request) {
        return execute(() -> stockCommandService.reserve(request));
    }

    @PostMapping("/release")
    public ResponseEntity<?> release(@RequestBody StockOpRequest request) {
        return execute(() -> stockCommandService.release(request));
    }

    @PostMapping("/confirm")
    public ResponseEntity<?> confirm(@RequestBody StockOpRequest request) {
        return execute(() -> stockCommandService.confirm(request));
    }

    @PostMapping("/sales")
    public ResponseEntity<?> sales(@RequestBody StockOpRequest request) {
        return execute(() -> stockCommandService.sales(request));
    }

    private ResponseEntity<?> execute(Supplier<StockOpResult> action) {
        try {
            return ResponseEntity.ok(Result.success(action.get()));
        } catch (StockOpException e) {
            log.warn("库存操作被拒绝, code={}, message={}", e.code(), e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new StockErrorResponse(e.code().name(), e.getMessage()));
        }
    }
}
