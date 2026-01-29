package com.hanumoka.payment.entity;

/**
 * 결제 상태
 */
public enum PaymentStatus {
    PENDING,    // 결제 대기
    APPROVED,   // 결제 승인됨 (PG사 승인 완료)
    CONFIRMED,  // 결제 확정 (주문 확정 후)
    FAILED,     // 결제 실패
    REFUNDED    // 환불 완료
}
