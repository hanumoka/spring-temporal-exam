package com.hanumoka.inventory.entity;

import com.hanumoka.common.exception.BusinessException;
import com.hanumoka.common.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "inventories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false, unique = true)
    private Product product;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "reserved_quantity", nullable = false)
    private Integer reservedQuantity;

    @Version
    private Long version;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.quantity == null) {
            this.quantity = 0;
        }
        if (this.reservedQuantity == null) {
            this.reservedQuantity = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    @Builder
    public Inventory(Product product, Integer quantity) {
        this.product = product;
        this.quantity = quantity != null ? quantity : 0;
        this.reservedQuantity = 0;
    }

    /**
     * 가용 재고 (전체 재고 - 예약된 재고)
     */
    public Integer getAvailableQuantity() {
        return quantity - reservedQuantity;
    }

    /**
     * 재고 예약 (Saga Step 1: 주문 생성 시)
     */
    public void reserve(int amount) {
        if (getAvailableQuantity() < amount) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK.toErrorInfo());
        }
        this.reservedQuantity += amount;
    }

    /**
     * 예약 확정 (Saga Step 3: 결제 완료 후)
     * 예약된 수량을 실제 차감으로 전환
     */
    public void confirmReservation(int amount) {
        if (this.reservedQuantity < amount) {
            throw new IllegalStateException("예약된 수량보다 확정 수량이 큽니다.");
        }
        this.reservedQuantity -= amount;
        this.quantity -= amount;
    }

    /**
     * 예약 취소 (보상 트랜잭션: 결제 실패 시)
     */
    public void cancelReservation(int amount) {
        this.reservedQuantity -= amount;
        if (this.reservedQuantity < 0) {
            this.reservedQuantity = 0;
        }
    }

    /**
     * 재고 추가 (입고)
     */
    public void addStock(int amount) {
        this.quantity += amount;
    }
}
