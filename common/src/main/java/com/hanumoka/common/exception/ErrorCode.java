package com.hanumoka.common.exception;


import com.hanumoka.common.dto.ErrorInfo;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ErrorCode {
    // 공통
    INVALID_INPUT("COMMON_001", "잘못된 입력입니다"),
    INTERNAL_ERROR("COMMON_002", "내부 서버 오류가 발생했습니다"),

    // 주문
    ORDER_NOT_FOUND("ORDER_001", "주문을 찾을 수 없습니다"),

    // 재고
    INSUFFICIENT_STOCK("INVENTORY_001", "재고가 부족합니다"),

    // 결제
    PAYMENT_FAILED("PAYMENT_001", "결제에 실패했습니다"),
    ;

    private final String code;
    private final String message;

    public ErrorInfo toErrorInfo() {
        return ErrorInfo.of(this.code, this.message);
    }
}
