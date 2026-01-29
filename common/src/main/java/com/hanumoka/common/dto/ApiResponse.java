package com.hanumoka.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    private boolean success;
    private T data;
    private ErrorInfo errorInfo;

    public static <T> ApiResponse<T> success() {
        return ApiResponse.<T>builder()
                .success(true)
                .build();
    }

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .build();
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .errorInfo(ErrorInfo.of(code, message))
                .build();
    }

    public static <T> ApiResponse<T> fail(ErrorInfo errorInfo) {
        return ApiResponse.<T>builder()
                .success(false)
                .errorInfo(errorInfo)
                .build();
    }
}
