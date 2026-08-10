package com.llmwiki.common;

import org.springframework.http.HttpStatus;

/**
 * 带稳定错误码和 HTTP 状态的业务异常，供统一异常处理器转换为前端可消费结构。
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    /**
     * 创建业务异常。
     *
     * @param status HTTP 状态
     * @param code 稳定的机器错误码
     * @param message 面向用户或维护者的错误说明
     */
    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** @return 应返回的 HTTP 状态 */
    public HttpStatus status() {
        return status;
    }

    /** @return 稳定的机器错误码 */
    public String code() {
        return code;
    }
}

