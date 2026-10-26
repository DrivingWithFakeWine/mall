package com.dking.shop.exception;

public class BusinessException extends RuntimeException{
    public BusinessException(String messages) {
        super(messages);
    }
}
