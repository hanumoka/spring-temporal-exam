package com.hanumoka.payment.service;

import com.hanumoka.common.exception.BusinessException;
import com.hanumoka.common.exception.ErrorCode;
import com.hanumoka.payment.entity.Payment;
import com.hanumoka.payment.gateway.PaymentGateway;
import com.hanumoka.payment.gateway.PaymentResult;
import com.hanumoka.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;  // 추가

    /**
     * 결제 생성
     */
    @Transactional
    public Payment createPayment(Long orderId, BigDecimal amount, String paymentMethod) {
        String paymentKey = generatePaymentKey();

        Payment payment = Payment.builder()
                .paymentKey(paymentKey)
                .orderId(orderId)
                .amount(amount)
                .paymentMethod(paymentMethod)
                .build();

        Payment savedPayment = paymentRepository.save(payment);
        log.info("결제 생성 완료: paymentKey={}, orderId={}, amount={}",
                paymentKey, orderId, amount);

        return savedPayment;
    }

    /**
     * 결제 조회 (ID)
     */
    public Payment getPayment(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_FAILED.toErrorInfo()));
    }

    /**
     * 결제 조회 (결제키)
     */
    public Payment getPaymentByPaymentKey(String paymentKey) {
        return paymentRepository.findByPaymentKey(paymentKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_FAILED.toErrorInfo()));
    }

    /**
     * 주문별 결제 조회
     */
    public Payment getPaymentByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_FAILED.toErrorInfo()));
    }

    /**
     * 결제 승인 처리 (Saga Step)
     * Fake PG를 통한 결제 승인
     */
    @Transactional
    public Payment approvePayment(Long paymentId) {
        Payment payment = getPayment(paymentId);

        // Fake PG 호출 (1단계 결제: 즉시 승인)
        PaymentResult result = paymentGateway.approve(
                payment.getOrderId().toString(),
                payment.getAmount()
        );

        if (!result.success()) {
            log.error("PG 결제 승인 실패: paymentId={}, error={}", paymentId, result.errorMessage());
            throw new BusinessException(ErrorCode.PAYMENT_FAILED.toErrorInfo());
        }

        payment.approve(result.pgTransactionId());
        log.info("결제 승인 완료: paymentId={}, pgTransactionId={}", paymentId,
                result.pgTransactionId());

        return payment;
    }

    /**
     * 결제 확정 (Saga Step)
     */
    @Transactional
    public Payment confirmPayment(Long paymentId) {
        Payment payment = getPayment(paymentId);
        payment.confirm();
        log.info("결제 확정 완료: paymentId={}", paymentId);
        return payment;
    }

    /**
     * 결제 실패 처리
     */
    @Transactional
    public Payment failPayment(Long paymentId, String reason) {
        Payment payment = getPayment(paymentId);
        payment.fail(reason);
        log.info("결제 실패 처리: paymentId={}, reason={}", paymentId, reason);
        return payment;
    }

    /**
     * 환불 처리 - 보상 트랜잭션 (Saga Compensation)
     * Fake PG를 통한 환불 처리
     */
    @Transactional
    public Payment refundPayment(Long paymentId) {
        Payment payment = getPayment(paymentId);

        // PG 환불 호출 (pgTransactionId가 있는 경우에만)
        if (payment.getPgTransactionId() != null) {
            PaymentResult result = paymentGateway.refund(
                    payment.getPgTransactionId(),
                    payment.getAmount()
            );

            if (!result.success()) {
                log.warn("PG 환불 실패 (보상 트랜잭션은 계속 진행): paymentId={}, error={}",
                        paymentId, result.errorMessage());
                // 환불 실패 시에도 내부 상태는 변경 (보상 트랜잭션은 반드시 완료해야 함)
            }
        }

        payment.refund();
        log.info("환불 처리 완료 (보상): paymentId={}", paymentId);
        return payment;
    }

    private String generatePaymentKey() {
        return "PAY-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
    }
}