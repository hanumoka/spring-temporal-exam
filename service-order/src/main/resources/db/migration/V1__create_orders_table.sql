-- 주문 테이블 생성
CREATE TABLE orders
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '주문 ID',
    order_number VARCHAR(50)    NOT NULL UNIQUE COMMENT '주문 번호',
    customer_id  BIGINT         NOT NULL COMMENT '고객 ID',
    status       VARCHAR(20)    NOT NULL DEFAULT 'PENDING' COMMENT '주문상태',
    total_amount DECIMAL(15, 2) NOT NULL DEFAULT 0 COMMENT '총금액',
    version      BIGINT         NOT NULL DEFAULT 0 COMMENT '낙관적 락 버전',
    created_at   TIMESTAMP               DEFAULT CURRENT_TIMESTAMP COMMENT '생성일시',
    updated_at   TIMESTAMP               DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',

    INDEX        idx_orders_customer (customer_id),
    INDEX        idx_orders_status (status),
    INDEX        idx_orders_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='주문';