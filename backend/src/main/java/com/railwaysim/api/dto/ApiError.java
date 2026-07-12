package com.railwaysim.api.dto;

import java.time.Instant;

/**
 * 统一 API 错误响应结构。
 */
public record ApiError(
    String code,
    String message,
    String field,
    String traceId,
    Instant timestamp
) {
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, null, null, Instant.now());
    }

    public static ApiError of(String code, String message, String field) {
        return new ApiError(code, message, field, null, Instant.now());
    }

    public static ApiError badRequest(String message) {
        return of("BAD_REQUEST", message);
    }

    public static ApiError badRequest(String message, String field) {
        return of("BAD_REQUEST", message, field);
    }

    public static ApiError notFound(String message) {
        return of("NOT_FOUND", message);
    }

    public static ApiError conflict(String message) {
        return of("CONFLICT", message);
    }
}
