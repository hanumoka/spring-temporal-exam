package com.hanumoka.orchestrator.temporal.config;

import com.hanumoka.orchestrator.temporal.activity.OrderActivities;
import com.hanumoka.orchestrator.temporal.workflow.impl.OrderWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Temporal 설정
 *
 * <h3>구성 요소</h3>
 * <ul>
 *   <li>WorkflowServiceStubs: Temporal Server 연결</li>
 *   <li>WorkflowClient: Workflow 시작/조회 클라이언트</li>
 *   <li>WorkerFactory: Worker 생성 팩토리</li>
 *   <li>Worker: Workflow/Activity 실행 엔진</li>
 * </ul>
 *
 * <h3>temporal-spring-boot-starter 사용 시</h3>
 * <p>대부분 자동 설정되지만, Activity Bean 등록 등 커스텀이 필요한 경우
 * 이 설정 클래스를 사용합니다.</p>
 */
@Configuration
@Slf4j
public class TemporalConfig {

    private static final String TASK_QUEUE = "order-task-queue";

    @Value("${temporal.connection.target:localhost:7233}")
    private String temporalTarget;

    @Value("${temporal.namespace:default}")
    private String namespace;

    /**
     * Temporal Server 연결 Stubs
     */
    @Bean
    public WorkflowServiceStubs workflowServiceStubs() {
        log.info("Temporal Server 연결: {}", temporalTarget);

        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(temporalTarget)
                        .build()
        );
    }

    /**
     * Workflow Client
     *
     * <p>Controller에서 Workflow 시작/조회에 사용</p>
     */
    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs stubs) {
        log.info("WorkflowClient 생성: namespace={}", namespace);

        return WorkflowClient.newInstance(
                stubs,
                WorkflowClientOptions.newBuilder()
                        .setNamespace(namespace)
                        .build()
        );
    }

    /**
     * Worker Factory
     *
     * <p>Worker 생성을 관리</p>
     */
    @Bean
    public WorkerFactory workerFactory(WorkflowClient client) {
        return WorkerFactory.newInstance(client);
    }

    /**
     * Worker 생성 및 시작
     *
     * <p>Task Queue를 폴링하여 Workflow/Activity 실행</p>
     */
    @Bean
    public Worker worker(WorkerFactory factory, OrderActivities activities) {
        log.info("Worker 생성: taskQueue={}", TASK_QUEUE);

        Worker worker = factory.newWorker(TASK_QUEUE);

        // Workflow 등록
        worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);

        // Activity 등록 (Spring Bean 주입)
        worker.registerActivitiesImplementations(activities);

        // WorkerFactory 시작 (모든 Worker가 Task Queue 폴링 시작)
        factory.start();

        log.info("Worker 시작 완료");
        return worker;
    }
}
