package com.hanumoka.orchestrator.temporal.controller;

import com.hanumoka.orchestrator.temporal.dto.OrderRequest;
import com.hanumoka.orchestrator.temporal.dto.OrderResult;
import com.hanumoka.orchestrator.temporal.workflow.OrderWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.UUID;

/**
 * Temporal 주문 API Controller
 *
 * <h3>orchestrator-pure와 비교</h3>
 * <pre>
 * orchestrator-pure:    POST /api/saga/orders → SagaController → OrderSagaOrchestrator
 * orchestrator-temporal: POST /api/temporal/orders → OrderController → Temporal Workflow
 * </pre>
 *
 * <h3>Workflow 실행 방식</h3>
 * <ul>
 *   <li>동기 실행: execute() - 완료까지 대기</li>
 *   <li>비동기 실행: start() - 즉시 반환, 나중에 결과 조회</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/temporal/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final WorkflowClient workflowClient;

    private static final String TASK_QUEUE = "order-task-queue";

    /**
     * 주문 생성 (동기 실행)
     *
     * <p>Workflow가 완료될 때까지 대기 후 결과 반환</p>
     *
     * @param request 주문 요청
     * @return 주문 결과
     */
    @PostMapping
    public ResponseEntity<OrderResult> createOrder(@RequestBody OrderRequest request) {
        // Workflow ID 생성 (중복 방지를 위해 UUID 사용)
        String workflowId = "order-" + UUID.randomUUID().toString().substring(0, 8);

        log.info("Workflow 시작 요청: workflowId={}", workflowId);

        // Workflow 옵션 설정
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(TASK_QUEUE)
                .setWorkflowId(workflowId)
                // Workflow 전체 실행 제한 시간
                .setWorkflowExecutionTimeout(Duration.ofMinutes(5))
                .build();

        // Workflow Stub 생성
        OrderWorkflow workflow = workflowClient.newWorkflowStub(
                OrderWorkflow.class,
                options
        );

        // 동기 실행 (완료까지 대기)
        OrderResult result = workflow.processOrder(request);

        log.info("Workflow 완료: workflowId={}, success={}", workflowId, result.success());

        if (result.success()) {
            return ResponseEntity.ok(result);
        } else {
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 주문 생성 (비동기 실행)
     *
     * <p>Workflow를 시작하고 즉시 Workflow ID 반환</p>
     * <p>결과는 Temporal UI에서 확인하거나 별도 API로 조회</p>
     *
     * @param request 주문 요청
     * @return Workflow 시작 정보
     */
    @PostMapping("/async")
    public ResponseEntity<AsyncOrderResponse> createOrderAsync(@RequestBody OrderRequest request) {
        String workflowId = "order-" + UUID.randomUUID().toString().substring(0, 8);

        log.info("Workflow 비동기 시작: workflowId={}", workflowId);

        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(TASK_QUEUE)
                .setWorkflowId(workflowId)
                .setWorkflowExecutionTimeout(Duration.ofMinutes(5))
                .build();

        OrderWorkflow workflow = workflowClient.newWorkflowStub(
                OrderWorkflow.class,
                options
        );

        // 비동기 실행 (즉시 반환)
        WorkflowClient.start(workflow::processOrder, request);

        log.info("Workflow 시작됨: workflowId={}", workflowId);

        return ResponseEntity.accepted().body(
                new AsyncOrderResponse(
                        workflowId,
                        "Workflow started. Check Temporal UI for status.",
                        "http://localhost:21088/namespaces/default/workflows/" + workflowId
                )
        );
    }

    /**
     * Workflow 상태 조회
     *
     * @param workflowId Workflow ID
     * @return Workflow 결과 (완료된 경우)
     */
    @GetMapping("/{workflowId}")
    public ResponseEntity<OrderResult> getOrderStatus(@PathVariable String workflowId) {
        log.info("Workflow 상태 조회: workflowId={}", workflowId);

        try {
            // 기존 Workflow에 연결
            OrderWorkflow workflow = workflowClient.newWorkflowStub(
                    OrderWorkflow.class,
                    workflowId
            );

            // 결과 조회 (완료될 때까지 대기)
            OrderResult result = workflowClient.newWorkflowStub(
                    OrderWorkflow.class,
                    workflowId
            ).processOrder(null);  // 이미 시작된 Workflow의 결과를 가져옴 (실제로는 다른 방법 사용)

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Workflow 조회 실패: workflowId={}, error={}", workflowId, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 비동기 실행 응답
     */
    public record AsyncOrderResponse(
            String workflowId,
            String message,
            String temporalUiUrl
    ) {}
}
