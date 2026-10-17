package com.campuslife.common.api;

import com.campuslife.common.web.TraceContext;

public record ApiResponse<T>(String code, String message, T data, String traceId) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("OK", "成功", data, TraceContext.currentId());
    }

    public static ApiResponse<Void> failure(String code, String message) {
        return new ApiResponse<>(code, message, null, TraceContext.currentId());
    }
}
