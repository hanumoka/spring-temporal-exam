package com.hanumoka.common.dto;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class ErrorInfo {

    private String code;
    private String message;

    public static ErrorInfo of(String code, String message) {
        return ErrorInfo.builder()
                .code(code)
                .message(message)
                .build();
    }
}
