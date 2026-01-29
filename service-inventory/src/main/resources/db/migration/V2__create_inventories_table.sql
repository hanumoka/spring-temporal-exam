-- 재고 테이블 생성
CREATE TABLE inventories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '재고 ID',
    product_id BIGINT NOT NULL COMMENT '상품 ID',
    quantity INT NOT NULL DEFAULT 0 COMMENT '재고 수량',
    reserved_quantity INT NOT NULL DEFAULT 0 COMMENT '예약된 수량 (주문 확정 전)',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '낙관적 락 버전',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '생성일시',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',

    -- 상품당 재고는 1개만 존재
    CONSTRAINT uk_inventories_product UNIQUE (product_id),

    CONSTRAINT fk_inventories_product
        FOREIGN KEY (product_id) REFERENCES products(id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='재고';
