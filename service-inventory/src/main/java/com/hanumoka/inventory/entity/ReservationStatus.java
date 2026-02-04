package com.hanumoka.inventory.entity;

/**
 * 재고 예약 상태 (Semantic Lock)
 * <p>
 * 상태 전이:
 * AVAILABLE → RESERVING → RESERVED
 * ↓
 * (실패 시) → AVAILABLE
 */
public enum ReservationStatus {

    /**
     * 예약 가능 상태
     * - 다른 Saga가 작업 중이 아님
     * - 새로운 예약 요청 수락 가능
     */
    AVAILABLE,

    /**
     * 예약 진행 중
     * - 특정 Saga가 예약 작업 수행 중
     * - 다른 Saga의 예약 요청 거부
     * - saga_id가 설정되어 있어야 함
     */
    RESERVING,

    /**
     * 예약 완료
     * - 예약이 확정되어 실제 재고 차감 대기 중
     * - confirmReservation() 호출 시 AVAILABLE로 복귀
     */
    RESERVED
}
