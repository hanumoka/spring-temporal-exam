-- ============================================================================
-- V4: Outbox 이벤트 테이블에 PROCESSING 관련 컬럼 추가
-- ============================================================================
-- Why?
--   1. PROCESSING 상태 도입으로 중복 발행 방지
--   2. 지수 백오프 계산을 위한 lastFailedAt 필드 추가
--   3. PROCESSING 타임아웃 감지를 위한 processedAt 필드 추가
-- ============================================================================

-- 1. 처리 시작 시간 컬럼 추가 (PROCESSING 타임아웃 감지용)
ALTER TABLE outbox_event
    ADD COLUMN processed_at TIMESTAMP NULL COMMENT 'PROCESSING 상태 시작 시간 (타임아웃 감지용)';

-- 2. 마지막 실패 시간 컬럼 추가 (지수 백오프 계산용)
ALTER TABLE outbox_event
    ADD COLUMN last_failed_at TIMESTAMP NULL COMMENT '마지막 실패 시간 (지수 백오프 계산용)';

-- 3. PROCESSING 타임아웃 감지를 위한 인덱스 추가
CREATE INDEX idx_outbox_processing_timeout
    ON outbox_event (status, processed_at)
    COMMENT 'PROCESSING 타임아웃 이벤트 조회용';
