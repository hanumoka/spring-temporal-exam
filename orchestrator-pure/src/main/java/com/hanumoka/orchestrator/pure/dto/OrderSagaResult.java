package com.hanumoka.orchestrator.pure.dto;

public record OrderSagaResult(
        boolean success,
        Long orderId,
        Long paymentId,
        String errorMessage
) {
    public static OrderSagaResult success(Long orderId, Long
            paymentId) {
        return new OrderSagaResult(true, orderId, paymentId, null);
    }

    public static OrderSagaResult failure(String errorMessage) {
        return new OrderSagaResult(false, null, null, errorMessage);
    }
}