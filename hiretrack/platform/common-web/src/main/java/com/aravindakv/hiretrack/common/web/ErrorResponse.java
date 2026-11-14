package com.aravindakv.hiretrack.common.web;

public record ErrorResponse(ApiError error) {
    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(new ApiError(code, message));
    }
}