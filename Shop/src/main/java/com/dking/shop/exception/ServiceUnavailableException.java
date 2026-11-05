package com.dking.shop.exception;

/** 依赖服务不可用（如秒杀闸门 Redis），映射为 HTTP 503。 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }
}
