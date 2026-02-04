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

    // ========================================
    // Semantic Lock 필드 (신규)
    // ========================================

    @Enumerated(EnumType.STRING)
    @Column(name = "reservation_status", nullable = false)
    private ReservationStatus reservationStatus;

    @Column(name = "saga_id")
    private String sagaId;

    @Column(name = "lock_acquired_at")
    private LocalDateTime lockAcquiredAt;

    // ========================================

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
        // Semantic Lock 초기화
        if (this.reservationStatus == null) {
            this.reservationStatus = ReservationStatus.AVAILABLE;
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
        // Semantic Lock 초기화
        this.reservationStatus = ReservationStatus.AVAILABLE;
    }

    // ========================================
    // Semantic Lock 메소드 (신규)
    // ========================================

    /**
     * Semantic Lock 획득
     * - RLock 획득 후 호출해야 함
     * - 다른 Saga가 작업 중이면 예외 발생
     *
     * @param sagaId 현재 Saga ID
     */
    public void acquireSemanticLock(String sagaId) {
        // 이미 다른 Saga가 작업 중인지 확인 (RESERVING 또는 RESERVED 상태)
        if ((this.reservationStatus == ReservationStatus.RESERVING
                || this.reservationStatus == ReservationStatus.RESERVED)
                && !sagaId.equals(this.sagaId)) {
            throw new BusinessException(ErrorCode.INVENTORY_LOCKED_BY_OTHER_SAGA.toErrorInfo());
        }

        this.reservationStatus = ReservationStatus.RESERVING;
        this.sagaId = sagaId;
        this.lockAcquiredAt = LocalDateTime.now();
    }

    /**
     * Semantic Lock 해제 (성공 시)
     * - 예약 완료 후 RESERVED 상태로 전환
     *
     * @param sagaId 현재 Saga ID
     */
    public void releaseSemanticLockOnSuccess(String sagaId) {
        validateSagaOwnership(sagaId);
        this.reservationStatus = ReservationStatus.RESERVED;
        // sagaId는 유지 (confirm/cancel 시 검증용)
    }

    /**
     * Semantic Lock 해제 (실패/취소 시)
     * - AVAILABLE 상태로 복귀
     *
     * @param sagaId 현재 Saga ID
     */
    public void releaseSemanticLockOnFailure(String sagaId) {
        validateSagaOwnership(sagaId);
        this.reservationStatus = ReservationStatus.AVAILABLE;
        this.sagaId = null;
        this.lockAcquiredAt = null;
    }

    /**
     * Saga 소유권 검증
     *
     * @param sagaId 검증할 Saga ID
     */
    public void validateSagaOwnership(String sagaId) {
        if (this.sagaId != null && !this.sagaId.equals(sagaId)) {
            throw new BusinessException(ErrorCode.INVALID_SAGA_OWNERSHIP.toErrorInfo());
        }
    }

    /**
     * 예약 확정 시 Semantic Lock 완전 해제
     */
    public void clearSemanticLock() {
        this.reservationStatus = ReservationStatus.AVAILABLE;
        this.sagaId = null;
        this.lockAcquiredAt = null;
    }

    // ========================================
    // 기존 메소드 (유지)
    // ========================================

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
