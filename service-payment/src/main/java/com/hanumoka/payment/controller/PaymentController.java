package com.hanumoka.payment.controller;

import com.hanumoka.common.dto.ApiResponse;
import com.hanumoka.payment.entity.Payment;
import com.hanumoka.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * 결제 생성
     */
    @PostMapping
    public ApiResponse<PaymentResponse> createPayment(
            @RequestParam Long orderId,
            @RequestParam BigDecimal amount,
            @RequestParam(defaultValue = "CARD") String paymentMethod) {
        Payment payment = paymentService.createPayment(orderId, amount, paymentMethod);
        return ApiResponse.success(PaymentResponse.from(payment));
    }

    /**
     * 결제 조회 (ID)
     */
    @GetMapping("/{paymentId}")
    public ApiResponse<PaymentResponse> getPayment(@PathVariable Long paymentId) {
        Payment payment = paymentService.getPayment(paymentId);
        return ApiResponse.success(PaymentResponse.from(payment));
    }

    /**
     * 결제 조회 (주문 ID)
     */
    @GetMapping("/by-order/{orderId}")
    public ApiResponse<PaymentResponse> getPaymentByOrderId(@PathVariable Long orderId) {
        Payment payment = paymentService.getPaymentByOrderId(orderId);
        return ApiResponse.success(PaymentResponse.from(payment));
    }

    /**
     * 결제 승인 (Saga용)
     */
    @PostMapping("/{paymentId}/approve")
    public ApiResponse<PaymentResponse> approvePayment(@PathVariable Long paymentId) {
        Payment payment = paymentService.approvePayment(paymentId);
        return ApiResponse.success(PaymentResponse.from(payment));
    }

    /**
     * 결제 확정 (Saga용)
     */
    @PostMapping("/{paymentId}/confirm")
    public ApiResponse<PaymentResponse> confirmPayment(@PathVariable Long paymentId) {
        Payment payment = paymentService.confirmPayment(paymentId);
        return ApiResponse.success(PaymentResponse.from(payment));
    }

    /**
     * 환불 - 보상 트랜잭션 (Saga용)
     */
    @PostMapping("/{paymentId}/refund")
    public ApiResponse<PaymentResponse> refundPayment(@PathVariable Long paymentId) {
        Payment payment = paymentService.refundPayment(paymentId);
        return ApiResponse.success(PaymentResponse.from(payment));
    }

    // 응답 DTO
    public record PaymentResponse(
            Long id,
            String paymentKey,
            Long orderId,
            String amount,
            String status,
            String paymentMethod,
            String pgTransactionId
    ) {
        public static PaymentResponse from(Payment payment) {
            return new PaymentResponse(
                    payment.getId(),
                    payment.getPaymentKey(),
                    payment.getOrderId(),
                    payment.getAmount().toString(),
                    payment.getStatus().name(),
                    payment.getPaymentMethod(),
                    payment.getPgTransactionId()
            );
        }
    }
}
