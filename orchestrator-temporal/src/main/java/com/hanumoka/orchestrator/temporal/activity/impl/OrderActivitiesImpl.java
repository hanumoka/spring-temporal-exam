package com.hanumoka.orchestrator.temporal.activity.impl;

import com.hanumoka.orchestrator.temporal.activity.OrderActivities;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 주문 Activity 구현
 *
 * <h3>역할</h3>
 * <p>Workflow에서 호출되어 실제 REST API 요청을 수행합니다.</p>
 *
 * <h3>orchestrator-pure 대응</h3>
 * <pre>
 * InventoryServiceClient → reserveStock, confirmReservation, cancelReservation
 * PaymentServiceClient   → createPayment, approvePayment, confirmPayment, refundPayment
 * OrderServiceClient     → createOrder, confirmOrder, cancelOrder
 * </pre>
 *
 * <h3>차이점</h3>
 * <ul>
 *   <li>Resilience4j 불필요 → Temporal Activity 재시도 옵션 사용</li>
 *   <li>멱등성 키는 여전히 전달 (sagaId = workflowId)</li>
 * </ul>
 */
@Component("orderActivities")  // Bean 이름 지정 (application.yml에서 참조)
@Slf4j
public class OrderActivitiesImpl implements OrderActivities {

    private final RestClient orderClient;
    private final RestClient inventoryClient;
    private final RestClient paymentClient;

    public OrderActivitiesImpl(
            @Value("${services.order.url}") String orderUrl,
            @Value("${services.inventory.url}") String inventoryUrl,
            @Value("${services.payment.url}") String paymentUrl
    ) {
        this.orderClient = RestClient.builder()
                .baseUrl(orderUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        this.inventoryClient = RestClient.builder()
                .baseUrl(inventoryUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        this.paymentClient = RestClient.builder()
                .baseUrl(paymentUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    // ===== Order Service =====

    @Override
    public Long createOrder(Long customerId) {
        log.info("Activity: createOrder - customerId={}", customerId);

        Map<String, Object> response = orderClient.post()
                .uri("/api/orders")
                .body(Map.of("customerId", customerId))
                .retrieve()
                .body(Map.class);

        Long orderId = ((Number) response.get("orderId")).longValue();
        log.info("Activity: createOrder 완료 - orderId={}", orderId);
        return orderId;
    }

    @Override
    public void confirmOrder(Long orderId) {
        log.info("Activity: confirmOrder - orderId={}", orderId);

        orderClient.put()
                .uri("/api/orders/{orderId}/confirm", orderId)
                .retrieve()
                .toBodilessEntity();

        log.info("Activity: confirmOrder 완료");
    }

    @Override
    public void cancelOrder(Long orderId) {
        if (orderId == null) {
            log.warn("Activity: cancelOrder 스킵 - orderId is null");
            return;
        }

        log.info("Activity: cancelOrder - orderId={}", orderId);

        orderClient.put()
                .uri("/api/orders/{orderId}/cancel", orderId)
                .retrieve()
                .toBodilessEntity();

        log.info("Activity: cancelOrder 완료");
    }

    // ===== Inventory Service =====

    @Override
    public void reserveStock(Long productId, int quantity, String sagaId) {
        log.info("Activity: reserveStock - productId={}, quantity={}, sagaId={}",
                productId, quantity, sagaId);

        inventoryClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/inventory/{productId}/reserve")
                        .queryParam("quantity", quantity)
                        .queryParam("sagaId", sagaId)
                        .build(productId))
                .header("X-Idempotency-Key", sagaId + "-inventory-reserve")
                .retrieve()
                .toBodilessEntity();

        log.info("Activity: reserveStock 완료");
    }

    @Override
    public void confirmReservation(Long productId, int quantity, String sagaId) {
        log.info("Activity: confirmReservation - productId={}, quantity={}, sagaId={}",
                productId, quantity, sagaId);

        inventoryClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/inventory/{productId}/confirm")
                        .queryParam("quantity", quantity)
                        .queryParam("sagaId", sagaId)
                        .build(productId))
                .header("X-Idempotency-Key", sagaId + "-inventory-confirm")
                .retrieve()
                .toBodilessEntity();

        log.info("Activity: confirmReservation 완료");
    }

    @Override
    public void cancelReservation(Long productId, int quantity, String sagaId) {
        log.info("Activity: cancelReservation - productId={}, quantity={}, sagaId={}",
                productId, quantity, sagaId);

        inventoryClient.delete()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/inventory/{productId}/reservation")
                        .queryParam("quantity", quantity)
                        .queryParam("sagaId", sagaId)
                        .build(productId))
                .header("X-Idempotency-Key", sagaId + "-inventory-cancel")
                .retrieve()
                .toBodilessEntity();

        log.info("Activity: cancelReservation 완료");
    }

    // ===== Payment Service =====

    @Override
    public Long createPayment(Long orderId, BigDecimal amount, String paymentMethod, String sagaId) {
        log.info("Activity: createPayment - orderId={}, amount={}, method={}, sagaId={}",
                orderId, amount, paymentMethod, sagaId);

        Map<String, Object> response = paymentClient.post()
                .uri("/api/payments")
                .header("X-Idempotency-Key", sagaId + "-payment-create")
                .body(Map.of(
                        "orderId", orderId,
                        "amount", amount,
                        "paymentMethod", paymentMethod
                ))
                .retrieve()
                .body(Map.class);

        Long paymentId = ((Number) response.get("paymentId")).longValue();
        log.info("Activity: createPayment 완료 - paymentId={}", paymentId);

        return paymentId;
    }

    @Override
    public void approvePayment(Long paymentId, String sagaId) {
        log.info("Activity: approvePayment - paymentId={}, sagaId={}", paymentId, sagaId);

        paymentClient.post()
                .uri("/api/payments/{paymentId}/approve", paymentId)
                .header("X-Idempotency-Key", sagaId + "-payment-approve")
                .retrieve()
                .toBodilessEntity();

        log.info("Activity: approvePayment 완료");
    }

    @Override
    public void confirmPayment(Long paymentId, String sagaId) {
        log.info("Activity: confirmPayment - paymentId={}, sagaId={}", paymentId, sagaId);

        paymentClient.post()
                .uri("/api/payments/{paymentId}/confirm", paymentId)
                .header("X-Idempotency-Key", sagaId + "-payment-confirm")
                .retrieve()
                .toBodilessEntity();

        log.info("Activity: confirmPayment 완료");
    }

    @Override
    public void refundPayment(Long paymentId, String sagaId) {
        if (paymentId == null) {
            log.warn("Activity: refundPayment 스킵 - paymentId is null");
            return;
        }

        log.info("Activity: refundPayment - paymentId={}, sagaId={}", paymentId, sagaId);

        paymentClient.post()
                .uri("/api/payments/{paymentId}/refund", paymentId)
                .header("X-Idempotency-Key", sagaId + "-payment-refund")
                .retrieve()
                .toBodilessEntity();

        log.info("Activity: refundPayment 완료");
    }
}
