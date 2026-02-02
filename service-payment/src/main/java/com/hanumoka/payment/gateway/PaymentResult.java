package com.hanumoka.payment.gateway;

import java.time.LocalDateTime;

/**
 * PG 응답 결과
 */
public record PaymentResult(
        boolean success,
        String pgTransactionId,
        String errorCode,
        String errorMessage,
        LocalDateTime processedAt
) {
    public static PaymentResult success(String pgTransactionId) {
        return new PaymentResult(true, pgTransactionId, null, null, LocalDateTime.now());
    }

    public static PaymentResult failure(String errorCode, String errorMessage) {
        return new PaymentResult(false, null, errorCode, errorMessage, LocalDateTime.now());
    }
}