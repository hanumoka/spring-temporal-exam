package com.hanumoka.orchestrator.pure.saga;

import com.hanumoka.orchestrator.pure.client.InventoryServiceClient;
import com.hanumoka.orchestrator.pure.client.OrderServiceClient;
import com.hanumoka.orchestrator.pure.client.PaymentServiceClient;
import com.hanumoka.orchestrator.pure.dto.OrderSagaRequest;
import com.hanumoka.orchestrator.pure.dto.OrderSagaResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 주문 Saga 오케스트레이터
 *
 * 정방향 Flow:
 *   T1: 주문 생성 → T2: 재고 예약 → T3: 결제 생성/승인
 *   → T4: 주문 확정 → T5: 재고 확정 → T6: 결제 확정
 *
 * 보상 Flow (역순):
 *   C3: 결제 환불 ← C2: 재고 예약 취소 ← C1: 주문 취소
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderSagaOrchestrator {

    private final OrderServiceClient orderClient;
    private final InventoryServiceClient inventoryClient;
    private final PaymentServiceClient paymentClient;

    /**
     * Saga 실행
     */
    public OrderSagaResult execute(OrderSagaRequest request) {
        // Saga ID 생성 (Semantic Lock 식별용)
        String sagaId = generateSagaId();

        log.info("========== Saga 시작 [sagaId={}] ==========", sagaId);
        log.info("요청: customerId={}, productId={}, quantity={}, amount={}",
                request.customerId(), request.productId(),
                request.quantity(), request.amount());

        // 각 단계의 결과를 추적 (보상 시 필요)
        Long orderId = null;
        boolean stockReserved = false;
        Long paymentId = null;

        try {
            // ===== 정방향 트랜잭션 (Forward) =====

            // T1: 주문 생성
            log.info("[T1] 주문 생성 시작");
            orderId = orderClient.createOrder(request.customerId());
            log.info("[T1] 주문 생성 완료: orderId={}", orderId);

            // T2: 재고 예약 (sagaId 전달 - Semantic Lock)
            log.info("[T2] 재고 예약 시작");
            inventoryClient.reserveStock(request.productId(), request.quantity(), sagaId);
            stockReserved = true;
            log.info("[T2] 재고 예약 완료");

            // T3: 결제 생성 + 승인 (sagaId 전달 - Layer 3 멱등성)
            log.info("[T3] 결제 생성 시작");
            paymentId = paymentClient.createPayment(
                    orderId,
                    request.amount(),
                    request.paymentMethod(),
                    sagaId
            );
            log.info("[T3] 결제 생성 완료: paymentId={}", paymentId);

            log.info("[T3] 결제 승인 시작");
            paymentClient.approvePayment(paymentId, sagaId);
            log.info("[T3] 결제 승인 완료");

            // T4: 주문 확정
            log.info("[T4] 주문 확정 시작");
            orderClient.confirmOrder(orderId);
            log.info("[T4] 주문 확정 완료");

            // T5: 재고 확정 (예약 → 실제 차감, sagaId 전달 - Semantic Lock 검증)
            log.info("[T5] 재고 확정 시작");
            inventoryClient.confirmReservation(request.productId(), request.quantity(), sagaId);
            log.info("[T5] 재고 확정 완료");

            // T6: 결제 확정 (sagaId 전달 - Layer 3 멱등성)
            log.info("[T6] 결제 확정 시작");
            paymentClient.confirmPayment(paymentId, sagaId);
            log.info("[T6] 결제 확정 완료");

            log.info("========== Saga 성공 [sagaId={}] ==========", sagaId);
            return OrderSagaResult.success(orderId, paymentId);

        } catch (Exception e) {
            log.error("========== Saga 실패 [sagaId={}]: {} ==========", sagaId, e.getMessage());

            // ===== 보상 트랜잭션 (Compensation) - 역순 =====
            compensate(orderId, stockReserved, paymentId, request, sagaId);

            return OrderSagaResult.failure(e.getMessage());
        }
    }

    /**
     * 보상 트랜잭션 실행 (역순)
     *
     * 각 보상은 독립적으로 실행 (하나가 실패해도 나머지 계속 진행)
     *
     * @param orderId       주문 ID
     * @param stockReserved 재고 예약 여부
     * @param paymentId     결제 ID
     * @param request       원본 요청
     * @param sagaId        Saga 식별자 (Semantic Lock 해제용)
     */
    private void compensate(Long orderId, boolean stockReserved,
                            Long paymentId, OrderSagaRequest request, String sagaId) {
        log.info("========== 보상 트랜잭션 시작 [sagaId={}] ==========", sagaId);

        // C3: 결제 환불 (결제가 생성된 경우, sagaId 전달 - Layer 3 멱등성)
        if (paymentId != null) {
            try {
                log.info("[C3] 결제 환불 시작: paymentId={}", paymentId);
                paymentClient.refundPayment(paymentId, sagaId);
                log.info("[C3] 결제 환불 완료");
            } catch (Exception e) {
                log.error("[C3] 결제 환불 실패: {}", e.getMessage());
                // 실패해도 다음 보상 계속 진행
            }
        }

        // C2: 재고 예약 취소 (예약이 완료된 경우, sagaId로 Semantic Lock 해제)
        if (stockReserved) {
            try {
                log.info("[C2] 재고 예약 취소 시작: productId={}, quantity={}, sagaId={}",
                        request.productId(), request.quantity(), sagaId);
                inventoryClient.cancelReservation(request.productId(), request.quantity(), sagaId);
                log.info("[C2] 재고 예약 취소 완료");
            } catch (Exception e) {
                log.error("[C2] 재고 예약 취소 실패: {}", e.getMessage());
            }
        }

        // C1: 주문 취소 (주문이 생성된 경우)
        if (orderId != null) {
            try {
                log.info("[C1] 주문 취소 시작: orderId={}", orderId);
                orderClient.cancelOrder(orderId);
                log.info("[C1] 주문 취소 완료");
            } catch (Exception e) {
                log.error("[C1] 주문 취소 실패: {}", e.getMessage());
            }
        }

        log.info("========== 보상 트랜잭션 완료 [sagaId={}] ==========", sagaId);
    }

    /**
     * Saga ID 생성
     *
     * @return SAGA-XXXXXXXX 형식의 고유 식별자
     */
    private String generateSagaId() {
        return "SAGA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
