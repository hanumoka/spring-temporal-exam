package com.hanumoka.orchestrator.pure.dto;

import java.math.BigDecimal;

public record OrderSagaRequest(
        Long customerId,
        Long productId,
        int quantity,
        BigDecimal amount,
        String paymentMethod
) {
    public OrderSagaRequest {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            paymentMethod = "CARD";
        }
    }
}