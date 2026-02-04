package com.hanumoka.orchestrator.pure.client;

import com.hanumoka.common.dto.ApiResponse;
import com.hanumoka.common.exception.BusinessException;
import com.hanumoka.common.exception.ErrorCode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 재고 서비스 클라이언트
 * <p>
 * Resilience4j 적용:
 * - @CircuitBreaker: 재고 서비스 장애 시 빠른 실패
 * - @Retry: 네트워크 일시 오류 시 자동 재시도
 * <p>
 * 내부 서비스이지만 네트워크 호출이므로 장애 대비 필요
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryServiceClient {

    private final RestClient restClient;
    private static final String BASE_URL = "http://localhost:8082/api/inventory";

    private static final String CIRCUIT_BREAKER_NAME = "default";
    private static final String RETRY_NAME = "default";

    /**
     * 재고 예약
     * <p>
     * ★ Resilience4j 적용
     * - 재고 예약 실패 시 주문 진행 불가
     * - 네트워크 오류 시 재시도
     */
    @CircuitBreaker(name = CIRCUIT_BREAKER_NAME, fallbackMethod = "reserveStockFallback")
    @Retry(name = RETRY_NAME)
    public void reserveStock(Long productId, int quantity, String sagaId) {
        log.debug("[Resilience4j] 재고 예약 시도: productId={}, quantity={}, sagaId={}",
                productId, quantity, sagaId);

        restClient.post()
                .uri(BASE_URL + "/{productId}/reserve?quantity={quantity}&sagaId={sagaId}",
                        productId, quantity, sagaId)
                .retrieve()
                .body(ApiResponse.class);

        log.info("재고 예약 완료: productId={}, quantity={}, sagaId={}", productId, quantity, sagaId);
    }

    private void reserveStockFallback(Long productId, int quantity, String sagaId, Exception ex) {
        log.error("[Fallback] 재고 예약 실패 - productId={}, quantity={}, sagaId={}, 원인: {}",
                productId, quantity, sagaId, ex.getMessage());
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE.toErrorInfo());
    }

    /**
     * 예약 확정
     */
    @CircuitBreaker(name = CIRCUIT_BREAKER_NAME, fallbackMethod = "confirmReservationFallback")
    @Retry(name = RETRY_NAME)
    public void confirmReservation(Long productId, int quantity, String sagaId) {
        log.debug("[Resilience4j] 재고 확정 시도: productId={}, quantity={}", productId, quantity);

        restClient.post()
                .uri(BASE_URL + "/{productId}/confirm?quantity={quantity}&sagaId={sagaId}",
                        productId, quantity, sagaId)
                .retrieve()
                .body(ApiResponse.class);

        log.info("재고 확정 완료: productId={}, quantity={}", productId, quantity);
    }

    private void confirmReservationFallback(Long productId, int quantity, String sagaId, Exception ex) {
        log.error("[Fallback] 재고 확정 실패 - productId={}, quantity={}, sagaId:{} 원인: {}",
                productId, quantity, sagaId, ex.getMessage());
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE.toErrorInfo());
    }

    /**
     * 예약 취소 (보상 트랜잭션)
     * <p>
     * ★ 보상은 반드시 성공해야 함
     *
     * @param productId 상품 ID
     * @param quantity  취소 수량
     * @param sagaId    Saga 식별자 (Semantic Lock 검증용)
     */
    @CircuitBreaker(name = CIRCUIT_BREAKER_NAME, fallbackMethod = "cancelReservationFallback")
    @Retry(name = RETRY_NAME)
    public void cancelReservation(Long productId, int quantity, String sagaId) {
        log.debug("[Resilience4j] 재고 예약 취소 시도: productId={}, quantity={}, sagaId={}",
                productId, quantity, sagaId);

        restClient.post()
                .uri(BASE_URL + "/{productId}/cancel?quantity={quantity}&sagaId={sagaId}",
                        productId, quantity, sagaId)
                .retrieve()
                .body(ApiResponse.class);

        log.info("재고 예약 취소 완료 (보상): productId={}, quantity={}, sagaId={}", productId, quantity, sagaId);
    }

    private void cancelReservationFallback(Long productId, int quantity, String sagaId, Exception ex) {
        log.error("[Fallback][CRITICAL] 재고 예약 취소 실패 - 수동 처리 필요! productId={}, quantity={}, sagaId={}, 원인: {}",
                productId, quantity, sagaId, ex.getMessage());
        // TODO: Dead Letter Queue에 저장
        throw new BusinessException(ErrorCode.COMPENSATION_FAILED.toErrorInfo());
    }
}
