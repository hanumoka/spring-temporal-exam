-- Outbox 이벤트 테이블 생성
-- Outbox 패턴: DB 저장과 이벤트 발행의 원자성 보장

CREATE TABLE outbox_event
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Outbox 이벤트 ID',
    aggregate_type VARCHAR(100) NOT NULL COMMENT '도메인 타입 (Order, Payment, etc.)',
    aggregate_id   VARCHAR(100) NOT NULL COMMENT '도메인 ID',
    event_type     VARCHAR(100) NOT NULL COMMENT '이벤트 타입 (OrderCreated, OrderCancelled, etc.)',
    payload        JSON         NOT NULL COMMENT '이벤트 데이터 (JSON)',
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '상태 (PENDING, PUBLISHED, FAILED)',
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성일시',
    published_at   TIMESTAMP    NULL COMMENT '발행일시',
    retry_count    INT          NOT NULL DEFAULT 0 COMMENT '재시도 횟수',
    last_error     TEXT         NULL COMMENT '마지막 에러 메시지',

    -- 인덱스
    INDEX idx_outbox_status_created (status, created_at),
    INDEX idx_outbox_aggregate (aggregate_type, aggregate_id),
    INDEX idx_outbox_published (published_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='Outbox 이벤트 (이중 쓰기 문제 해결용)';
