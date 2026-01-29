package com.hanumoka.payment.service;

import com.hanumoka.common.exception.BusinessException;
import com.hanumoka.common.exception.ErrorCode;
import com.hanumoka.payment.entity.Payment;
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
     * 실제로는 PG사 API 호출 후 처리
     */
    @Transactional
    public Payment approvePayment(Long paymentId) {
        Payment payment = getPayment(paymentId);

        // TODO: Phase 2에서 Fake PG 구현체로 대체
        String pgTransactionId = "PG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        payment.approve(pgTransactionId);
        log.info("결제 승인 완료: paymentId={}, pgTransactionId={}", paymentId, pgTransactionId);

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
     */
    @Transactional
    public Payment refundPayment(Long paymentId) {
        Payment payment = getPayment(paymentId);
        payment.refund();
        log.info("환불 처리 완료 (보상): paymentId={}", paymentId);
        return payment;
    }

    private String generatePaymentKey() {
        return "PAY-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
    }
}
