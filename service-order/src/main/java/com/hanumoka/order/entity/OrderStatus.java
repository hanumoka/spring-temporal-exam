package com.hanumoka.order.entity;

/**
 * 주문 상태
 */
public enum OrderStatus {
    PENDING,      // 주문 생성됨 (결제 대기)
    CONFIRMED,    // 주문 확정 (결제 완료, 재고 차감 완료)
    COMPLETED,    // 주문 완료 (배송 완료)
    CANCELLED     // 주문 취소
}
