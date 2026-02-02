package com.hanumoka.payment.gateway;


import java.math.BigDecimal;

/**
 * 결제 대행사(PG) 추상화 인터페이스
 *
 * 지원 패턴:
 * - 1단계: approve() → refund() (현재 Saga 방식)
 * - 2단계: authorize() → capture()/void() (권장 방식)
 */
public interface PaymentGateway {

    /**
     * 1단계 결제: 즉시 승인 (돈이 바로 빠짐)
     * @return PG 트랜잭션 ID
     */
    PaymentResult approve(String orderId, BigDecimal amount);

    /**
     * 1단계 결제: 환불 (승인 취소, 3-5일 소요)
     */
    PaymentResult refund(String pgTransactionId, BigDecimal amount);

    /**
     * 2단계 결제: 카드 홀딩 (돈 안 빠짐, 한도만 차감)
     */
    PaymentResult authorize(String orderId, BigDecimal amount);

    /**
     * 2단계 결제: 실제 청구 (홀딩된 금액 확정)
     */
    PaymentResult capture(String pgTransactionId, BigDecimal amount);

    /**
     * 2단계 결제: 홀딩 취소 (즉시, 수수료 없음)
     */
    PaymentResult voidAuthorization(String pgTransactionId);
}