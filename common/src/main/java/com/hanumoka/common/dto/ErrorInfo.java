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
