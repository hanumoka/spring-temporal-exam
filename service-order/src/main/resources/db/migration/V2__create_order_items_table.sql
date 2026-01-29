-- 주문 상품 테이블 생성
CREATE TABLE order_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '주문상품 ID',
    order_id BIGINT NOT NULL COMMENT '주문 ID',
    product_id BIGINT NOT NULL COMMENT '상품 ID',
    product_name VARCHAR(200) NOT NULL COMMENT '상품명',
    quantity INT NOT NULL DEFAULT 1 COMMENT '수량',
    unit_price DECIMAL(15, 2) NOT NULL COMMENT '단가',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '생성일시',

    -- FK 선언 시 InnoDB가 order_id 인덱스 자동 생성
    CONSTRAINT fk_order_items_order
        FOREIGN KEY (order_id) REFERENCES orders(id)
        ON DELETE CASCADE,

    -- FK가 없는 컬럼은 명시적 인덱스 필요
    INDEX idx_order_items_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='주문 상품';
