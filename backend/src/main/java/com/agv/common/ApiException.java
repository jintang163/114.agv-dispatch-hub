package com.agv.common;

/** 业务异常，由 GlobalExceptionHandler 统一转 400 */
public class ApiException extends RuntimeException {
    public ApiException(String message) {
        super(message);
    }
}
