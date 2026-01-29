-- 결제 테이블 생성
CREATE TABLE payments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '결제 ID',
    payment_key VARCHAR(100) NOT NULL UNIQUE COMMENT '결제 키 (PG사 거래 식별자)',
    order_id BIGINT NOT NULL COMMENT '주문 ID',
    amount DECIMAL(15, 2) NOT NULL COMMENT '결제 금액',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '결제 상태',
    payment_method VARCHAR(20) COMMENT '결제 수단 (CARD, BANK_TRANSFER 등)',
    pg_transaction_id VARCHAR(100) COMMENT 'PG사 거래 ID',
    failed_reason VARCHAR(500) COMMENT '실패 사유',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '낙관적 락 버전',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '생성일시',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',

    INDEX idx_payments_order (order_id),
    INDEX idx_payments_status (status),
    INDEX idx_payments_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='결제';
