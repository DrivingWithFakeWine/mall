package com.dking.product.exception;

public class BusinessException extends RuntimeException{
    public BusinessException(String messages) {
        super(messages);
    }
}
