package com.hanumoka.orchestrator.pure.client;

import com.hanumoka.common.dto.ApiResponse;
import com.hanumoka.common.exception.BusinessException;
import com.hanumoka.common.exception.ErrorCode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 결제 서비스 클라이언트
 *
 * Resilience4j 적용:
 * - @CircuitBreaker: 연속 실패 시 빠른 실패 (연쇄 장애 방지)
 * - @Retry: 일시적 네트워크 오류 시 자동 재시도
 *
 * 순서: CircuitBreaker → Retry → 실제 호출
 * (CircuitBreaker가 OPEN이면 Retry 없이 즉시 실패)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentServiceClient {

    private final RestClient restClient;
    private static final String BASE_URL = "http://localhost:8083/api/payments";

    private static final String CIRCUIT_BREAKER_NAME = "paymentService";
    private static final String RETRY_NAME = "paymentService";

    /**
     * 결제 생성
     *
     * Resilience4j 미적용 이유:
     * - 결제 생성은 내부 DB 작업 (외부 PG 호출 X)
     * - 실패 시 Saga 보상으로 처리
     */
    public Long createPayment(Long orderId, BigDecimal amount, String paymentMethod) {
        ApiResponse<Map<String, Object>> response = restClient.post()
                .uri(BASE_URL + "?orderId={orderId}&amount={amount}&paymentMethod={paymentMethod}",
                        orderId, amount, paymentMethod)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        Long paymentId = ((Number) response.getData().get("id")).longValue();
        log.info("결제 생성 완료: paymentId={}", paymentId);
        return paymentId;
    }

    /**
     * 결제 승인 (PG 호출)
     *
     * ★ Resilience4j 적용 (외부 PG 호출)
     * - CircuitBreaker: PG 장애 시 빠른 실패
     * - Retry: 네트워크 일시 오류 시 재시도
     *
     * fallbackMethod: 서킷 OPEN 또는 모든 재시도 실패 시 호출
     */
    @CircuitBreaker(name = CIRCUIT_BREAKER_NAME, fallbackMethod = "approvePaymentFallback")
    @Retry(name = RETRY_NAME)
    public void approvePayment(Long paymentId) {
        log.debug("[Resilience4j] 결제 승인 시도: paymentId={}", paymentId);

        restClient.post()
                .uri(BASE_URL + "/{paymentId}/approve", paymentId)
                .retrieve()
                .body(ApiResponse.class);

        log.info("결제 승인 완료: paymentId={}", paymentId);
    }

    /**
     * 결제 승인 Fallback
     * - CircuitBreaker OPEN 상태이거나
     * - 모든 Retry 시도 실패 시 호출
     *
     * @param paymentId 결제 ID
     * @param ex 발생한 예외
     */
    private void approvePaymentFallback(Long paymentId, Exception ex) {
        log.error("[Fallback] 결제 승인 실패 - paymentId={}, 원인: {}", paymentId, ex.getMessage());
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE.toErrorInfo());
    }

    /**
     * 결제 확정
     *
     * Resilience4j 미적용 이유:
     * - 내부 상태 변경 (외부 호출 X)
     * - 실패 시 Saga 보상으로 처리
     */
    public void confirmPayment(Long paymentId) {
        restClient.post()
                .uri(BASE_URL + "/{paymentId}/confirm", paymentId)
                .retrieve()
                .body(ApiResponse.class);

        log.info("결제 확정 완료: paymentId={}", paymentId);
    }

    /**
     * 환불 (보상 트랜잭션)
     *
     * ★ Resilience4j 적용 (외부 PG 호출)
     * - 보상 트랜잭션은 반드시 성공해야 함
     * - 재시도 횟수를 더 늘릴 수 있음 (별도 설정 가능)
     */
    @CircuitBreaker(name = CIRCUIT_BREAKER_NAME, fallbackMethod = "refundPaymentFallback")
    @Retry(name = RETRY_NAME)
    public void refundPayment(Long paymentId) {
        log.debug("[Resilience4j] 결제 환불 시도: paymentId={}", paymentId);

        restClient.post()
                .uri(BASE_URL + "/{paymentId}/refund", paymentId)
                .retrieve()
                .body(ApiResponse.class);

        log.info("결제 환불 완료 (보상): paymentId={}", paymentId);
    }

    /**
     * 환불 Fallback
     * - 보상 실패는 심각한 상황 (수동 처리 필요)
     * - 실무에서는 Dead Letter Queue에 저장하여 나중에 재처리
     */
    private void refundPaymentFallback(Long paymentId, Exception ex) {
        log.error("[Fallback][CRITICAL] 환불 실패 - 수동 처리 필요! paymentId={}, 원인: {}",
                paymentId, ex.getMessage());
        // TODO: Dead Letter Queue에 저장하여 나중에 재처리
        throw new BusinessException(ErrorCode.COMPENSATION_FAILED.toErrorInfo());
    }
}
