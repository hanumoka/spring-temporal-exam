package com.hanumoka.orchestrator.temporal.workflow;

import com.hanumoka.orchestrator.temporal.dto.OrderRequest;
import com.hanumoka.orchestrator.temporal.dto.OrderResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 주문 Workflow 인터페이스
 *
 * <h3>Workflow 특징</h3>
 * <ul>
 *   <li>비즈니스 프로세스의 오케스트레이션 정의</li>
 *   <li>상태가 자동으로 영속화됨 (Durable Execution)</li>
 *   <li>크래시 후 자동 재개</li>
 *   <li>결정적(Deterministic) 코드만 허용</li>
 * </ul>
 *
 * <h3>orchestrator-pure 대응</h3>
 * <pre>
 * orchestrator-pure: OrderSagaOrchestrator.execute()
 * orchestrator-temporal: OrderWorkflow.processOrder()
 * </pre>
 */
@WorkflowInterface
public interface OrderWorkflow {

    /**
     * 주문 처리 Workflow 실행
     *
     * <p>이 메서드가 Workflow의 진입점입니다.</p>
     *
     * @param request 주문 요청
     * @return 주문 결과
     */
    @WorkflowMethod
    OrderResult processOrder(OrderRequest request);
}
