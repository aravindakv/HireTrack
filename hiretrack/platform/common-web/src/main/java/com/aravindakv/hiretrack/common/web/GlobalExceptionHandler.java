package com.aravindakv.hiretrack.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> api(ApiException e) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.status());
        if (e.retryAfterSeconds() != null) {
            builder.header(HttpHeaders.RETRY_AFTER, e.retryAfterSeconds().toString());
        }
        return builder.body(ErrorResponse.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return status(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ErrorResponse> invalidParams(HandlerMethodValidationException e) {
        return status(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "One or more request parameters are invalid.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
        return status(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "The request body is not valid JSON for this endpoint.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> typeMismatch(MethodArgumentTypeMismatchException e) {
        return status(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", e.getName() + " has an invalid value.");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ErrorResponse> missingHeader(MissingRequestHeaderException e) {
        String code = switch (e.getHeaderName()) {
            case "Idempotency-Key" -> "IDEMPOTENCY_KEY_REQUIRED";
            case "If-Match" -> "PRECONDITION_REQUIRED";
            default -> "MISSING_HEADER";
        };
        HttpStatus status = "If-Match".equals(e.getHeaderName()) ? HttpStatus.PRECONDITION_REQUIRED : HttpStatus.BAD_REQUEST;
        return status(status, code, "Header " + e.getHeaderName() + " is required.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> tooLarge(MaxUploadSizeExceededException e) {
        return status(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "The file is larger than the allowed size.");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)       // includes JPA @Version conflicts
    ResponseEntity<ErrorResponse> optimisticLock(OptimisticLockingFailureException e) {
        return status(HttpStatus.PRECONDITION_FAILED, "PRECONDITION_FAILED",
                "This record was changed by another request. Reload it and try again.");
    }

    @ExceptionHandler(ResourceAccessException.class)
    ResponseEntity<ErrorResponse> upstreamUnavailable(ResourceAccessException e) {
        log.warn("Downstream call failed: {}", e.getMessage());
        return status(HttpStatus.BAD_GATEWAY, "UPSTREAM_UNAVAILABLE", "A dependent service did not respond. Try again.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> noRoute(NoResourceFoundException e) {
        return status(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such endpoint.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> method(HttpRequestMethodNotSupportedException e) {
        return status(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "This method is not supported here.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return status(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong on our side. Try again.");
    }

    private static ResponseEntity<ErrorResponse> status(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
    }
}