package com.hanumoka.common.exception;

import com.hanumoka.common.dto.ErrorInfo;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final ErrorInfo errorInfo;

    public BusinessException(String message, ErrorInfo errorInfo) {
        super(message);
        this.errorInfo = errorInfo;
    }

    public BusinessException(String message, Throwable cause, ErrorInfo errorInfo) {
        super(message, cause);
        this.errorInfo = errorInfo;
    }

    public BusinessException(ErrorInfo errorInfo) {
        super(errorInfo.getMessage());
        this.errorInfo = errorInfo;
    }
}
