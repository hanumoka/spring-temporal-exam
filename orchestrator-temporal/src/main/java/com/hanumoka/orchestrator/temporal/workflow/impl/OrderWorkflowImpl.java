package com.hanumoka.orchestrator.temporal.workflow.impl;

import com.hanumoka.orchestrator.temporal.activity.OrderActivities;
import com.hanumoka.orchestrator.temporal.dto.OrderRequest;
import com.hanumoka.orchestrator.temporal.dto.OrderResult;
import com.hanumoka.orchestrator.temporal.workflow.OrderWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;

/**
 * 주문 Workflow 구현
 *
 * <h3>orchestrator-pure와 비교</h3>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────────────┐
 * │  orchestrator-pure (167줄)          │  orchestrator-temporal (~100줄)       │
 * ├─────────────────────────────────────────────────────────────────────────────┤
 * │  - 수동 보상 순서 관리                │  - saga.addCompensation() 자동 관리   │
 * │  - try-catch로 보상 호출             │  - saga.compensate() 자동 역순 실행   │
 * │  - 보상 실패 시 로그만 남김           │  - Activity 재시도 옵션으로 자동 재시도│
 * │  - 크래시 시 상태 유실               │  - 크래시 후 자동 재개                │
 * │  - sagaId 직접 생성/관리             │  - workflowId 자동 생성 (추적 용이)   │
 * └─────────────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Workflow 규칙</h3>
 * <ul>
 *   <li>외부 호출 금지 → Activity로 위임</li>
 *   <li>비결정적 코드 금지 (Random, System.currentTimeMillis 등)</li>
 *   <li>Thread.sleep 금지 → Workflow.sleep 사용</li>
 * </ul>
 */
public class OrderWorkflowImpl implements OrderWorkflow {

    // Workflow 내에서는 Workflow.getLogger() 사용 (SLF4J 직접 사용 X)
    private final Logger log = Workflow.getLogger(OrderWorkflowImpl.class);

    // Activity 옵션 설정
    private final ActivityOptions activityOptions = ActivityOptions.newBuilder()
            // 시작 → 완료 최대 시간
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            // 재시도 옵션
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(1))      // 첫 재시도 대기
                    .setBackoffCoefficient(2.0)                     // 지수 백오프
                    .setMaximumInterval(Duration.ofSeconds(30))     // 최대 대기 시간
                    .setMaximumAttempts(3)                          // 최대 재시도 횟수
                    .build())
            .build();

    // Activity Stub 생성 (Temporal이 프록시 생성)
    private final OrderActivities activities =
            Workflow.newActivityStub(OrderActivities.class, activityOptions);

    @Override
    public OrderResult processOrder(OrderRequest request) {
        // Workflow ID는 Temporal이 자동 생성 (클라이언트에서 지정도 가능)
        String workflowId = Workflow.getInfo().getWorkflowId();

        log.info("========== Workflow 시작 [workflowId={}] ==========", workflowId);
        log.info("요청: customerId={}, productId={}, quantity={}, amount={}",
                request.customerId(), request.productId(),
                request.quantity(), request.amount());

        // Saga 옵션: 병렬 보상 여부 설정 가능
        Saga.Options sagaOptions = new Saga.Options.Builder()
                .setParallelCompensation(false)  // 보상을 순차 실행 (역순)
                .build();

        Saga saga = new Saga(sagaOptions);

        // 각 단계의 결과를 저장 (보상 시 필요)
        Long orderId = null;
        Long paymentId = null;

        try {
            // ===== 정방향 트랜잭션 (Forward) =====

            // T1: 주문 생성
            log.info("[T1] 주문 생성 시작");
            orderId = activities.createOrder(request.customerId());
            log.info("[T1] 주문 생성 완료: orderId={}", orderId);

            // 주문 생성 후 보상 등록 (orderId 사용 가능)
            final Long finalOrderId = orderId;
            saga.addCompensation(() -> activities.cancelOrder(finalOrderId));

            // T2: 재고 예약 (workflowId를 sagaId로 사용)
            log.info("[T2] 재고 예약 시작");
            activities.reserveStock(request.productId(), request.quantity(), workflowId);
            log.info("[T2] 재고 예약 완료");

            // 재고 예약 후 보상 등록
            saga.addCompensation(() -> activities.cancelReservation(
                    request.productId(),
                    request.quantity(),
                    workflowId
            ));

            // T3: 결제 생성
            log.info("[T3] 결제 생성 시작");
            paymentId = activities.createPayment(
                    orderId,
                    request.amount(),
                    request.paymentMethod(),
                    workflowId
            );
            log.info("[T3] 결제 생성 완료: paymentId={}", paymentId);

            // 결제 생성 후 환불 보상 등록
            final Long finalPaymentId = paymentId;
            saga.addCompensation(() -> activities.refundPayment(finalPaymentId, workflowId));

            // T3-2: 결제 승인
            log.info("[T3] 결제 승인 시작");
            activities.approvePayment(paymentId, workflowId);
            log.info("[T3] 결제 승인 완료");

            // T4: 주문 확정
            log.info("[T4] 주문 확정 시작");
            activities.confirmOrder(orderId);
            log.info("[T4] 주문 확정 완료");

            // T5: 재고 확정
            log.info("[T5] 재고 확정 시작");
            activities.confirmReservation(request.productId(), request.quantity(), workflowId);
            log.info("[T5] 재고 확정 완료");

            // T6: 결제 확정
            log.info("[T6] 결제 확정 시작");
            activities.confirmPayment(paymentId, workflowId);
            log.info("[T6] 결제 확정 완료");

            log.info("========== Workflow 성공 [workflowId={}] ==========", workflowId);
            return OrderResult.success(orderId, paymentId, workflowId);

        } catch (ActivityFailure e) {
            // Activity 실패 시 자동 보상 실행
            log.error("========== Workflow 실패 [workflowId={}]: {} ==========",
                    workflowId, e.getCause() != null ? e.getCause().getMessage() : e.getMessage());

            // 등록된 보상 액션을 역순으로 자동 실행
            saga.compensate();

            String errorMessage = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            return OrderResult.failure(errorMessage, workflowId);

        } catch (Exception e) {
            log.error("========== Workflow 예외 [workflowId={}]: {} ==========",
                    workflowId, e.getMessage());

            saga.compensate();

            return OrderResult.failure(e.getMessage(), workflowId);
        }
    }
}
