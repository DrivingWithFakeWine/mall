package com.dking.shop.exception;

/**
 * 资源不存在（HTTP 层仍返回 200，业务码为 404）。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
