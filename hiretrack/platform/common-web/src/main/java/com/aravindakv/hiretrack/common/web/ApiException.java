package com.aravindakv.hiretrack.common.web;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final Integer retryAfterSeconds;

    public ApiException(int status, String code, String message, Integer retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static ApiException of(int status, String code, String message) {
        return new ApiException(status, code, message, null);
    }

    public static ApiException badRequest(String code, String message) {
        return of(HttpStatus.BAD_REQUEST.value(), code, message);
    }

    public static ApiException unauthorized(String message) {
        return of(HttpStatus.UNAUTHORIZED.value(), "UNAUTHENTICATED", message);
    }

    public static ApiException notFound(String what) {
        return of(HttpStatus.NOT_FOUND.value(), "NOT_FOUND", what + " not found.");
    }

    public static ApiException conflict(String code, String message) {
        return of(HttpStatus.CONFLICT.value(), code, message);
    }

    public static ApiException conflictRetry(String code, String message, int retryAfterSeconds) {
        return new ApiException(HttpStatus.CONFLICT.value(), code, message, retryAfterSeconds);
    }

    public static ApiException preconditionFailed(String message) {
        return of(HttpStatus.PRECONDITION_FAILED.value(), "PRECONDITION_FAILED", message);
    }

    public static ApiException preconditionRequired(String message) {
        return of(HttpStatus.PRECONDITION_REQUIRED.value(), "PRECONDITION_REQUIRED", message);
    }

    public static ApiException payloadTooLarge(String code, String message) {
        return of(HttpStatus.PAYLOAD_TOO_LARGE.value(), code, message);
    }

    public static ApiException unprocessable(String code, String message) {
        return of(HttpStatus.UNPROCESSABLE_ENTITY.value(), code, message);
    }

    public static ApiException tooManyRequests(String code, String message, int retryAfterSeconds) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS.value(), code, message, retryAfterSeconds);
    }

    public static ApiException upstream(String code, String message) {
        return of(HttpStatus.BAD_GATEWAY.value(), code, message);
    }

    public int status() { return status; }
    public String code() { return code; }
    public Integer retryAfterSeconds() { return retryAfterSeconds; }
    public boolean isClientError() { return status >= 400 && status < 500; }
}