package com.hanumoka.orchestrator.temporal.dto;

/**
 * 주문 결과 DTO
 *
 * <p>Temporal Workflow의 반환값</p>
 */
public record OrderResult(
        boolean success,
        Long orderId,
        Long paymentId,
        String errorMessage,
        String workflowId  // Temporal Workflow ID 추가
) {
    public static OrderResult success(Long orderId, Long paymentId, String workflowId) {
        return new OrderResult(true, orderId, paymentId, null, workflowId);
    }

    public static OrderResult failure(String errorMessage, String workflowId) {
        return new OrderResult(false, null, null, errorMessage, workflowId);
    }
}
