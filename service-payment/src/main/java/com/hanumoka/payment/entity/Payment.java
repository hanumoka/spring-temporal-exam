package com.hanumoka.payment.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_key", nullable = false, unique = true, length = 100)
    private String paymentKey;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    @Column(name = "payment_method", length = 20)
    private String paymentMethod;

    @Column(name = "pg_transaction_id", length = 100)
    private String pgTransactionId;

    @Column(name = "failed_reason", length = 500)
    private String failedReason;

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
        if (this.status == null) {
            this.status = PaymentStatus.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    @Builder
    public Payment(String paymentKey, Long orderId, BigDecimal amount, String paymentMethod) {
        this.paymentKey = paymentKey;
        this.orderId = orderId;
        this.amount = amount;
        this.paymentMethod = paymentMethod;
        this.status = PaymentStatus.PENDING;
    }

    /**
     * 결제 승인 (PG사 승인 완료)
     */
    public void approve(String pgTransactionId) {
        this.status = PaymentStatus.APPROVED;
        this.pgTransactionId = pgTransactionId;
    }

    /**
     * 결제 확정 (주문 확정 후)
     */
    public void confirm() {
        if (this.status != PaymentStatus.APPROVED) {
            throw new IllegalStateException("승인된 결제만 확정할 수 있습니다.");
        }
        this.status = PaymentStatus.CONFIRMED;
    }

    /**
     * 결제 실패
     */
    public void fail(String reason) {
        this.status = PaymentStatus.FAILED;
        this.failedReason = reason;
    }

    /**
     * 환불 (보상 트랜잭션)
     */
    public void refund() {
        if (this.status != PaymentStatus.APPROVED && this.status != PaymentStatus.CONFIRMED) {
            throw new IllegalStateException("승인/확정된 결제만 환불할 수 있습니다.");
        }
        this.status = PaymentStatus.REFUNDED;
    }
}
