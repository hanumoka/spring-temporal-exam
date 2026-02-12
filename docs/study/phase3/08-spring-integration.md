# Temporal + Spring Boot 연동

> **전제**: `01-temporal-concepts.md`, `05-temporal-faq.md` 학습 완료
> **목표**: Spring Boot 프로젝트에서 Temporal을 실전 연동하는 전체 과정 이해

---

## 1. Spring + Temporal 시너지

```
+------------------------------------------------------------------+
|                    Spring + Temporal 시너지                        |
+------------------------------------------------------------------+
|                                                                    |
|  1. 의존성 주입 (DI)                                               |
|     Activity에 Repository, RestTemplate 등 기존 Bean 주입 가능     |
|                                                                    |
|  2. 자동 설정                                                      |
|     @Configuration + application.yml + Starter로 깔끔한 구성       |
|                                                                    |
|  3. 기존 인프라 활용                                                |
|     Security, Actuator, Micrometer 등 그대로 통합                  |
|                                                                    |
|  4. 테스트 용이성                                                   |
|     @SpringBootTest + TestWorkflowEnvironment + Mockito 활용       |
|                                                                    |
|  효과: Workflow 클래스 자체는 ~100줄로 간결 (orchestrator-pure 167줄)|
|  단, 전체 구현은 Activities(232줄) + Config(108줄) 포함             |
|  제거: SagaState, SagaRepository, Resilience4j 설정, 수동 보상     |
+------------------------------------------------------------------+
```

---

## 2. 관리 주체 정리: Spring vs Temporal

### 누가 무엇을 관리하는가?

| 구성 요소 | 인스턴스 생성 | 생명주기 관리 | Spring DI |
|-----------|-------------|-------------|-----------|
| **Worker** | Spring @Bean | Spring | O 가능 |
| **Activity** | Spring @Component | Spring | O 가능 |
| **Workflow** | Temporal new | Temporal | X 불가 |

**핵심**: Workflow만 Temporal이 직접 관리한다.
Temporal이 Workflow를 수천 번 Replay(재생성)할 수 있어야 하므로, Spring Context와 무관하게 기본 생성자로 생성된다.

### 코드에서 확인하기

```java
@Configuration
public class TemporalConfig {
    @Bean
    public Worker worker(WorkerFactory factory, OrderActivities activities) {
        Worker worker = factory.newWorker("order-task-queue");

        // Workflow: 클래스 타입만 전달 --> Temporal이 직접 new 호출
        worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);

        // Activity: Spring Bean 인스턴스 전달 --> DI 완전 활용
        worker.registerActivitiesImplementations(activities);

        factory.start();
        return worker;
    }
}
```

### 실수하기 쉬운 패턴

```java
// X Workflow에 @Component, @Autowired 사용 --> 작동 안 함
@Component
public class OrderWorkflowImpl implements OrderWorkflow {
    @Autowired private SomeService someService;  // Temporal은 Spring을 모름
}

// O Activity Stub을 통해 외부 서비스 호출
public class OrderWorkflowImpl implements OrderWorkflow {
    private final SomeActivities activities =
        Workflow.newActivityStub(SomeActivities.class, options);

    public void processOrder() {
        activities.callSomeService();  // Activity가 Spring Bean이므로 DI 사용
    }
}
```

---

## 3. Workflow 코드 저장 위치

```
+------------------------------------------------------------------+
|  Temporal Server (DB)              | Worker (Spring Boot App)      |
+------------------------------------+-------------------------------+
|                                    |                               |
|  O 저장되는 것:                    |  여기에 있는 것:              |
|  - Event History                   |  - OrderWorkflowImpl.class    |
|    (Started, Scheduled, Completed) |  - OrderActivitiesImpl.class  |
|  - Workflow 실행 상태              |  - 모든 비즈니스 로직         |
|    (workflowId, status, I/O)       |                               |
|  - Task Queue 정보                 |                               |
|                                    |                               |
|  X 저장 안 되는 것:               |                               |
|  - Workflow/Activity 코드          |                               |
|  - 비즈니스 로직 자체              |                               |
+------------------------------------+-------------------------------+

실행 흐름:
  Controller --> Temporal Server: "OrderWorkflow 시작해줘"
  Server --> DB 저장: workflowType="OrderWorkflow" (이름만!)
  Server --> Task Queue에 Task 추가
  Worker  <-- Long Polling으로 Task 수신
  Worker  --> 등록된 클래스에서 "OrderWorkflow" 찾아서 실행
```

---

## 4. 프로젝트 설정

### 4.1 build.gradle

```groovy
// orchestrator-temporal 모듈
dependencies {
    implementation 'io.temporal:temporal-spring-boot-starter:1.26.0'
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation project(':common')

    testImplementation 'io.temporal:temporal-testing:1.26.0'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
}
```

### 4.2 Temporal 서버 설정

```bash
# 방법 1: Temporal CLI (개발 환경 권장, SQLite 내장)
# 기본 포트는 7233이지만, 이 프로젝트에서는 21733으로 매핑
docker run --rm -p 21733:7233 -p 8233:8233 \
  temporalio/temporal:latest \
  server start-dev --ip 0.0.0.0
```

```yaml
# 방법 2: Docker Compose (외부 DB 사용)
# 공식 예제: https://github.com/temporalio/samples-server/tree/main/compose
services:
  temporal:
    image: temporalio/server:latest
    ports:
      - "21733:7233"
    environment:
      - DB=mysql
      - DB_PORT=3306
      - MYSQL_USER=temporal
      - MYSQL_PWD=temporal
      - MYSQL_SEEDS=mysql
    depends_on:
      - mysql

  temporal-ui:
    image: temporalio/ui:latest
    ports:
      - "21088:8080"
    environment:
      - TEMPORAL_ADDRESS=temporal:7233  # 내부 네트워크에서는 기본 포트 사용
    depends_on:
      - temporal
```

---

## 5. application.yml 설정

### 수동 Bean 구성 방식

```yaml
spring:
  application:
    name: orchestrator-temporal
server:
  port: 21081

temporal:
  service-address: localhost:21733   # 이 프로젝트에서는 7233 → 21733으로 매핑
  namespace: default
  task-queue: order-task-queue

services:
  order-url: http://localhost:8081
  inventory-url: http://localhost:8082
  payment-url: http://localhost:8083
  notification-url: http://localhost:8084

management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
```

### temporal-spring-boot-starter 방식

```yaml
spring:
  temporal:
    connection:
      target: localhost:21733   # 이 프로젝트 매핑 포트
    namespace: default
    workers:
      - name: order-worker
        task-queue: order-task-queue
        max-concurrent-workflow-task-executors: 200
        max-concurrent-activity-executors: 200
    packages:
      - com.hanumoka.orchestrator.temporal.workflow
      - com.hanumoka.orchestrator.temporal.activity
```

---

## 6. Worker 설정 (@Configuration)

```java
@Configuration
public class TemporalConfig {

    @Value("${temporal.connection.target:localhost:21733}")
    private String temporalTarget;

    @Value("${temporal.namespace}")
    private String namespace;

    private static final String TASK_QUEUE = "order-task-queue";

    @Bean
    public WorkflowServiceStubs workflowServiceStubs() {
        return WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget(temporalTarget)
                .build()
        );
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs stubs) {
        return WorkflowClient.newInstance(stubs,
            WorkflowClientOptions.newBuilder()
                .setNamespace(namespace)
                .build()
        );
    }

    @Bean
    public WorkerFactory workerFactory(WorkflowClient client) {
        return WorkerFactory.newInstance(client);
    }

    @Bean
    public Worker worker(WorkerFactory factory, OrderActivities activities) {
        WorkerOptions options = WorkerOptions.newBuilder()
            .setMaxConcurrentActivityExecutionSize(100)
            .setMaxConcurrentWorkflowTaskExecutionSize(100)
            .build();

        Worker worker = factory.newWorker(TASK_QUEUE, options);
        worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        factory.start();
        return worker;
    }
}
```

### Worker 시작 흐름

```
Spring Boot 시작
  |
  v
[1] WorkflowServiceStubs 생성 --> gRPC로 Temporal Server 연결
[2] WorkflowClient 생성 --> Namespace 설정
[3] OrderActivitiesImpl Bean 생성 --> RestTemplate, Repository 주입
[4] Worker Bean 생성 --> Workflow/Activity 등록
[5] factory.start() --> 별도 스레드에서 Long Polling 시작
[6] 애플리케이션 Ready --> Controller 요청 수신 + Worker Task 대기
```

---

## 7. Workflow & Activity를 Spring Bean으로 구성

### Activity (Spring Bean)

```java
@ActivityInterface
public interface OrderActivities {
    @ActivityMethod String createOrder(OrderRequest request);
    @ActivityMethod String reserveStock(String orderId, Long productId, int quantity);
    @ActivityMethod String processPayment(String orderId, Long amount);
    @ActivityMethod void confirmOrder(String orderId);
    @ActivityMethod void cancelOrder(String orderId);
    @ActivityMethod void releaseStock(String reservationId);
    @ActivityMethod void refundPayment(String paymentId);
}

@Slf4j
@Component  // Spring Bean으로 등록 --> DI 가능!
@RequiredArgsConstructor
public class OrderActivitiesImpl implements OrderActivities {

    private final RestTemplate restTemplate;  // Spring DI

    @Value("${services.order-url}")
    private String orderServiceUrl;

    @Override
    public String createOrder(OrderRequest request) {
        log.info("Activity: 주문 생성 - {}", request);
        OrderResponse response = restTemplate.postForObject(
            orderServiceUrl + "/orders", request, OrderResponse.class
        );
        if (response == null) throw new RuntimeException("주문 생성 실패");
        return response.getOrderId();
    }
    // ... 나머지 메서드 구현
}
```

### Workflow (Spring Bean 아님!)

```java
@WorkflowInterface
public interface OrderWorkflow {
    @WorkflowMethod OrderResult processOrder(OrderRequest request);
    @SignalMethod   void cancelOrder(String reason);
    @QueryMethod    String getOrderStatus();
}

// @Component 붙이면 안 됨! 기본 생성자만 사용!
@Slf4j
public class OrderWorkflowImpl implements OrderWorkflow {

    private final OrderActivities activities;
    private String currentStatus = "INITIALIZED";
    private boolean cancelRequested = false;

    public OrderWorkflowImpl() {
        this.activities = Workflow.newActivityStub(OrderActivities.class,
            ActivityOptions.newBuilder()
                // 30초: HTTP 호출 기준 충분한 시간이면서, 장애 감지를 빠르게 하기 위해 짧게 설정
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .setRetryOptions(RetryOptions.newBuilder()
                    .setMaximumAttempts(3)
                    .setInitialInterval(Duration.ofSeconds(1))
                    .setBackoffCoefficient(2.0)
                    .build())
                .build());
    }

    @Override
    public OrderResult processOrder(OrderRequest request) {
        Saga saga = new Saga(new Saga.Options.Builder()
            .setParallelCompensation(false)
            .setContinueWithError(true).build());
        try {
            currentStatus = "CREATING_ORDER";
            String orderId = activities.createOrder(request);
            saga.addCompensation(() -> activities.cancelOrder(orderId));

            if (cancelRequested) throw new RuntimeException("취소 요청됨");

            currentStatus = "RESERVING_STOCK";
            String resId = activities.reserveStock(orderId, request.getProductId(), request.getQuantity());
            saga.addCompensation(() -> activities.releaseStock(resId));

            currentStatus = "PROCESSING_PAYMENT";
            String payId = activities.processPayment(orderId, request.getAmount());
            saga.addCompensation(() -> activities.refundPayment(payId));

            currentStatus = "CONFIRMING_ORDER";
            activities.confirmOrder(orderId);
            currentStatus = "COMPLETED";
            return OrderResult.success(orderId, payId);

        } catch (ActivityFailure | RuntimeException e) {
            currentStatus = "COMPENSATING";
            saga.compensate();
            currentStatus = "FAILED";
            return OrderResult.failure(e instanceof ActivityFailure
                ? e.getCause().getMessage() : e.getMessage());
        }
    }

    @Override
    public void cancelOrder(String reason) { this.cancelRequested = true; }

    @Override
    public String getOrderStatus() { return currentStatus; }
}
```

---

## 8. Auto-Discovery 설정

temporal-spring-boot-starter를 사용하면 Workflow/Activity를 자동 등록할 수 있다.

```java
// Workflow에 @WorkflowImpl 추가
@WorkflowImpl(taskQueues = "order-task-queue")
public class OrderWorkflowImpl implements OrderWorkflow { ... }
```

```yaml
# application.yml에서 패키지 스캔 설정
spring:
  temporal:
    workers:
      - name: order-worker
        task-queue: order-task-queue
    packages:
      - com.hanumoka.orchestrator.temporal.workflow
      - com.hanumoka.orchestrator.temporal.activity
```

| 비교 항목 | 수동 설정 | Auto-Discovery |
|----------|----------|----------------|
| 등록 방법 | worker.register...() 직접 호출 | 어노테이션으로 자동 |
| 추가 시 | 설정 코드 수정 필요 | 어노테이션만 추가 |
| 가시성 | 무엇이 등록되는지 명확 | 패키지 스캔에 의존 |
| 권장 | 학습 / 소규모 | 프로덕션 / 대규모 |

> **이 프로젝트의 선택**: 수동 등록 방식 (`TemporalConfig.java`)을 사용한다.
> - 학습 프로젝트이므로 Temporal의 내부 동작을 명시적으로 이해하기 위함
> - `worker.registerWorkflowImplementationTypes()`, `worker.registerActivitiesImplementations()`를 직접 호출
> - Auto-Discovery의 `@WorkflowImpl` 어노테이션은 사용하지 않음

---

## 9. 테스트 작성 (TestWorkflowEnvironment)

### 단위 테스트

```java
class OrderWorkflowTest {

    @RegisterExtension
    public static final TestWorkflowExtension ext =
        TestWorkflowExtension.newBuilder()
            .setWorkflowTypes(OrderWorkflowImpl.class)
            .setDoNotStart(true).build();

    @Mock private OrderActivities mockActivities;

    @Test
    void 정상_주문_처리() {
        Worker worker = ext.getWorker();
        worker.registerActivitiesImplementations(mockActivities);
        ext.getTestEnvironment().start();

        when(mockActivities.createOrder(any())).thenReturn("order-123");
        when(mockActivities.reserveStock(anyString(), anyLong(), anyInt()))
            .thenReturn("res-456");
        when(mockActivities.processPayment(anyString(), anyLong()))
            .thenReturn("pay-789");

        OrderWorkflow wf = ext.getWorkflowClient().newWorkflowStub(
            OrderWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(ext.getTaskQueue()).build());

        OrderResult result = wf.processOrder(new OrderRequest(1L, 100L, 2, 50000L));

        assertThat(result.isSuccess()).isTrue();
        verify(mockActivities).confirmOrder("order-123");
        verify(mockActivities, never()).cancelOrder(anyString());
    }

    @Test
    void 결제_실패시_보상_트랜잭션() {
        Worker worker = ext.getWorker();
        worker.registerActivitiesImplementations(mockActivities);
        ext.getTestEnvironment().start();

        when(mockActivities.createOrder(any())).thenReturn("order-123");
        when(mockActivities.reserveStock(anyString(), anyLong(), anyInt()))
            .thenReturn("res-456");
        when(mockActivities.processPayment(anyString(), anyLong()))
            .thenThrow(new RuntimeException("결제 실패"));

        OrderWorkflow wf = ext.getWorkflowClient().newWorkflowStub(
            OrderWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(ext.getTaskQueue()).build());

        OrderResult result = wf.processOrder(new OrderRequest(1L, 100L, 2, 50000L));

        assertThat(result.isSuccess()).isFalse();
        verify(mockActivities).releaseStock("res-456");
        verify(mockActivities).cancelOrder("order-123");
        verify(mockActivities, never()).refundPayment(anyString());
    }
}
```

### 통합 테스트 (Testcontainers)

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrderWorkflowIntegrationTest {

    @Container
    static GenericContainer<?> temporal =
        new GenericContainer<>("temporalio/temporal:latest")
            .withExposedPorts(7233)
            .withCommand("server", "start-dev", "--ip", "0.0.0.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("temporal.service-address",
            () -> temporal.getHost() + ":" + temporal.getMappedPort(7233));
    }

    @Autowired private TestRestTemplate restTemplate;

    @Test
    void 주문_API_통합_테스트() {
        var request = new OrderRequest(1L, 100L, 2, 50000L);
        var response = restTemplate.postForEntity(
            "/api/orders/async", request, WorkflowStartResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().workflowId()).startsWith("order-");
    }
}
```

---

## 10. 인프라 구성 + 포트 정리

### 전체 구조도

```
+-----------------------------------------------------------------------+
|                         전체 인프라 구성도                               |
+-----------------------------------------------------------------------+
|                                                                         |
|  Temporal Cluster (Docker)                                              |
|  +-------------------------------------------------------------------+ |
|  |  Frontend(:21733) ---+--- History Service ---+--- Matching Service | |
|  |  (gRPC Gateway)      |                       |                     | |
|  |                       +--- PostgreSQL/MySQL --+                     | |
|  |                            (Event History)                         | |
|  +-------------------------------------------------------------------+ |
|         |  gRPC                                                         |
|         v                                                               |
|  Spring Boot (Worker)                                                   |
|  +-------------------------------------------------------------------+ |
|  |  WorkflowClient --gRPC--> Temporal Server                          | |
|  |  Worker <--Long Polling-- Task Queue                               | |
|  |    Workflow (Temporal 관리)                                         | |
|  |    Activity --HTTP--> Order/Inventory/Payment Service              | |
|  +-------------------------------------------------------------------+ |
|                                                                         |
|  Temporal UI (:21088) -- http://localhost:21088                         |
+-----------------------------------------------------------------------+
```

### Temporal Server 내부 서비스

| 서비스 | 역할 |
|--------|------|
| **Frontend** | gRPC API Gateway, 모든 요청의 진입점 |
| **History** | Event History 기록, Workflow 상태 추적 |
| **Matching** | Task Queue 관리, Worker에게 Task 분배 |
| **Worker** | 시스템 내부 Workflow (타이머, 스케줄 등) |

### 포트 정리

| 구성 요소 | 포트 | 용도 |
|----------|------|------|
| Temporal Server gRPC | 21733 | SDK가 연결하는 포트 (기본 7233 → 21733 매핑) |
| Temporal UI | 21088 | 브라우저 모니터링 (기본 8088 → 21088 매핑) |
| orchestrator-temporal | 21081 | Temporal Workflow API |
| service-order | 21082 | 주문 서비스 |
| service-inventory | 21083 | 재고 서비스 |
| service-payment | 21084 | 결제 서비스 |
| service-notification | 21085 | 알림 서비스 |

### 트러블슈팅 Quick Reference

| 증상 | 원인 | 해결 |
|------|------|------|
| Workflow execution already started | 같은 Workflow ID 중복 | 고유한 Workflow ID 사용 |
| Activity task timed out | 실행 시간 초과 | 타임아웃 늘리기 |
| Worker not polling | Worker 미시작 | factory.start() 확인, Queue 이름 확인 |
| Non-deterministic workflow | Random, 시간, I/O 사용 | Workflow.newRandom(), Workflow.sleep() 사용 |
| Spring Bean 주입 안 됨 | Activity를 new로 생성 | @Component + 인스턴스 전달 |
| 연결 타임아웃 | Server 미실행 | Docker 컨테이너, 포트 확인 |

---

## 참고 자료

- [Temporal Java SDK](https://docs.temporal.io/dev-guide/java)
- [Spring Boot Integration](https://docs.temporal.io/develop/java/spring-boot-integration)
- [temporal-spring-boot-starter](https://github.com/temporalio/sdk-java/tree/master/temporal-spring-boot-autoconfigure)
- [Temporal Java Samples](https://github.com/temporalio/samples-java)

---

*이전 학습: [11-activity-design.md](./11-activity-design.md)*
*다음 학습: [09-saga-with-temporal.md](./09-saga-with-temporal.md)*
