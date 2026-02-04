-- Semantic Lock 필드 추가
-- 재고 예약 상태를 비즈니스 레벨에서 관리

ALTER TABLE inventories
    ADD COLUMN reservation_status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE' COMMENT '예약 상태 (AVAILABLE, RESERVING, RESERVED)',
    ADD COLUMN saga_id VARCHAR(50) NULL COMMENT '현재 작업 중인 Saga ID',
    ADD COLUMN lock_acquired_at TIMESTAMP NULL COMMENT 'Semantic Lock 획득 시간';

-- 인덱스 추가 (saga_id로 조회 가능)
CREATE INDEX idx_inventories_saga_id ON inventories (saga_id);