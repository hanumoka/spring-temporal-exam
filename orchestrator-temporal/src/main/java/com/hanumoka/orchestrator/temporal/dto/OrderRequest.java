package com.hanumoka.orchestrator.temporal.dto;

import java.math.BigDecimal;

/**
 * 주문 요청 DTO
 *
 * <p>Temporal Workflow에 전달되는 입력 데이터</p>
 */
public record OrderRequest(
        Long customerId,
        Long productId,
        int quantity,
        BigDecimal amount,
        String paymentMethod
) {
    public OrderRequest {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            paymentMethod = "CARD";
        }
    }
}
