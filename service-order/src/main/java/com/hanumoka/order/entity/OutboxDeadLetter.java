package com.hanumoka.order.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Outbox Dead Letter Queue 엔티티
 *
 * <h3>Why DLQ?</h3>
 * <ul>
 *   <li>최대 재시도 횟수를 초과한 이벤트를 별도 테이블로 이동</li>
 *   <li>원본 테이블(outbox_event) 정리 → 성능 유지</li>
 *   <li>실패 이벤트 보관 → 분석/수동 처리 가능</li>
 *   <li>운영자 알림 → 문제 인지</li>
 * </ul>
 *
 * <h3>워크플로우</h3>
 * <pre>
 * FAILED (retry 초과) → DLQ 이동 → 운영자 확인 → 수동 처리 → resolved
 * </pre>
 */
@Entity
@Table(name = "outbox_dead_letter",
        indexes = {
                @Index(name = "idx_dlq_aggregate", columnList = "aggregate_type, aggregate_id"),
                @Index(name = "idx_dlq_failed_at", columnList = "failed_at"),
                @Index(name = "idx_dlq_resolved", columnList = "resolved"),
                @Index(name = "idx_dlq_event_type", columnList = "event_type")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxDeadLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 원본 outbox_event ID
     */
    @Column(name = "original_id", nullable = false)
    private Long originalId;

    /**
     * 도메인 타입 (예: Order, Payment, Inventory)
     */
    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    /**
     * 도메인 ID (예: 주문번호)
     */
    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;

    /**
     * 이벤트 타입 (예: OrderCreated)
     */
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /**
     * 이벤트 데이터 (JSON)
     */
    @Column(nullable = false, columnDefinition = "JSON")
    private String payload;

    /**
     * 총 재시도 횟수
     */
    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    /**
     * 마지막 에러 메시지
     */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /**
     * 원본 이벤트 생성 시간
     */
    @Column(name = "original_created_at", nullable = false)
    private LocalDateTime originalCreatedAt;

    /**
     * DLQ 이동 시간
     */
    @Column(name = "failed_at", nullable = false)
    private LocalDateTime failedAt;

    /**
     * 수동 처리 완료 여부
     */
    @Column(nullable = false)
    private boolean resolved = false;

    /**
     * 수동 처리 완료 시간
     */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    /**
     * 처리 메모 (어떻게 해결했는지)
     */
    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    @Builder
    private OutboxDeadLetter(Long originalId, String aggregateType, String aggregateId,
                             String eventType, String payload, int retryCount,
                             String lastError, LocalDateTime originalCreatedAt) {
        this.originalId = originalId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.retryCount = retryCount;
        this.lastError = lastError;
        this.originalCreatedAt = originalCreatedAt;
        this.failedAt = LocalDateTime.now();
        this.resolved = false;
    }

    /**
     * OutboxEvent → OutboxDeadLetter 변환
     *
     * @param event 실패한 Outbox 이벤트
     * @return DLQ 엔티티
     */
    public static OutboxDeadLetter from(OutboxEvent event) {
        return OutboxDeadLetter.builder()
                .originalId(event.getId())
                .aggregateType(event.getAggregateType())
                .aggregateId(event.getAggregateId())
                .eventType(event.getEventType())
                .payload(event.getPayload())
                .retryCount(event.getRetryCount())
                .lastError(event.getLastError())
                .originalCreatedAt(event.getCreatedAt())
                .build();
    }

    /**
     * 수동 처리 완료 표시
     *
     * @param note 처리 메모
     */
    public void markAsResolved(String note) {
        this.resolved = true;
        this.resolvedAt = LocalDateTime.now();
        this.resolutionNote = note;
    }
}
