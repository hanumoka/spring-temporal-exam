package com.hanumoka.orchestrator.pure.controller;

import com.hanumoka.common.dto.ApiResponse;
import com.hanumoka.orchestrator.pure.dto.OrderSagaRequest;
import com.hanumoka.orchestrator.pure.dto.OrderSagaResult;
import com.hanumoka.orchestrator.pure.saga.OrderSagaOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.hanumoka.common.idempotency.Idempotent;

@RestController
@RequestMapping("/api/saga")
@RequiredArgsConstructor
@Slf4j
public class SagaController {

    private final OrderSagaOrchestrator orchestrator;

    /**
     * 주문 Saga 실행
     *
     * POST /api/saga/order
     * Headers: X-Idempotency-Key: {unique-key}
     * {
     *   "customerId": 1,
     *   "productId": 1,
     *   "quantity": 2,
     *   "amount": 20000,
     *   "paymentMethod": "CARD"
     * }
     */
    @PostMapping("/order")
    @Idempotent(prefix = "saga-order", ttlSeconds = 86400)  // 24시간 유지
    public ResponseEntity<ApiResponse<OrderSagaResult>> executeOrderSaga(
            @RequestBody OrderSagaRequest request) {
        log.info("주문 Saga 요청 수신: {}", request);

        OrderSagaResult result = orchestrator.execute(request);

        if (result.success()) {
            log.info("주문 Saga 성공: orderId={}, paymentId={}",
                    result.orderId(), result.paymentId());
            return ResponseEntity.ok(ApiResponse.success(result));
        } else {
            log.warn("주문 Saga 실패: {}", result.errorMessage());
            return ResponseEntity.ok(ApiResponse.fail("SAGA_FAILED", result.errorMessage()));
        }
    }
}