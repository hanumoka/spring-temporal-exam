package com.hanumoka.order.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Outbox 이벤트 엔티티
 *
 * <p>Outbox 패턴의 핵심 테이블로, 비즈니스 데이터와 함께 트랜잭션으로 저장됩니다.</p>
 *
 * <h3>Why Outbox?</h3>
 * <ul>
 *   <li>DB 저장 + 이벤트 발행을 원자적으로 처리 불가 (이중 쓰기 문제)</li>
 *   <li>Outbox 테이블에 이벤트를 함께 저장하면 트랜잭션 원자성 보장</li>
 *   <li>별도 프로세스(Polling Publisher)가 Outbox → 메시지 브로커로 발행</li>
 * </ul>
 *
 * <h3>상태 흐름</h3>
 * <pre>
 * PENDING → PUBLISHED (정상)
 * PENDING → FAILED → PENDING (재시도) → PUBLISHED
 * </pre>
 */
@Entity
@Table(name = "outbox_event",
        indexes = {
                @Index(name = "idx_outbox_status_created", columnList = "status, created_at"),
                @Index(name = "idx_outbox_aggregate", columnList = "aggregate_type, aggregate_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 도메인 타입 (예: Order, Payment, Inventory)
     * Redis Stream 토픽 결정에 사용
     */
    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    /**
     * 도메인 ID (예: 주문번호, 결제ID)
     * 같은 ID의 이벤트는 순서 보장 필요
     */
    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;

    /**
     * 이벤트 타입 (예: OrderCreated, OrderCancelled)
     * Consumer가 처리 로직 분기에 사용
     */
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /**
     * 이벤트 데이터 (JSON)
     * 도메인 이벤트의 전체 정보 포함
     */
    @Column(nullable = false, columnDefinition = "JSON")
    private String payload;

    /**
     * 이벤트 상태
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status = OutboxStatus.PENDING;

    /**
     * 이벤트 생성 시간
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 이벤트 발행 완료 시간
     */
    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    /**
     * 재시도 횟수 (지수 백오프에 사용)
     */
    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    /**
     * 마지막 에러 메시지
     */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /**
     * 마지막 실패 시간 (지수 백오프 계산에 사용)
     */
    @Column(name = "last_failed_at")
    private LocalDateTime lastFailedAt;

    /**
     * 처리 시작 시간 (PROCESSING 타임아웃 감지에 사용)
     */
    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Builder
    public OutboxEvent(String aggregateType, String aggregateId,
                       String eventType, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 처리 시작 (PENDING → PROCESSING)
     *
     * <p>락 획득 후 같은 트랜잭션 내에서 호출하여
     * 트랜잭션 종료(락 해제) 후에도 다른 인스턴스가
     * 이 이벤트를 조회하지 못하도록 합니다.</p>
     */
    public void markAsProcessing() {
        this.status = OutboxStatus.PROCESSING;
        this.processedAt = LocalDateTime.now();
    }

    /**
     * 발행 성공 처리 (PROCESSING → PUBLISHED)
     */
    public void markAsPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
    }

    /**
     * 발행 실패 처리 (PROCESSING → FAILED)
     *
     * @param error 에러 메시지
     */
    public void markAsFailed(String error) {
        this.status = OutboxStatus.FAILED;
        this.retryCount++;
        this.lastError = error;
        this.lastFailedAt = LocalDateTime.now();
    }

    /**
     * 재시도를 위해 PENDING 상태로 변경 (FAILED → PENDING)
     */
    public void markForRetry() {
        this.status = OutboxStatus.PENDING;
    }

    /**
     * PROCESSING 타임아웃으로 PENDING 복구
     *
     * <p>Publisher가 비정상 종료된 경우,
     * PROCESSING 상태로 남아있는 이벤트를
     * 일정 시간 후 PENDING으로 복구합니다.</p>
     */
    public void markAsTimedOut() {
        this.status = OutboxStatus.PENDING;
        this.processedAt = null;
    }

    /**
     * 재시도 가능 여부 확인
     *
     * @param maxRetryCount 최대 재시도 횟수
     * @return 재시도 가능하면 true
     */
    public boolean canRetry(int maxRetryCount) {
        return this.retryCount < maxRetryCount;
    }
}
