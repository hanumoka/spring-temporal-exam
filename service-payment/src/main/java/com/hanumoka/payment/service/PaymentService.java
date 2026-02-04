package com.hanumoka.payment.service;

import com.hanumoka.common.exception.BusinessException;
import com.hanumoka.common.exception.ErrorCode;
import com.hanumoka.payment.entity.Payment;
import com.hanumoka.payment.gateway.PaymentGateway;
import com.hanumoka.payment.gateway.PaymentResult;
import com.hanumoka.payment.repository.PaymentRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final RedissonClient redissonClient;

    // ========================================
    // 세마포어 설정 (PG 동시 호출 제한)
    // ========================================
    private static final String SEMAPHORE_KEY = "semaphore:pg-gateway";

    @Value("${payment.semaphore.permits:10}")
    private int semaphorePermits;

    @Value("${payment.semaphore.wait-seconds:5}")
    private int semaphoreWaitSeconds;

    /**
     * 세마포어 초기화
     * 애플리케이션 시작 시 permits 수 설정
     */
    @PostConstruct
    public void initSemaphore() {
        RSemaphore semaphore = redissonClient.getSemaphore(SEMAPHORE_KEY);
        // trySetPermits: 이미 설정되어 있으면 무시 (멱등성)
        boolean initialized = semaphore.trySetPermits(semaphorePermits);
        if (initialized) {
            log.info("PG 세마포어 초기화 완료: permits={}", semaphorePermits);
        } else {
            log.info("PG 세마포어 이미 존재: currentPermits={}", semaphore.availablePermits());
        }
    }

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
     *
     * ★ RSemaphore로 PG 동시 호출 제한
     * - 동시 permits 수만큼만 PG API 호출 가능
     * - 초과 시 대기 후 타임아웃되면 PG_THROTTLED 에러
     */
    @Transactional
    public Payment approvePayment(Long paymentId) {
        Payment payment = getPayment(paymentId);

        // 세마포어로 PG 호출 제한
        PaymentResult result = executeWithSemaphore(() ->
                paymentGateway.approve(
                        payment.getOrderId().toString(),
                        payment.getAmount()
                )
        );

        if (!result.success()) {
            log.error("PG 결제 승인 실패: paymentId={}, error={}", paymentId, result.errorMessage());
            throw new BusinessException(ErrorCode.PAYMENT_FAILED.toErrorInfo());
        }

        payment.approve(result.pgTransactionId());
        log.info("결제 승인 완료: paymentId={}, pgTransactionId={}", paymentId, result.pgTransactionId());

        return payment;
    }

    /**
     * 세마포어로 보호된 PG 호출 실행
     *
     * @param pgCall PG 호출 로직
     * @return PaymentResult
     */
    private PaymentResult executeWithSemaphore(Supplier<PaymentResult> pgCall) {
        RSemaphore semaphore = redissonClient.getSemaphore(SEMAPHORE_KEY);

        boolean acquired = false;
        try {
            log.debug("세마포어 획득 시도: availablePermits={}", semaphore.availablePermits());
            // tryAcquire(permits, Duration) - Redisson 3.52.0 권장 API (Duration 사용)
            acquired = semaphore.tryAcquire(1, Duration.ofSeconds(semaphoreWaitSeconds));

            if (!acquired) {
                log.warn("세마포어 획득 실패 (타임아웃): waitSeconds={}", semaphoreWaitSeconds);
                throw new BusinessException(ErrorCode.PG_THROTTLED.toErrorInfo());
            }

            log.debug("세마포어 획득 성공: remainingPermits={}", semaphore.availablePermits());
            return pgCall.get();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("세마포어 획득 중 인터럽트 발생");
            throw new BusinessException(ErrorCode.LOCK_INTERRUPTED.toErrorInfo());
        } finally {
            if (acquired) {
                semaphore.release();
                log.debug("세마포어 반환 완료: availablePermits={}", semaphore.availablePermits());
            }
        }
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
     *
     * ★ 보상 트랜잭션 특성:
     * - 세마포어 획득 실패해도 내부 상태는 변경 (보상은 반드시 완료)
     * - PG 환불 실패해도 내부 상태는 변경 (나중에 수동 처리)
     */
    @Transactional
    public Payment refundPayment(Long paymentId) {
        Payment payment = getPayment(paymentId);

        // PG 환불 호출 (pgTransactionId가 있는 경우에만)
        if (payment.getPgTransactionId() != null) {
            try {
                // 세마포어로 PG 호출 제한
                PaymentResult result = executeWithSemaphore(() ->
                        paymentGateway.refund(
                                payment.getPgTransactionId(),
                                payment.getAmount()
                        )
                );

                if (!result.success()) {
                    log.warn("PG 환불 실패 (보상 트랜잭션은 계속 진행): paymentId={}, error={}",
                            paymentId, result.errorMessage());
                    // TODO: Dead Letter Queue에 저장하여 나중에 수동 처리
                }
            } catch (BusinessException e) {
                // 세마포어 획득 실패해도 보상 트랜잭션은 계속 진행
                log.warn("세마포어 획득 실패 (보상 트랜잭션은 계속 진행): paymentId={}, error={}",
                        paymentId, e.getMessage());
                // TODO: Dead Letter Queue에 저장하여 나중에 PG 환불 재시도
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