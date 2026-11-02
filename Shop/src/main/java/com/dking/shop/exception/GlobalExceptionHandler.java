package com.dking.shop.exception;

import com.dking.shop.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;

/**
 * 业务异常沿用 HTTP 200 + Result.code 的信封约定；
 * Spring MVC 框架异常由 ResponseEntityExceptionHandler 接管，保留正确的 HTTP 状态。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    // 业务异常
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        return Result.error(400, e.getMessage());
    }

    // 资源不存在
    @ExceptionHandler(NotFoundException.class)
    public Result<Void> handleNotFound(NotFoundException e) {
        return Result.error(404, e.getMessage());
    }

    // 参数校验：保持与业务异常一致的 HTTP 200 + code 400 契约
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        List<FieldError> errors = ex.getBindingResult().getFieldErrors();
        String message = errors.isEmpty() ? "参数校验失败" : errors.get(0).getDefaultMessage();
        return ResponseEntity.ok().headers(headers).body(Result.error(400, message));
    }

    // 框架异常：保留 400/404/405/415/500 等 HTTP 状态，响应体统一为 Result
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.is5xxServerError()) {
            log.error("未捕获异常", ex);
        } else {
            log.warn("请求异常, status={}, message={}", statusCode.value(), ex.getMessage());
        }
        return ResponseEntity.status(statusCode)
                .headers(headers)
                .body(Result.error(statusCode.value(), messageFor(statusCode)));
    }

    // 兜底异常
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        log.error("未捕获异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(500, "系统繁忙，请稍后重试"));
    }

    private String messageFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "请求参数错误";
            case 404 -> "资源不存在";
            case 405 -> "请求方法不支持";
            case 406 -> "不支持的响应类型";
            case 415 -> "不支持的 Content-Type";
            default -> status.is5xxServerError() ? "系统繁忙，请稍后重试" : "请求失败";
        };
    }
}
