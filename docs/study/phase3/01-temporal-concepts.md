# Temporal 핵심 개념

## 이 문서에서 배우는 것

- Temporal이란 무엇이며 왜 필요한지 이해
- Workflow, Activity, Worker, Task Queue의 개념과 역할
- Temporal의 아키텍처와 동작 원리
- Phase 2-A에서 겪었던 분산 트랜잭션의 어려움을 Temporal이 어떻게 해결하는지

---

## 1. 왜 Temporal이 필요한가?

### Phase 2-A에서 겪은 어려움 복습

Phase 2-A에서 REST 기반 Saga 패턴을 구현하면서 다음과 같은 어려움을 겪었습니다:

```
┌─────────────────────────────────────────────────────────────────┐
│                    Phase 2-A의 문제점들                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. 상태 관리의 복잡성                                           │
│     - Saga 상태를 직접 DB에 저장/관리                            │
│     - 어떤 단계까지 성공했는지 추적 필요                          │
│     - 서버 재시작 시 진행 중인 Saga 복구 로직 필요                │
│                                                                 │
│  2. 보상 트랜잭션 구현의 어려움                                   │
│     - 각 단계별 롤백 로직 직접 구현                               │
│     - 보상 트랜잭션 실패 시 처리?                                │
│     - 부분 실패 시나리오 처리 복잡                                │
│                                                                 │
│  3. 재시도 로직의 복잡성                                         │
│     - Resilience4j 설정 관리                                    │
│     - 어떤 예외는 재시도, 어떤 예외는 즉시 실패                   │
│     - 재시도 횟수, 간격 등 설정 관리                              │
│                                                                 │
│  4. 타임아웃 처리                                                │
│     - 서비스별 타임아웃 설정                                      │
│     - 장시간 실행 작업 처리 어려움                                │
│                                                                 │
│  5. 가시성 부족                                                  │
│     - 현재 Saga가 어떤 상태인지 파악 어려움                       │
│     - 실패 원인 추적 어려움                                       │
│     - 디버깅 복잡                                                │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 직접 구현한 Saga 코드의 문제점

Phase 2-A에서 작성한 코드를 다시 살펴봅시다:

```java
// Phase 2-A에서 구현한 오케스트레이터 (문제점 포함)
public class PureSagaOrchestrator {

    public OrderSagaResult execute(OrderSagaRequest request) {
        String orderId = null;
        String reservationId = null;
        String paymentId = null;

        try {
            // Step 1: 주문 생성
            orderId = orderClient.createOrder(request);  // ← 여기서 서버 죽으면?

            // Step 2: 재고 예약
            reservationId = inventoryClient.reserveStock(orderId);  // ← 네트워크 타임아웃?

            // Step 3: 결제 처리
            paymentId = paymentClient.processPayment(orderId);  // ← 결제 성공인지 실패인지 모호할 때?

            return OrderSagaResult.success(orderId);

        } catch (Exception e) {
            // 보상 트랜잭션
            compensate(orderId, reservationId, paymentId);  // ← 보상 중에 또 실패하면?
            return OrderSagaResult.failure(e.getMessage());
        }
    }
}
```

**이 코드의 문제점:**

| 문제 | 설명 |
|------|------|
| **서버 장애** | Step 2 실행 중 서버가 죽으면? 다시 시작해야 하나? 처음부터? |
| **네트워크 장애** | 결제 API 호출 후 응답을 못 받으면? 성공? 실패? |
| **재시도 한계** | 3번 재시도 후에도 실패하면? 사람이 수동 처리? |
| **상태 추적** | 지금 몇 번째 주문이 어느 단계인지 어떻게 알지? |
| **장시간 작업** | 외부 결제 승인이 1시간 걸리면 어떻게 대기? |

---

## 2. Temporal이란?

### 정의

**Temporal**은 분산 시스템에서 **내구성 있는 실행(Durable Execution)**을 제공하는 워크플로우 오케스트레이션 플랫폼입니다.

```
┌─────────────────────────────────────────────────────────────────┐
│                         Temporal                                 │
│                                                                  │
│  "코드를 작성하면, Temporal이 알아서 실행을 보장해줍니다"          │
│                                                                  │
│  - 실패하면 자동 재시도                                           │
│  - 서버가 죽어도 이어서 실행                                      │
│  - 상태를 자동으로 저장                                           │
│  - 실행 이력을 완벽하게 기록                                      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Temporal의 핵심 가치

```
┌──────────────────┐
│   일반 코드       │
│                  │
│  try {           │
│    step1();      │    서버 죽으면 처음부터 다시
│    step2();      │    실패하면 수동 처리 필요
│    step3();      │    상태 추적 불가
│  } catch...      │
│                  │
└──────────────────┘

        vs

┌──────────────────┐
│ Temporal Workflow │
│                  │
│  step1();        │    서버 죽어도 이어서 실행
│  step2();        │    실패하면 자동 재시도
│  step3();        │    모든 상태 자동 기록
│                  │
└──────────────────┘
```

### Temporal이 해결해주는 것

| Phase 2-A 문제 | Temporal 해결책 |
|----------------|----------------|
| Saga 상태 관리 | **Event Sourcing**으로 자동 관리 |
| 보상 트랜잭션 | **Saga 패턴** 내장 지원 |
| 재시도 로직 | **Retry Policy** 선언적 설정 |
| 타임아웃 처리 | **Activity Timeout** 내장 |
| 가시성 | **Temporal Web UI** 제공 |

---

## 3. Temporal 아키텍처

### 전체 구조

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           Temporal Architecture                          │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                        Temporal Server                            │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │   │
│  │  │  Frontend   │  │   History   │  │  Matching   │              │   │
│  │  │  Service    │  │   Service   │  │  Service    │              │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘              │   │
│  │                          │                                        │   │
│  │                    ┌─────┴─────┐                                 │   │
│  │                    │ Database  │  (PostgreSQL/MySQL/Cassandra)   │   │
│  │                    └───────────┘                                 │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                              │                                           │
│                              │ gRPC                                      │
│                              │                                           │
│  ┌───────────────────────────┼───────────────────────────────────────┐  │
│  │                           │                                        │  │
│  │   ┌─────────────┐    ┌─────────────┐    ┌─────────────┐          │  │
│  │   │   Worker    │    │   Worker    │    │   Worker    │          │  │
│  │   │             │    │             │    │             │          │  │
│  │   │ ┌─────────┐ │    │ ┌─────────┐ │    │ ┌─────────┐ │          │  │
│  │   │ │Workflow │ │    │ │Workflow │ │    │ │Activity │ │          │  │
│  │   │ │  Code   │ │    │ │  Code   │ │    │ │  Code   │ │          │  │
│  │   │ └─────────┘ │    │ └─────────┘ │    │ └─────────┘ │          │  │
│  │   │ ┌─────────┐ │    │ ┌─────────┐ │    │ ┌─────────┐ │          │  │
│  │   │ │Activity │ │    │ │Activity │ │    │ │Activity │ │          │  │
│  │   │ │  Code   │ │    │ │  Code   │ │    │ │  Code   │ │          │  │
│  │   │ └─────────┘ │    │ └─────────┘ │    │ └─────────┘ │          │  │
│  │   └─────────────┘    └─────────────┘    └─────────────┘          │  │
│  │                     Your Application (Workers)                    │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 구성 요소 설명

| 구성 요소 | 역할 |
|-----------|------|
| **Temporal Server** | 워크플로우 실행을 조율하는 중앙 서버 |
| **Worker** | 실제 Workflow/Activity 코드를 실행하는 프로세스 (우리 애플리케이션) |
| **Database** | 워크플로우 상태와 이벤트 히스토리 저장 |

---

## 4. 핵심 개념: Workflow

### Workflow란?

**Workflow**는 비즈니스 로직의 전체 흐름을 정의하는 함수입니다.

```
┌─────────────────────────────────────────────────────────────────┐
│                         Workflow                                 │
│                                                                  │
│  "주문 처리 전체 과정을 하나의 Workflow로 정의"                    │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  @WorkflowMethod                                         │    │
│  │  OrderResult processOrder(OrderRequest request) {        │    │
│  │      String orderId = createOrder(request);  // Activity │    │
│  │      reserveStock(orderId);                  // Activity │    │
│  │      processPayment(orderId);                // Activity │    │
│  │      return confirmOrder(orderId);           // Activity │    │
│  │  }                                                       │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Workflow의 특징

```
┌─────────────────────────────────────────────────────────────────┐
│                    Workflow 특징                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. 결정적(Deterministic)                                        │
│     - 같은 입력 → 항상 같은 실행 경로                             │
│     - Random, 현재 시간 직접 사용 금지                            │
│                                                                  │
│  2. 내구성(Durable)                                              │
│     - 서버가 죽어도 상태 유지                                     │
│     - 재시작 시 마지막 상태에서 이어서 실행                        │
│                                                                  │
│  3. 장시간 실행 가능                                              │
│     - 며칠, 몇 달도 실행 가능                                     │
│     - 외부 이벤트 대기 가능 (Signal, Query)                       │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Workflow 코드 예시

```java
// 인터페이스 정의
@WorkflowInterface
public interface OrderWorkflow {

    @WorkflowMethod
    OrderResult processOrder(OrderRequest request);

    // Signal: 외부에서 워크플로우에 이벤트 전달
    @SignalMethod
    void cancelOrder(String reason);

    // Query: 워크플로우 상태 조회
    @QueryMethod
    OrderStatus getStatus();
}

// 구현
public class OrderWorkflowImpl implements OrderWorkflow {

    // Activity stub 생성
    private final OrderActivities activities = Workflow.newActivityStub(
        OrderActivities.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .setRetryOptions(RetryOptions.newBuilder()
                .setMaximumAttempts(3)
                .build())
            .build()
    );

    private OrderStatus status = OrderStatus.PENDING;
    private boolean cancelled = false;

    @Override
    public OrderResult processOrder(OrderRequest request) {
        // Step 1: 주문 생성
        status = OrderStatus.CREATING_ORDER;
        String orderId = activities.createOrder(request);

        // 취소 확인
        if (cancelled) {
            return compensateAndReturn(orderId, null, null);
        }

        // Step 2: 재고 예약
        status = OrderStatus.RESERVING_STOCK;
        String reservationId = activities.reserveStock(orderId);

        // Step 3: 결제 처리
        status = OrderStatus.PROCESSING_PAYMENT;
        String paymentId = activities.processPayment(orderId);

        // Step 4: 주문 확정
        status = OrderStatus.CONFIRMING;
        activities.confirmOrder(orderId);

        status = OrderStatus.COMPLETED;
        return OrderResult.success(orderId);
    }

    @Override
    public void cancelOrder(String reason) {
        this.cancelled = true;
    }

    @Override
    public OrderStatus getStatus() {
        return this.status;
    }
}
```

### Workflow에서 하면 안 되는 것

```java
// ❌ 잘못된 Workflow 코드
public class BadWorkflowImpl implements OrderWorkflow {

    @Override
    public OrderResult processOrder(OrderRequest request) {

        // ❌ Random 사용 금지 (비결정적)
        if (Math.random() > 0.5) { ... }

        // ❌ 현재 시간 직접 사용 금지
        LocalDateTime now = LocalDateTime.now();

        // ❌ 네트워크 호출 금지 (Activity에서 해야 함)
        restTemplate.postForObject(...);

        // ❌ 파일 I/O 금지 (Activity에서 해야 함)
        Files.readString(path);

        // ❌ Thread.sleep 금지
        Thread.sleep(1000);
    }
}

// ✅ 올바른 Workflow 코드
public class GoodWorkflowImpl implements OrderWorkflow {

    @Override
    public OrderResult processOrder(OrderRequest request) {

        // ✅ Temporal이 제공하는 랜덤 사용
        int random = Workflow.newRandom().nextInt(100);

        // ✅ Temporal이 제공하는 시간 사용
        long now = Workflow.currentTimeMillis();

        // ✅ Activity를 통한 외부 호출
        String result = activities.callExternalService(request);

        // ✅ Workflow.sleep 사용
        Workflow.sleep(Duration.ofSeconds(1));
    }
}
```

---

## 5. 핵심 개념: Activity

### Activity란?

**Activity**는 외부 세계와 상호작용하는 작업 단위입니다.

```
┌─────────────────────────────────────────────────────────────────┐
│                          Activity                                │
│                                                                  │
│  "실제로 일을 하는 곳"                                            │
│                                                                  │
│  - REST API 호출                                                 │
│  - 데이터베이스 접근                                              │
│  - 파일 처리                                                     │
│  - 외부 서비스 연동                                               │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Workflow vs Activity

```
┌─────────────────────────────────────────────────────────────────┐
│                   Workflow vs Activity                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Workflow                        Activity                        │
│  ─────────────────────────────   ─────────────────────────────  │
│  - 비즈니스 로직의 흐름           - 실제 작업 수행                │
│  - 결정적(Deterministic)          - 비결정적(Non-deterministic)   │
│  - 외부 호출 금지                 - 외부 호출 가능                │
│  - 상태 자동 저장                 - 재시도 자동 지원              │
│                                                                  │
│  [비유]                                                          │
│  - Workflow = 요리 레시피         - Activity = 실제 요리 동작    │
│  - "계란을 깨고, 섞고, 굽는다"    - "계란 깨기", "섞기", "굽기"  │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Activity 코드 예시

```java
// 인터페이스 정의
@ActivityInterface
public interface OrderActivities {

    @ActivityMethod
    String createOrder(OrderRequest request);

    @ActivityMethod
    String reserveStock(String orderId);

    @ActivityMethod
    String processPayment(String orderId);

    @ActivityMethod
    void confirmOrder(String orderId);

    // 보상 트랜잭션용 Activity
    @ActivityMethod
    void cancelOrder(String orderId);

    @ActivityMethod
    void releaseStock(String reservationId);

    @ActivityMethod
    void refundPayment(String paymentId);
}

// 구현
@Component
@RequiredArgsConstructor
public class OrderActivitiesImpl implements OrderActivities {

    private final OrderServiceClient orderClient;
    private final InventoryServiceClient inventoryClient;
    private final PaymentServiceClient paymentClient;

    @Override
    public String createOrder(OrderRequest request) {
        // REST API 호출 - 여기서는 자유롭게 외부 호출 가능
        OrderResponse response = orderClient.createOrder(request);
        return response.getOrderId();
    }

    @Override
    public String reserveStock(String orderId) {
        ReservationResponse response = inventoryClient.reserveStock(orderId);
        return response.getReservationId();
    }

    @Override
    public String processPayment(String orderId) {
        PaymentResponse response = paymentClient.processPayment(orderId);
        return response.getPaymentId();
    }

    @Override
    public void confirmOrder(String orderId) {
        orderClient.confirmOrder(orderId);
    }

    @Override
    public void cancelOrder(String orderId) {
        orderClient.cancelOrder(orderId);
    }

    @Override
    public void releaseStock(String reservationId) {
        inventoryClient.cancelReservation(reservationId);
    }

    @Override
    public void refundPayment(String paymentId) {
        paymentClient.refundPayment(paymentId);
    }
}
```

### Activity 옵션 설정

```java
// Workflow에서 Activity stub 생성 시 옵션 설정
ActivityOptions options = ActivityOptions.newBuilder()
    // 타임아웃 설정
    .setStartToCloseTimeout(Duration.ofMinutes(5))    // Activity 전체 실행 시간
    .setScheduleToStartTimeout(Duration.ofMinutes(1)) // 스케줄 후 시작까지 대기
    .setHeartbeatTimeout(Duration.ofSeconds(30))      // Heartbeat 주기

    // 재시도 설정
    .setRetryOptions(RetryOptions.newBuilder()
        .setInitialInterval(Duration.ofSeconds(1))     // 첫 재시도 대기
        .setBackoffCoefficient(2.0)                    // 지수 백오프 계수
        .setMaximumInterval(Duration.ofMinutes(1))     // 최대 대기 시간
        .setMaximumAttempts(5)                         // 최대 시도 횟수
        .setDoNotRetry(InvalidInputException.class)    // 재시도 안 할 예외
        .build())
    .build();

OrderActivities activities = Workflow.newActivityStub(
    OrderActivities.class,
    options
);
```

---

## 6. 핵심 개념: Worker

### Worker란?

**Worker**는 Workflow와 Activity 코드를 실제로 실행하는 프로세스입니다.

```
┌─────────────────────────────────────────────────────────────────┐
│                           Worker                                 │
│                                                                  │
│  "Temporal Server로부터 작업을 가져와서 실행하는 애플리케이션"     │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                                                           │   │
│  │   Worker                                                  │   │
│  │   │                                                       │   │
│  │   ├── Task Queue: "order-queue"                          │   │
│  │   │                                                       │   │
│  │   ├── 등록된 Workflow:                                    │   │
│  │   │   └── OrderWorkflowImpl.class                        │   │
│  │   │                                                       │   │
│  │   └── 등록된 Activity:                                    │   │
│  │       └── OrderActivitiesImpl (인스턴스)                  │   │
│  │                                                           │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Worker의 동작 방식

```
┌─────────────────────────────────────────────────────────────────┐
│                     Worker 동작 방식                             │
│                                                                  │
│  1. Worker가 Temporal Server에 연결                              │
│     │                                                            │
│     ▼                                                            │
│  2. "order-queue"라는 Task Queue를 폴링                          │
│     │                                                            │
│     ▼                                                            │
│  3. Task가 있으면 가져옴 (Workflow Task 또는 Activity Task)       │
│     │                                                            │
│     ▼                                                            │
│  4. 등록된 코드로 Task 실행                                       │
│     │                                                            │
│     ▼                                                            │
│  5. 결과를 Temporal Server에 보고                                │
│     │                                                            │
│     ▼                                                            │
│  6. 다시 2번으로 (무한 반복)                                      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Worker 코드 예시

```java
@Configuration
@RequiredArgsConstructor
public class TemporalWorkerConfig {

    private final WorkflowClient workflowClient;
    private final OrderActivities orderActivities;  // Spring Bean으로 주입

    @Bean
    public WorkerFactory workerFactory() {
        return WorkerFactory.newInstance(workflowClient);
    }

    @Bean
    public Worker orderWorker(WorkerFactory workerFactory) {
        // Task Queue 이름으로 Worker 생성
        Worker worker = workerFactory.newWorker("order-queue");

        // Workflow 구현체 등록 (클래스)
        worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);

        // Activity 구현체 등록 (인스턴스 - Spring Bean 사용 가능!)
        worker.registerActivitiesImplementations(orderActivities);

        return worker;
    }

    @PostConstruct
    public void startWorkers() {
        workerFactory().start();
    }
}
```

### 다중 Worker 구성

```
┌─────────────────────────────────────────────────────────────────┐
│                    다중 Worker 구성                              │
│                                                                  │
│                      Temporal Server                             │
│                           │                                      │
│            ┌──────────────┼──────────────┐                      │
│            │              │              │                       │
│            ▼              ▼              ▼                       │
│     ┌──────────┐   ┌──────────┐   ┌──────────┐                 │
│     │ Worker 1 │   │ Worker 2 │   │ Worker 3 │                 │
│     │(Server A)│   │(Server B)│   │(Server C)│                 │
│     └──────────┘   └──────────┘   └──────────┘                 │
│                                                                  │
│  - 여러 Worker가 같은 Task Queue를 폴링                          │
│  - 자동 로드 밸런싱                                              │
│  - 하나가 죽어도 다른 Worker가 처리                               │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Worker Rate Limiting (Phase 2-A RSemaphore 대체)

Phase 2-A에서 RSemaphore로 처리량을 제한했던 것을 Temporal에서는 Worker 옵션으로 설정합니다:

```java
// Worker 생성 시 동시성 제한 설정
WorkerOptions options = WorkerOptions.newBuilder()
    // Activity 동시 실행 수 제한 (RSemaphore 역할)
    .setMaxConcurrentActivityExecutionSize(10)

    // Workflow Task 동시 실행 수 제한
    .setMaxConcurrentWorkflowTaskExecutionSize(100)

    // Task Queue 레벨에서 초당 Activity 실행 제한
    .setMaxTaskQueueActivitiesPerSecond(50.0)

    .build();

Worker worker = workerFactory.newWorker("payment-queue", options);
```

```
┌─────────────────────────────────────────────────────────────────┐
│               Phase 2-A RSemaphore vs Temporal Worker           │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  [Phase 2-A - RSemaphore]                                       │
│  ─────────────────────────                                      │
│  RSemaphore semaphore = redisson.getSemaphore("payment:limit"); │
│  semaphore.trySetPermits(10);                                   │
│                                                                  │
│  try {                                                          │
│      if (semaphore.tryAcquire(5, TimeUnit.SECONDS)) {          │
│          paymentService.process(request);                       │
│      }                                                          │
│  } finally {                                                    │
│      semaphore.release();                                       │
│  }                                                              │
│                                                                  │
│  → Redis 의존성, 수동 acquire/release, 예외 시 누수 위험        │
│                                                                  │
│  ─────────────────────────────────────────────────────────────  │
│                                                                  │
│  [Temporal - Worker Options]                                    │
│  ──────────────────────────                                     │
│  WorkerOptions.newBuilder()                                     │
│      .setMaxConcurrentActivityExecutionSize(10)  // 동시 10개   │
│      .build();                                                  │
│                                                                  │
│  → 설정만 하면 자동 관리, 누수 없음, Redis 불필요               │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 7. 핵심 개념: Task Queue

### Task Queue란?

**Task Queue**는 Workflow/Activity Task를 Worker에게 전달하는 대기열입니다.

```
┌─────────────────────────────────────────────────────────────────┐
│                         Task Queue                               │
│                                                                  │
│  "작업을 전달하는 파이프라인"                                      │
│                                                                  │
│  Client ──▶ [Task] ──▶ ┌─────────────┐ ──▶ Worker               │
│                        │ Task Queue  │                          │
│                        │ "order-queue"│                          │
│                        └─────────────┘                          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Task Queue의 역할

```
┌─────────────────────────────────────────────────────────────────┐
│                    Task Queue의 역할                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. 작업 분배                                                    │
│     - 여러 Worker에게 작업을 균등하게 분배                        │
│                                                                  │
│  2. 디커플링                                                     │
│     - Client와 Worker 분리                                       │
│     - Worker가 없어도 작업 요청 가능                              │
│                                                                  │
│  3. 라우팅                                                       │
│     - 특정 작업을 특정 Worker 그룹에게 전달                        │
│     - 예: "payment-queue"는 결제 전용 Worker만 처리               │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Task Queue 사용 예시

```java
// Workflow 시작 시 Task Queue 지정
WorkflowOptions options = WorkflowOptions.newBuilder()
    .setTaskQueue("order-queue")
    .setWorkflowId("order-" + orderId)
    .build();

OrderWorkflow workflow = workflowClient.newWorkflowStub(
    OrderWorkflow.class,
    options
);

// Workflow 비동기 실행
WorkflowClient.start(workflow::processOrder, request);
```

### 서로 다른 Task Queue 사용

```
┌─────────────────────────────────────────────────────────────────┐
│                 서비스별 Task Queue 분리                         │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │                    Temporal Server                       │    │
│  │                                                          │    │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │    │
│  │  │ order-queue  │  │inventory-queue│ │payment-queue │  │    │
│  │  └───────┬──────┘  └───────┬──────┘  └───────┬──────┘  │    │
│  └──────────┼─────────────────┼─────────────────┼─────────┘    │
│             │                 │                 │               │
│             ▼                 ▼                 ▼               │
│     ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│     │ Order Worker │  │  Inventory   │  │   Payment    │       │
│     │              │  │   Worker     │  │   Worker     │       │
│     │ - 주문 로직   │  │ - 재고 로직   │  │ - 결제 로직   │       │
│     └──────────────┘  └──────────────┘  └──────────────┘       │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 8. 전체 흐름 이해하기

### 주문 처리 전체 흐름

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        주문 처리 전체 흐름                                │
│                                                                          │
│  [1] Client가 Workflow 시작 요청                                         │
│      │                                                                   │
│      │  POST /orders { productId: 1, quantity: 2 }                      │
│      │                                                                   │
│      ▼                                                                   │
│  ┌──────────────────┐                                                   │
│  │  API Controller  │                                                   │
│  │  (Spring Boot)   │                                                   │
│  └────────┬─────────┘                                                   │
│           │                                                              │
│  [2]      │  WorkflowClient.start(workflow::processOrder, request)      │
│           │                                                              │
│           ▼                                                              │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                      Temporal Server                              │   │
│  │                                                                   │   │
│  │  [3] Workflow Task 생성 → order-queue에 추가                       │   │
│  │                                                                   │   │
│  └────────────────────────────────────────────────────────────────── ┘   │
│                              │                                           │
│  [4]                        ▼                                           │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                         Worker                                     │   │
│  │                                                                    │   │
│  │  [5] Workflow Task 폴링 및 실행                                     │   │
│  │      │                                                             │   │
│  │      │  processOrder(request) {                                    │   │
│  │      │      orderId = activities.createOrder(request);             │   │
│  │      │      // ↑ [6] Activity Task 생성 → 실행 → 결과 반환           │   │
│  │      │      reservationId = activities.reserveStock(orderId);      │   │
│  │      │      // ↑ [7] Activity Task 생성 → 실행 → 결과 반환           │   │
│  │      │      paymentId = activities.processPayment(orderId);        │   │
│  │      │      // ↑ [8] Activity Task 생성 → 실행 → 결과 반환           │   │
│  │      │      activities.confirmOrder(orderId);                      │   │
│  │      │      return OrderResult.success(orderId);                   │   │
│  │      │  }                                                          │   │
│  │      │                                                             │   │
│  │  [9] Workflow 완료 → 결과 저장                                      │   │
│  │                                                                    │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Event History (실행 이력)

```
┌─────────────────────────────────────────────────────────────────┐
│                      Event History                               │
│                                                                  │
│  Temporal은 모든 실행 이벤트를 기록합니다:                         │
│                                                                  │
│  Event #1: WorkflowExecutionStarted                             │
│           └─ input: { productId: 1, quantity: 2 }               │
│                                                                  │
│  Event #2: WorkflowTaskScheduled                                │
│                                                                  │
│  Event #3: WorkflowTaskCompleted                                │
│                                                                  │
│  Event #4: ActivityTaskScheduled                                │
│           └─ activity: createOrder                              │
│                                                                  │
│  Event #5: ActivityTaskStarted                                  │
│                                                                  │
│  Event #6: ActivityTaskCompleted                                │
│           └─ result: "order-123"                                │
│                                                                  │
│  Event #7: ActivityTaskScheduled                                │
│           └─ activity: reserveStock                             │
│                                                                  │
│  ... (계속)                                                       │
│                                                                  │
│  Event #N: WorkflowExecutionCompleted                           │
│           └─ result: { success: true, orderId: "order-123" }    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

이 Event History 덕분에:
- 서버가 죽어도 마지막 이벤트부터 이어서 실행
- 실패 원인 추적 가능
- 워크플로우 상태 언제든 조회 가능

---

## 9. Temporal이 해결하는 Phase 2-A 문제들

### 문제 1: 상태 관리

```
┌─────────────────────────────────────────────────────────────────┐
│ Phase 2-A                           Temporal                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  // 직접 상태 관리                    // 자동 상태 관리           │
│  sagaState.setStatus(STEP_2);        // 코드만 작성하면 됨        │
│  sagaRepository.save(sagaState);      step1();                   │
│  try {                                step2();  // 여기까지 완료   │
│      step2();                         step3();  // 자동 기록      │
│  } catch ...                                                     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 문제 2: 재시도 로직

```
┌─────────────────────────────────────────────────────────────────┐
│ Phase 2-A                           Temporal                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  // Resilience4j 설정                // 선언적 설정               │
│  @Retry(name = "service")            RetryOptions.newBuilder()   │
│  public void call() {                    .setMaximumAttempts(3)  │
│      // 복잡한 설정 파일 필요              .build()               │
│  }                                                               │
│                                       // 자동 재시도!             │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 문제 3: 보상 트랜잭션

```
┌─────────────────────────────────────────────────────────────────┐
│ Phase 2-A                           Temporal                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  // 직접 구현                         // Saga 패턴 내장           │
│  try {                                Saga saga = Saga.newBuilder│
│      step1();                             .setParallelCompensation│
│      step2();                             (true)                  │
│      step3();                             .build();               │
│  } catch (Exception e) {              saga.addCompensation(      │
│      compensateStep2();                   () -> compensateStep1()│
│      compensateStep1();               );                         │
│  }                                    // 실패 시 자동 보상!       │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 문제 4: 가시성

```
┌─────────────────────────────────────────────────────────────────┐
│ Phase 2-A                           Temporal                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  // 로그 뒤지기                       // Temporal Web UI          │
│  grep "orderId" app.log              ┌─────────────────────────┐ │
│  // Zipkin 확인                       │  Workflow: order-123   │ │
│  // DB 상태 조회                       │  Status: Running       │ │
│                                       │  Current: Step 2       │ │
│  "지금 어디까지 진행됐지?"              │  History: [상세 이력]   │ │
│                                       └─────────────────────────┘ │
│                                       // 실시간 상태 확인!        │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 10. 실습 과제

### 과제 1: Temporal 로컬 실행

> ⚠️ **주의**: `temporalio/auto-setup` 이미지는 **Deprecated** 되었습니다.
> 개발 환경에서는 Temporal CLI 또는 `temporalio/temporal` 이미지를 사용하세요.

#### 방법 1: Temporal CLI (권장 - 가장 간단)

```bash
# Temporal CLI 설치 후
temporal server start-dev

# 또는 Docker로 실행
docker run --rm -p 7233:7233 -p 8233:8233 \
  temporalio/temporal:latest \
  server start-dev --ip 0.0.0.0
```

- 포트 7233: gRPC (Worker 연결)
- 포트 8233: Web UI
- SQLite 내장 (별도 DB 불필요)

#### 방법 2: Docker Compose (외부 DB 필요 시)

> **주의**: `temporalio/docker-compose` 저장소는 2026-01-05 아카이브되었습니다.
> 새로운 공식 예제는 [samples-server/compose](https://github.com/temporalio/samples-server/tree/main/compose)를 참조하세요.

```bash
# 새로운 공식 예제 저장소 사용
git clone https://github.com/temporalio/samples-server.git
cd samples-server/compose

# PostgreSQL 사용 예제
docker-compose -f docker-compose-postgres.yml up -d

# MySQL 사용 예제
docker-compose -f docker-compose-mysql.yml up -d
```

**직접 설정 시 예시**:
```yaml
# docker-compose-temporal.yml
services:
  temporal:
    image: temporalio/server:latest
    ports:
      - "7233:7233"
    environment:
      - DB=postgres
      - DB_PORT=5432
      - POSTGRES_USER=temporal
      - POSTGRES_PWD=temporal
      - POSTGRES_SEEDS=postgresql
    depends_on:
      - postgresql

  postgresql:
    image: postgres:15
    ports:
      - "5432:5432"
    environment:
      POSTGRES_USER: temporal
      POSTGRES_PASSWORD: temporal

  temporal-ui:
    image: temporalio/ui:latest
    ports:
      - "8080:8080"
    environment:
      - TEMPORAL_ADDRESS=temporal:7233
```

> **참고**: 프로덕션 환경 설정은 [Temporal Deployment Guide](https://docs.temporal.io/self-hosted-guide/deployment) 참조

### 과제 2: Temporal Web UI 탐색

브라우저에서 `http://localhost:8080`에 접속하여 Temporal UI를 탐색합니다.

- Namespaces 확인
- Workflow 목록 확인
- 실행 이력(Event History) 확인

### 과제 3: 개념 정리

다음 질문에 답해보세요:

1. Workflow와 Activity의 차이점은?
2. Worker의 역할은?
3. Task Queue는 왜 필요한가?
4. Temporal이 Phase 2-A의 어떤 문제를 해결하는가?

---

## 참고 자료

- [Temporal 공식 문서](https://docs.temporal.io/)
- [Temporal Java SDK](https://github.com/temporalio/sdk-java)
- [Temporal 핵심 개념 (공식)](https://docs.temporal.io/concepts)
- [Temporal vs 기존 방식 비교](https://temporal.io/how-it-works)
- [Temporal 101 튜토리얼](https://learn.temporal.io/courses/temporal_101/)

---

## 다음 단계

[02-temporal-spring.md](./02-temporal-spring.md) - Temporal + Spring Boot 연동으로 이동
