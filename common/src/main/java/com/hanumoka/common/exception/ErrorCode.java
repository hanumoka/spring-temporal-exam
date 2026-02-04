package com.hanumoka.common.exception;


import com.hanumoka.common.dto.ErrorInfo;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ErrorCode {
    // ========================================
    // 공통
    // ========================================
    INVALID_INPUT("COMMON_001", "잘못된 입력입니다"),
    INTERNAL_ERROR("COMMON_002", "내부 서버 오류가 발생했습니다"),

    // ========================================
    // 인프라/시스템 (락, 서비스 통신)
    // ========================================
    /** 분산 락 획득 실패 (Redis RLock) */
    LOCK_ACQUISITION_FAILED("INFRA_001", "리소스 잠금 획득에 실패했습니다. 잠시 후 다시 시도해주세요."),

    /** 락 획득 중 인터럽트 발생 */
    LOCK_INTERRUPTED("INFRA_002", "요청 처리가 중단되었습니다."),

    /** 외부/내부 서비스 일시 장애 (Fallback) */
    SERVICE_UNAVAILABLE("INFRA_003", "서비스가 일시적으로 불안정합니다. 잠시 후 다시 시도해주세요."),

    /** 보상 트랜잭션 실패 - 관리자 확인 필요 */
    COMPENSATION_FAILED("INFRA_004", "보상 처리에 실패했습니다. 관리자 확인이 필요합니다."),

    // ========================================
    // 주문
    // ========================================
    ORDER_NOT_FOUND("ORDER_001", "주문을 찾을 수 없습니다"),

    // ========================================
    // 재고
    // ========================================
    INSUFFICIENT_STOCK("INVENTORY_001", "재고가 부족합니다"),

    /** Semantic Lock: 다른 Saga가 작업 중 */
    INVENTORY_LOCKED_BY_OTHER_SAGA("INVENTORY_002", "다른 주문이 처리 중입니다. 잠시 후 다시 시도해주세요."),

    /** Semantic Lock: 유효하지 않은 Saga ID */
    INVALID_SAGA_OWNERSHIP("INVENTORY_003", "해당 예약에 대한 권한이 없습니다."),

    // ========================================
    // 결제
    // ========================================
    PAYMENT_FAILED("PAYMENT_001", "결제에 실패했습니다"),
    ;

    private final String code;
    private final String message;

    public ErrorInfo toErrorInfo() {
        return ErrorInfo.of(this.code, this.message);
    }
}
