package com.hanumoka.orchestrator.pure.client;

import com.hanumoka.common.dto.ApiResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 주문 서비스 클라이언트
 *
 * Resilience4j 적용:
 * - @CircuitBreaker: 주문 서비스 장애 시 빠른 실패
 * - @Retry: 네트워크 일시 오류 시 자동 재시도
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderServiceClient {

    private final RestClient restClient;
    private static final String BASE_URL = "http://localhost:8081/api/orders";

    private static final String CIRCUIT_BREAKER_NAME = "default";
    private static final String RETRY_NAME = "default";

    /**
     * 주문 생성
     *
     * ★ Resilience4j 적용
     * - 주문 생성은 Saga의 첫 단계
     * - 실패 시 전체 Saga 시작 불가
     */
    @CircuitBreaker(name = CIRCUIT_BREAKER_NAME, fallbackMethod = "createOrderFallback")
    @Retry(name = RETRY_NAME)
    public Long createOrder(Long customerId) {
        log.debug("[Resilience4j] 주문 생성 시도: customerId={}", customerId);

        ApiResponse<Map<String, Object>> response = restClient.post()
                .uri(BASE_URL + "?customerId={customerId}", customerId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        Long orderId = ((Number) response.getData().get("id")).longValue();
        log.info("주문 생성 완료: orderId={}", orderId);
        return orderId;
    }

    private Long createOrderFallback(Long customerId, Exception ex) {
        log.error("[Fallback] 주문 생성 실패 - customerId={}, 원인: {}", customerId, ex.getMessage());
        throw new RuntimeException("주문 서비스 일시 장애. 잠시 후 다시 시도해주세요.", ex);
    }

    /**
     * 주문 확정
     */
    @CircuitBreaker(name = CIRCUIT_BREAKER_NAME, fallbackMethod = "confirmOrderFallback")
    @Retry(name = RETRY_NAME)
    public void confirmOrder(Long orderId) {
        log.debug("[Resilience4j] 주문 확정 시도: orderId={}", orderId);

        restClient.post()
                .uri(BASE_URL + "/{orderId}/confirm", orderId)
                .retrieve()
                .body(ApiResponse.class);

        log.info("주문 확정 완료: orderId={}", orderId);
    }

    private void confirmOrderFallback(Long orderId, Exception ex) {
        log.error("[Fallback] 주문 확정 실패 - orderId={}, 원인: {}", orderId, ex.getMessage());
        throw new RuntimeException("주문 확정 실패. 잠시 후 다시 시도해주세요.", ex);
    }

    /**
     * 주문 취소 (보상 트랜잭션)
     *
     * ★ 보상은 반드시 성공해야 함
     */
    @CircuitBreaker(name = CIRCUIT_BREAKER_NAME, fallbackMethod = "cancelOrderFallback")
    @Retry(name = RETRY_NAME)
    public void cancelOrder(Long orderId) {
        log.debug("[Resilience4j] 주문 취소 시도: orderId={}", orderId);

        restClient.post()
                .uri(BASE_URL + "/{orderId}/cancel", orderId)
                .retrieve()
                .body(ApiResponse.class);

        log.info("주문 취소 완료 (보상): orderId={}", orderId);
    }

    private void cancelOrderFallback(Long orderId, Exception ex) {
        log.error("[Fallback][CRITICAL] 주문 취소 실패 - 수동 처리 필요! orderId={}, 원인: {}",
                orderId, ex.getMessage());
        // TODO: Dead Letter Queue에 저장
        throw new RuntimeException("주문 취소 실패. 관리자 확인이 필요합니다.", ex);
    }
}
