package com.hanumoka.payment.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 테스트용 Fake PG 구현체
 *
 * 설정 가능:
 * - payment.fake.delay-ms: 처리 지연 시간 (기본 100ms)
 * - payment.fake.failure-rate: 실패 확률 0.0~1.0 (기본 0.0)
 */
@Slf4j
@Component
public class FakePaymentGateway implements PaymentGateway {

    @Value("${payment.fake.delay-ms:100}")
    private long delayMs;

    @Value("${payment.fake.failure-rate:0.0}")
    private double failureRate;

    private final Random random = new Random();

    // 2단계 결제용: 홀딩된 금액 저장
    private final Map<String, BigDecimal> authorizedAmounts = new ConcurrentHashMap<>();

    @Override
    public PaymentResult approve(String orderId, BigDecimal amount) {
        log.info("[Fake PG] 1단계 결제 승인 요청 - orderId: {}, amount: {}", orderId, amount);

        simulateDelay();

        if (shouldFail()) {
            log.warn("[Fake PG] 결제 승인 실패 (시뮬레이션)");
            return PaymentResult.failure("PG_ERROR_001", "결제 승인 실패 (시뮬레이션)");
        }

        String pgTransactionId = generateTransactionId();
        log.info("[Fake PG] 결제 승인 완료 - pgTxId: {} ⚠️ 실제 돈이 빠지는 시점!", pgTransactionId);

        return PaymentResult.success(pgTransactionId);
    }

    @Override
    public PaymentResult refund(String pgTransactionId, BigDecimal amount) {
        log.info("[Fake PG] 환불 요청 - pgTxId: {}, amount: {}", pgTransactionId, amount);

        simulateDelay();

        if (shouldFail()) {
            log.warn("[Fake PG] 환불 실패 (시뮬레이션)");
            return PaymentResult.failure("PG_ERROR_002", "환불 실패 (시뮬레이션)");
        }

        log.info("[Fake PG] 환불 완료 - ⚠️ 실제로는 3-5일 후 입금됨");
        return PaymentResult.success(pgTransactionId);
    }

    @Override
    public PaymentResult authorize(String orderId, BigDecimal amount) {
        log.info("[Fake PG] 2단계 결제 - 카드 홀딩 요청 - orderId: {}, amount: {}", orderId, amount);

        simulateDelay();

        if (shouldFail()) {
            log.warn("[Fake PG] 카드 홀딩 실패 (시뮬레이션)");
            return PaymentResult.failure("PG_ERROR_003", "카드 홀딩 실패 (시뮬레이션)");
        }

        String pgTransactionId = generateTransactionId();
        authorizedAmounts.put(pgTransactionId, amount);

        log.info("[Fake PG] 카드 홀딩 완료 - pgTxId: {} ✅ 돈은 아직 안 빠짐 (한도만 차감)",
                pgTransactionId);
        return PaymentResult.success(pgTransactionId);
    }

    @Override
    public PaymentResult capture(String pgTransactionId, BigDecimal amount) {
        log.info("[Fake PG] 2단계 결제 - 실제 청구 요청 - pgTxId: {}", pgTransactionId);

        simulateDelay();

        BigDecimal authorizedAmount = authorizedAmounts.get(pgTransactionId);
        if (authorizedAmount == null) {
            return PaymentResult.failure("PG_ERROR_004", "홀딩된 결제를 찾을 수 없음");
        }

        if (shouldFail()) {
            log.warn("[Fake PG] 실제 청구 실패 (시뮬레이션)");
            return PaymentResult.failure("PG_ERROR_005", "실제 청구 실패 (시뮬레이션)");
        }

        authorizedAmounts.remove(pgTransactionId);
        log.info("[Fake PG] 실제 청구 완료 - ⚠️ 이 시점에 돈이 빠짐!");

        return PaymentResult.success(pgTransactionId);
    }

    @Override
    public PaymentResult voidAuthorization(String pgTransactionId) {
        log.info("[Fake PG] 2단계 결제 - 홀딩 취소 요청 - pgTxId: {}", pgTransactionId);

        simulateDelay();

        authorizedAmounts.remove(pgTransactionId);
        log.info("[Fake PG] 홀딩 취소 완료 - ✅ 즉시 취소, 수수료 없음");

        return PaymentResult.success(pgTransactionId);
    }

    private void simulateDelay() {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean shouldFail() {
        return random.nextDouble() < failureRate;
    }

    private String generateTransactionId() {
        return "PG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}