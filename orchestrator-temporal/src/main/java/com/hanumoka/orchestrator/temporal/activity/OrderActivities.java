package com.hanumoka.orchestrator.temporal.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.math.BigDecimal;

/**
 * 주문 Activity 인터페이스
 *
 * <h3>Activity 특징</h3>
 * <ul>
 *   <li>실제 외부 호출, I/O 작업 수행</li>
 *   <li>Workflow에서 호출됨</li>
 *   <li>실패 시 자동 재시도 (옵션 설정에 따라)</li>
 *   <li>비결정적 코드 허용</li>
 * </ul>
 *
 * <h3>orchestrator-pure 대응</h3>
 * <pre>
 * orchestrator-pure: InventoryServiceClient, PaymentServiceClient, OrderServiceClient
 * orchestrator-temporal: OrderActivities (통합)
 * </pre>
 */
@ActivityInterface
public interface OrderActivities {

    // ===== Order Service =====

    /**
     * 주문 생성 (T1)
     *
     * @param customerId 고객 ID
     * @return 생성된 주문 ID
     */
    @ActivityMethod
    Long createOrder(Long customerId);

    /**
     * 주문 확정 (T4)
     *
     * @param orderId 주문 ID
     */
    @ActivityMethod
    void confirmOrder(Long orderId);

    /**
     * 주문 취소 (C1 - 보상)
     *
     * @param orderId 주문 ID
     */
    @ActivityMethod
    void cancelOrder(Long orderId);

    // ===== Inventory Service =====

    /**
     * 재고 예약 (T2)
     *
     * @param productId 상품 ID
     * @param quantity  수량
     * @param sagaId    Saga ID (Semantic Lock)
     */
    @ActivityMethod
    void reserveStock(Long productId, int quantity, String sagaId);

    /**
     * 재고 확정 (T5)
     *
     * @param productId 상품 ID
     * @param quantity  수량
     * @param sagaId    Saga ID
     */
    @ActivityMethod
    void confirmReservation(Long productId, int quantity, String sagaId);

    /**
     * 재고 예약 취소 (C2 - 보상)
     *
     * @param productId 상품 ID
     * @param quantity  수량
     * @param sagaId    Saga ID
     */
    @ActivityMethod
    void cancelReservation(Long productId, int quantity, String sagaId);

    // ===== Payment Service =====

    /**
     * 결제 생성 (T3-1)
     *
     * @param orderId       주문 ID
     * @param amount        결제 금액
     * @param paymentMethod 결제 방법
     * @param sagaId        Saga ID (멱등성)
     * @return 생성된 결제 ID
     */
    @ActivityMethod
    Long createPayment(Long orderId, BigDecimal amount, String paymentMethod, String sagaId);

    /**
     * 결제 승인 (T3-2)
     *
     * @param paymentId 결제 ID
     * @param sagaId    Saga ID
     */
    @ActivityMethod
    void approvePayment(Long paymentId, String sagaId);

    /**
     * 결제 확정 (T6)
     *
     * @param paymentId 결제 ID
     * @param sagaId    Saga ID
     */
    @ActivityMethod
    void confirmPayment(Long paymentId, String sagaId);

    /**
     * 결제 환불 (C3 - 보상)
     *
     * @param paymentId 결제 ID
     * @param sagaId    Saga ID
     */
    @ActivityMethod
    void refundPayment(Long paymentId, String sagaId);
}
