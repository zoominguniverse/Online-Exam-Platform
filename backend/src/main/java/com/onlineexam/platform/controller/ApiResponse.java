package com.onlineexam.platform.controller;

import java.util.UUID;

public record ApiResponse<T>(Object code, String message, T data, String requestId) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "OK", data, com.onlineexam.platform.configuration.RequestContext.id().toString());
    }

    public static ApiResponse<Void> error(String code, String message) {
        return new ApiResponse<>(code, message, null, com.onlineexam.platform.configuration.RequestContext.id().toString());
    }
}
