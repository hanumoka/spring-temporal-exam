package com.hanumoka.order.entity;

/**
 * Outbox 이벤트 상태
 *
 * <h3>상태 전이</h3>
 * <pre>
 * PENDING → PROCESSING → PUBLISHED (정상)
 *              ↓
 *           FAILED → PENDING (재시도)
 * </pre>
 *
 * <h3>Why PROCESSING?</h3>
 * <ul>
 *   <li>FOR UPDATE SKIP LOCKED 락은 트랜잭션 종료 시 해제됨</li>
 *   <li>락 해제 후에도 다른 인스턴스가 같은 이벤트를 조회하지 못하도록</li>
 *   <li>PROCESSING 상태로 변경하여 "처리 중"임을 명시적으로 표시</li>
 * </ul>
 */
public enum OutboxStatus {

    /**
     * 발행 대기 중
     * - 이벤트 생성 시 초기 상태
     * - Polling Publisher가 조회하는 대상
     */
    PENDING,

    /**
     * 발행 처리 중
     * - Publisher가 이벤트를 가져가서 처리 중인 상태
     * - 다른 인스턴스가 중복 조회하지 않도록 보호
     * - 일정 시간 경과 시 타임아웃 처리 (PENDING으로 복구)
     */
    PROCESSING,

    /**
     * 발행 완료 (Redis Stream으로 전송됨)
     */
    PUBLISHED,

    /**
     * 발행 실패 (재시도 대상)
     */
    FAILED
}
