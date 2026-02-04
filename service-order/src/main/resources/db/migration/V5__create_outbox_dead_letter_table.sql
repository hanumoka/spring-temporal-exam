-- ============================================================================
-- V5: Outbox Dead Letter Queue (DLQ) 테이블 생성
-- ============================================================================
-- Why?
--   최대 재시도 횟수를 초과한 이벤트를 별도 테이블로 이동
--   1. 원본 테이블(outbox_event) 정리 → 성능 유지
--   2. 실패 이벤트 보관 → 분석/수동 처리 가능
--   3. 운영자 알림 → 문제 인지
-- ============================================================================

CREATE TABLE outbox_dead_letter (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- 원본 이벤트 정보 (그대로 복사)
    original_id         BIGINT NOT NULL COMMENT '원본 outbox_event ID',
    aggregate_type      VARCHAR(100) NOT NULL COMMENT '도메인 타입 (Order, Payment 등)',
    aggregate_id        VARCHAR(100) NOT NULL COMMENT '도메인 ID',
    event_type          VARCHAR(100) NOT NULL COMMENT '이벤트 타입 (OrderCreated 등)',
    payload             JSON NOT NULL COMMENT '이벤트 데이터',

    -- 실패 정보
    retry_count         INT NOT NULL COMMENT '총 재시도 횟수',
    last_error          TEXT COMMENT '마지막 에러 메시지',

    -- 시간 정보
    original_created_at TIMESTAMP NOT NULL COMMENT '원본 이벤트 생성 시간',
    failed_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'DLQ 이동 시간',

    -- 수동 처리 상태
    resolved            BOOLEAN NOT NULL DEFAULT FALSE COMMENT '수동 처리 완료 여부',
    resolved_at         TIMESTAMP NULL COMMENT '수동 처리 완료 시간',
    resolution_note     TEXT NULL COMMENT '처리 메모 (어떻게 해결했는지)',

    -- 인덱스
    INDEX idx_dlq_aggregate (aggregate_type, aggregate_id),
    INDEX idx_dlq_failed_at (failed_at),
    INDEX idx_dlq_resolved (resolved),
    INDEX idx_dlq_event_type (event_type)
) COMMENT 'Outbox 실패 이벤트 보관 (Dead Letter Queue)';
