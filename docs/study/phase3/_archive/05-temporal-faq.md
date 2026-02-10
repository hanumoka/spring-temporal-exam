# Temporal 자주 묻는 질문 (FAQ) 완전 가이드

> **작성일**: 2026-02-07
> **대상**: Temporal 학습 중 자주 헷갈리는 개념 정리
> **전제**: `00-temporal-deep-dive.md` 학습 완료

---

## 목차

### 기본 개념
1. [관리 주체: Spring vs Temporal](#1-관리-주체-spring-vs-temporal)
2. [Workflow 코드는 어디에 저장되나?](#2-workflow-코드는-어디에-저장되나)
3. [Workflow 수정/추가 시 어떻게 되는가?](#3-workflow-수정추가-시-어떻게-되는가)
4. [재시도 계속 실패하면 어떻게 되는가?](#4-재시도-계속-실패하면-어떻게-되는가)

### 기능과 통신
5. [Signal과 Query 상세](#5-signal과-query-상세)
6. [인프라 구성과 Spring 연동](#6-인프라-구성과-spring-연동)
7. [gRPC와 Long Polling 동작 원리](#7-grpc와-long-polling-동작-원리)

### 심화 주제
8. [Workflow ID와 Run ID는 무엇인가?](#8-workflow-id와-run-id는-무엇인가)
9. [Child Workflow는 언제 사용하는가?](#9-child-workflow는-언제-사용하는가)
10. [Temporal Cloud vs Self-Hosted](#10-temporal-cloud-vs-self-hosted)
11. [성능과 한계](#11-성능과-한계)
12. [모니터링과 디버깅](#12-모니터링과-디버깅)
13. [일반적인 실수와 트러블슈팅](#13-일반적인-실수와-트러블슈팅)

---

## 1. 관리 주체: Spring vs Temporal

### 핵심 질문
> "Workflow, Activity, Worker를 Spring이 관리하는 건가, Temporal이 관리하는 건가?"

### 한눈에 보는 답변

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     누가 무엇을 관리하는가?                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  구성 요소       │ 인스턴스 생성  │ 생명주기 관리 │ Spring DI    │    │
│  ├──────────────────┼───────────────┼───────────────┼──────────────┤    │
│  │  Worker          │ Spring @Bean  │ Spring        │ ✅ 가능      │    │
│  │  Activity        │ Spring @Comp  │ Spring        │ ✅ 가능      │    │
│  │  Workflow        │ Temporal new  │ Temporal      │ ❌ 불가      │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  왜 Workflow만 다른가?                                                       │
│  ─────────────────────                                                       │
│  Temporal이 Workflow를 수천 번 재생성(Replay)할 수 있어야 하기 때문!         │
│  Spring Context와 무관하게 언제든 new 할 수 있어야 함.                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 비유로 이해하기

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            호텔 비유                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  호텔 = Spring Boot Application                                              │
│  호텔 체인 본사 = Temporal Server                                            │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  구성 요소    │  비유                                                │    │
│  ├──────────────┼──────────────────────────────────────────────────────┤    │
│  │  Worker      │  호텔 건물 자체 (Spring이 짓고 관리)                   │    │
│  │  Activity    │  호텔 직원 (Spring이 고용하고 관리, 다른 직원과 협업)   │    │
│  │  Workflow    │  투숙객 (본사가 배정, 호텔은 방만 제공, 체인인 기록 관리)│    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  투숙객(Workflow)은:                                                         │
│  - 본사(Temporal)가 어느 호텔로 보낼지 결정                                  │
│  - 체크인/체크아웃 기록은 본사에 보관                                        │
│  - 호텔(Spring)은 방(Worker)만 제공, 투숙객 정보 관리 안 함                 │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 코드로 확인하기

```java
// ═══════════════════════════════════════════════════════════════════════════
// Worker 설정 코드에서 차이점 확인
// ═══════════════════════════════════════════════════════════════════════════

@Configuration
public class TemporalConfig {

    @Bean
    public Worker worker(WorkerFactory factory, OrderActivities activities) {

        Worker worker = factory.newWorker("order-task-queue");

        // ─────────────────────────────────────────────────────────────────
        // Workflow 등록: 클래스 타입만 전달 (.class)
        // ─────────────────────────────────────────────────────────────────
        // Temporal이 필요할 때 직접 new OrderWorkflowImpl() 호출
        // Spring과 무관하게 생성됨 → Spring DI 불가!

        worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);
        //                                         ↑ 타입만! 인스턴스 아님!

        // ─────────────────────────────────────────────────────────────────
        // Activity 등록: Spring Bean 인스턴스 전달
        // ─────────────────────────────────────────────────────────────────
        // Spring이 생성한 Bean을 그대로 전달
        // RestClient, Repository 등 모든 DI 가능!

        worker.registerActivitiesImplementations(activities);
        //                                       ↑ 인스턴스! (Spring Bean)

        factory.start();
        return worker;
    }
}
```

### 왜 Workflow는 Spring Bean이 아닌가?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                  Workflow가 Spring Bean이면 안 되는 3가지 이유                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  이유 1: Replay (Event Sourcing) 때문                                        │
│  ────────────────────────────────────                                        │
│  - Temporal은 Workflow를 수천 번 재생성할 수 있음                             │
│  - 크래시 복구 시 매번 new OrderWorkflowImpl() 호출                          │
│  - Spring Context와 무관하게 생성되어야 함                                    │
│                                                                              │
│  이유 2: 결정적(Deterministic) 실행 보장                                      │
│  ───────────────────────────────────────                                     │
│  - Workflow는 외부 상태에 의존하면 안 됨                                      │
│  - Spring Bean 상태, DB 데이터, 시스템 시간은 변할 수 있음                    │
│  - Replay할 때마다 같은 결과가 나와야 함                                      │
│                                                                              │
│  이유 3: Temporal이 완전히 제어해야 함                                        │
│  ─────────────────────────────────────                                       │
│  - 언제 생성/중단/재개할지                                                   │
│  - 어느 Worker에서 실행할지                                                  │
│  - Spring이 관리하면 이 제어권이 분산됨                                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 실수하기 쉬운 패턴

```java
// ❌ 잘못된 예: Workflow에 @Component 붙이기
@Component  // 의미 없음! Temporal이 어차피 직접 생성함
public class OrderWorkflowImpl implements OrderWorkflow {

    @Autowired  // ❌ 작동 안 함! Temporal은 Spring을 모름
    private SomeService someService;
}

// ❌ 잘못된 예: Workflow에 생성자 주입 시도
public class OrderWorkflowImpl implements OrderWorkflow {

    private final SomeService someService;

    public OrderWorkflowImpl(SomeService someService) {  // ❌ Temporal은 기본 생성자만 사용
        this.someService = someService;
    }
}

// ✅ 올바른 예: Activity를 통해 외부 서비스 호출
public class OrderWorkflowImpl implements OrderWorkflow {

    // Activity Stub을 통해 호출
    private final SomeActivities activities = Workflow.newActivityStub(
        SomeActivities.class, options
    );

    @Override
    public void processOrder() {
        // Activity가 Spring Bean이므로 DI 받은 서비스 사용 가능
        activities.callSomeService();
    }
}
```

---

## 2. Workflow 코드는 어디에 저장되나?

### 핵심 질문
> "Workflow가 Temporal에 등록되는 것 같은데, DB에 저장되는 건가?"

### 한눈에 보는 답변

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         무엇이 어디에 있는가?                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────┐     │
│  │                Temporal Server + PostgreSQL (DB)                   │     │
│  │                                                                     │     │
│  │  ✅ 저장되는 것:                                                   │     │
│  │  ├── Event History (이벤트 목록)                                   │     │
│  │  │     - WorkflowExecutionStarted                                  │     │
│  │  │     - ActivityTaskScheduled                                     │     │
│  │  │     - ActivityTaskCompleted (결과값 포함)                       │     │
│  │  ├── Workflow 실행 상태                                            │     │
│  │  │     - workflowId, status, input, output                         │     │
│  │  └── Task Queue 정보                                               │     │
│  │                                                                     │     │
│  │  ❌ 저장 안 되는 것:                                               │     │
│  │  ├── Workflow 코드 (OrderWorkflowImpl.java)                        │     │
│  │  ├── Activity 코드 (OrderActivitiesImpl.java)                      │     │
│  │  └── 비즈니스 로직 자체                                            │     │
│  └────────────────────────────────────────────────────────────────────┘     │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────┐     │
│  │                Worker (Spring Boot Application)                    │     │
│  │                                                                     │     │
│  │  여기에 있는 것:                                                   │     │
│  │  ├── OrderWorkflowImpl.class (컴파일된 코드)                       │     │
│  │  ├── OrderActivitiesImpl.class (컴파일된 코드)                     │     │
│  │  └── 모든 비즈니스 로직                                            │     │
│  └────────────────────────────────────────────────────────────────────┘     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 비유로 이해하기

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          영화 촬영 비유                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Temporal Server = 영화사 본사 (기록 담당)                                   │
│  Worker = 촬영장 (실제 촬영)                                                 │
│  Workflow 코드 = 영화 대본                                                   │
│  Event History = 촬영 일지 ("오늘 3씬까지 촬영 완료")                        │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                                                                      │    │
│  │  영화사 본사(Temporal Server)에 저장되는 것:                         │    │
│  │  ───────────────────────────────────────────                         │    │
│  │  - 촬영 일지: "Scene 1 OK, Scene 2 OK, Scene 3 진행 중"             │    │
│  │  - 촬영 결과물 (각 씬의 결과)                                        │    │
│  │                                                                      │    │
│  │  영화사 본사에 저장 안 되는 것:                                      │    │
│  │  ───────────────────────────────────                                 │    │
│  │  - 대본 (비즈니스 로직)                                              │    │
│  │  - 촬영 기법 (코드 구현)                                             │    │
│  │  → 대본은 촬영장(Worker)에만 있음!                                   │    │
│  │                                                                      │    │
│  │  본사가 아는 것:                                                     │    │
│  │  "이 영화는 'OrderWorkflow'라는 대본을 사용하고, 3씬까지 완료됨"     │    │
│  │                                                                      │    │
│  │  본사가 모르는 것:                                                   │    │
│  │  "OrderWorkflow 대본에 뭐가 쓰여있는지, if문이 있는지, for문이 있는지"│    │
│  │                                                                      │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 실행 흐름에서 이해하기

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           실행 흐름 단계별 분석                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  [Step 1] Controller → Temporal Server                                      │
│  ─────────────────────────────────────                                       │
│      "OrderWorkflow 타입의 Workflow를 시작해줘"                              │
│      "입력값: {customerId: 1, productId: 2}"                                │
│                                                                              │
│  [Step 2] Temporal Server가 DB에 저장                                       │
│  ──────────────────────────────────────                                      │
│      DB 저장 내용:                                                          │
│      - workflowId: "order-abc123"                                           │
│      - workflowType: "OrderWorkflow"  ← 타입 이름만! 코드 없음!             │
│      - input: {customerId: 1, ...}                                          │
│      - status: RUNNING                                                       │
│                                                                              │
│      Task Queue에 Task 추가:                                                 │
│      - "order-task-queue"에 Workflow Task 넣음                              │
│                                                                              │
│  [Step 3] Worker가 Long Polling으로 Task 수신                                │
│  ──────────────────────────────────────────                                  │
│      "order-task-queue에 새 Task 왔네!"                                      │
│      Task 내용: workflowType: "OrderWorkflow"  ← 타입 이름만!               │
│                                                                              │
│  [Step 4] Worker 내부에서 코드 실행                                          │
│  ────────────────────────────────────                                        │
│      // "OrderWorkflow" 타입에 해당하는 클래스를 찾음                        │
│      Class<?> clazz = registeredWorkflows.get("OrderWorkflow");             │
│      // → OrderWorkflowImpl.class                                            │
│                                                                              │
│      Object instance = clazz.newInstance();  // 인스턴스 생성               │
│      instance.processOrder(input);           // 코드 실행!                  │
│                                                                              │
│  핵심: Temporal Server는 "OrderWorkflow"라는 이름만 알고,                   │
│        실제 코드는 Worker가 가지고 있음!                                     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Workflow 수정/추가 시 어떻게 되는가?

### 케이스 1: 새 Workflow 추가

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         새 Workflow 추가 절차                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  [Step 1] 코드 작성                                                         │
│  ─────────────────                                                           │
│      @WorkflowInterface                                                      │
│      public interface PaymentWorkflow { ... }                                │
│                                                                              │
│      public class PaymentWorkflowImpl implements PaymentWorkflow { ... }    │
│                                                                              │
│  [Step 2] Worker에 등록                                                      │
│  ──────────────────                                                          │
│      worker.registerWorkflowImplementationTypes(                             │
│          OrderWorkflowImpl.class,                                            │
│          PaymentWorkflowImpl.class   // ← 추가!                             │
│      );                                                                      │
│                                                                              │
│  [Step 3] Worker 재배포 (Spring Boot 재시작)                                 │
│  ─────────────────────────────────────────                                   │
│      → Temporal Server 설정 변경 불필요!                                     │
│      → 코드가 Worker에 있으므로 Worker만 재배포하면 됨                       │
│                                                                              │
│  ✅ 간단함! Temporal Server는 건드릴 필요 없음                               │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 케이스 2: 기존 Workflow 수정 (위험!)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    기존 Workflow 수정 시 발생하는 문제                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  상황: 실행 중인 Workflow가 있는데 코드를 수정함                             │
│                                                                              │
│  ┌────────── 수정 전 ──────────┐    ┌────────── 수정 후 ──────────┐        │
│  │ public OrderResult process()│    │ public OrderResult process()│        │
│  │ {                           │    │ {                           │        │
│  │   createOrder();            │    │   createOrder();            │        │
│  │   reserveStock();  // Step 2│    │   validateStock();  // NEW! │        │
│  │   processPayment();         │    │   reserveStock();           │        │
│  │ }                           │    │   processPayment();         │        │
│  └─────────────────────────────┘    └─────────────────────────────┘        │
│                                                                              │
│  문제 발생!                                                                  │
│  ───────────                                                                 │
│  - 기존 Workflow의 Event History: createOrder → reserveStock                │
│  - 새 코드를 Replay하면: createOrder → validateStock (???)                 │
│  - History와 코드가 일치하지 않음!                                          │
│                                                                              │
│  결과: ❌ Non-Deterministic Error!                                          │
│        Workflow가 복구 불가능해질 수 있음                                    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 케이스 3: 안전한 수정 방법 (Versioning)

```java
// ═══════════════════════════════════════════════════════════════════════════
// Versioning을 사용한 안전한 Workflow 수정
// ═══════════════════════════════════════════════════════════════════════════

public class OrderWorkflowImpl implements OrderWorkflow {

    @Override
    public OrderResult processOrder(OrderRequest request) {

        Long orderId = activities.createOrder(request.customerId());

        // ─────────────────────────────────────────────────────────────────
        // Versioning: 기존 코드와 새 코드를 안전하게 공존시킴
        // ─────────────────────────────────────────────────────────────────
        int version = Workflow.getVersion(
            "add-stock-validation",    // 변경 ID (고유한 이름)
            Workflow.DEFAULT_VERSION,  // 최소 버전 (-1, 기존 코드)
            1                          // 최대 버전 (1, 새 코드)
        );

        // version 값에 따라 분기
        if (version >= 1) {
            // ✅ 새로 시작되는 Workflow만 실행
            activities.validateStock(request.productId());
        }
        // ✅ 기존 Workflow는 이 블록을 스킵

        activities.reserveStock(request.productId(), request.quantity());
        activities.processPayment(orderId);

        return OrderResult.success(orderId);
    }
}
```

**Versioning 동작 원리:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Versioning 동작 원리                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  기존 Workflow Replay 시:                                                    │
│  ─────────────────────────                                                   │
│  Event History에 "add-stock-validation" 관련 이벤트 없음                    │
│  → Workflow.getVersion() 반환값: -1 (DEFAULT_VERSION)                       │
│  → if (version >= 1) 조건 불충족                                            │
│  → validateStock() 스킵                                                      │
│  → 기존 History와 일치! ✅                                                   │
│                                                                              │
│  새 Workflow 실행 시:                                                        │
│  ───────────────────                                                         │
│  Event History에 "add-stock-validation" 이벤트 기록됨                       │
│  → Workflow.getVersion() 반환값: 1                                          │
│  → if (version >= 1) 조건 충족                                              │
│  → validateStock() 실행                                                      │
│  → 새 코드대로 실행! ✅                                                      │
│                                                                              │
│  결과: 기존/신규 Workflow 모두 정상 동작!                                    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 재배포 필요 여부 정리

| 상황 | 재배포 필요? | 비고 |
|------|------------|------|
| 새 Workflow 추가 | ✅ Worker 재배포 | Temporal Server 변경 불필요 |
| 새 Activity 추가 | ✅ Worker 재배포 | |
| Workflow/Activity 코드 수정 | ✅ Worker 재배포 + Versioning | 실행 중 Workflow 있으면 Versioning 필수 |
| Workflow 시작/실행 | ❌ | |
| Signal/Query 전송 | ❌ | |

---

## 4. 재시도 계속 실패하면 어떻게 되는가?

### 재시도 설정

```java
ActivityOptions options = ActivityOptions.newBuilder()
    .setStartToCloseTimeout(Duration.ofSeconds(30))
    .setRetryOptions(RetryOptions.newBuilder()
        .setMaximumAttempts(3)           // 최대 3번 시도
        .setInitialInterval(Duration.ofSeconds(1))  // 첫 재시도 대기: 1초
        .setBackoffCoefficient(2.0)      // 지수 백오프: 1초 → 2초 → 4초
        .setMaximumInterval(Duration.ofMinutes(1))  // 최대 대기: 1분
        .build())
    .build();
```

### 재시도 흐름 시각화

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           재시도 흐름                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  시도 1 ────▶ ❌ 실패 ────▶ 1초 대기                                        │
│                              │                                               │
│                              ▼                                               │
│  시도 2 ────▶ ❌ 실패 ────▶ 2초 대기 (1초 × 2.0)                            │
│                              │                                               │
│                              ▼                                               │
│  시도 3 ────▶ ❌ 실패 ────▶ MaximumAttempts 도달!                           │
│                              │                                               │
│                              ▼                                               │
│                    ActivityFailure 예외 발생                                 │
│                              │                                               │
│                ┌─────────────┴─────────────┐                                │
│                ▼                           ▼                                │
│    [예외 처리 없으면]              [Saga 패턴 사용 시]                        │
│    Workflow 상태: FAILED          보상 트랜잭션 실행                         │
│    수동 개입 필요                 Workflow 상태: COMPLETED                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 시나리오 1: 예외 처리 없음 → Workflow 실패

```java
public OrderResult processOrder(OrderRequest request) {
    Order order = activities.createOrder(request);
    activities.reserveStock(...);  // 여기서 3번 다 실패!
    activities.processPayment(...);  // 실행 안 됨
    return new OrderResult(...);  // 도달 못 함
}

// 결과:
// - Workflow 상태: FAILED
// - Temporal UI에서 확인 가능
// - 수동 개입 필요 (운영자가 확인 후 조치)
```

### 시나리오 2: Saga 패턴으로 보상 실행

```java
public OrderResult processOrder(OrderRequest request) {
    Saga saga = new Saga(new Saga.Options.Builder().build());

    try {
        // T1: 주문 생성
        Long orderId = activities.createOrder(request);
        saga.addCompensation(() -> activities.cancelOrder(orderId));

        // T2: 재고 예약 - 여기서 3번 실패!
        activities.reserveStock(...);
        saga.addCompensation(() -> activities.cancelReservation(...));

        // T3: 결제 처리 (도달 못 함)
        // ...

    } catch (ActivityFailure e) {
        // 보상 트랜잭션 실행 (등록된 역순으로)
        saga.compensate();
        return OrderResult.failure(e.getMessage());
    }
}

// 실행 결과:
// T1: createOrder ✅ 성공
// T2: reserveStock ❌ 3번 실패 → ActivityFailure 발생
// catch 블록 진입
// C1: cancelOrder ✅ 보상 실행 (주문 취소)
// Workflow 상태: COMPLETED (정상 종료!)
// 반환값: OrderResult(null, "FAILED", "재고 부족")
```

### 시나리오 3: 보상도 실패하면?

```java
Saga saga = new Saga(new Saga.Options.Builder()
    .setContinueWithError(true)  // 보상 실패해도 계속 진행
    .build());

try {
    // 비즈니스 로직...
} catch (ActivityFailure e) {
    try {
        saga.compensate();  // 보상 중에도 실패할 수 있음!
    } catch (Exception compensationError) {
        // 보상도 실패 → 알림 발송 + 수동 처리 대기열에 추가
        activities.notifyOperator(
            "보상 실패! 수동 확인 필요",
            request,
            compensationError.getMessage()
        );
    }
    return OrderResult.failure(e.getMessage());
}
```

### 재시도하면 안 되는 에러 설정

```java
RetryOptions.newBuilder()
    .setMaximumAttempts(3)
    // 이 예외들은 재시도 안 함 (즉시 실패 처리)
    .setDoNotRetry(
        IllegalArgumentException.class.getName(),    // 잘못된 입력
        InsufficientStockException.class.getName(),  // 재고 부족 (비즈니스 오류)
        AuthorizationException.class.getName()       // 권한 없음
    )
    .build();
```

**재시도 해야 하는 vs 하면 안 되는 에러:**

| 재시도 해야 하는 에러 | 하면 안 되는 에러 |
|--------------------|------------------|
| 네트워크 타임아웃 | 재고 부족 |
| 일시적 서버 오류 (503) | 잘못된 요청 (400) |
| DB 연결 끊김 | 비즈니스 규칙 위반 |
| 외부 API 일시 장애 | 권한 없음 (401, 403) |

---

## 5. Signal과 Query 상세

### 왜 필요한가?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       Signal과 Query가 필요한 상황                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  [상황 1] 고객이 "주문 취소"를 요청함                                        │
│           → 실행 중인 Workflow에 "취소해!" 라고 알려야 함                    │
│           → Signal 사용                                                      │
│                                                                              │
│  [상황 2] 관리자가 "이 주문 지금 어디까지 진행됐어?"                          │
│           → Workflow의 현재 상태를 조회해야 함                               │
│           → Query 사용                                                       │
│                                                                              │
│  [상황 3] 결제 승인 대기 중에 "카드 정보 변경해줘"                            │
│           → 실행 중인 Workflow에 새 정보를 전달해야 함                       │
│           → Signal 사용                                                      │
│                                                                              │
│  [상황 4] 고객센터에서 "이 주문 처리 완료된 단계 알려줘"                      │
│           → Workflow의 완료된 단계 목록 조회                                 │
│           → Query 사용                                                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Signal vs Query 비교

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Signal vs Query 비교                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  구분         │ Signal                  │ Query                     │    │
│  ├───────────────┼─────────────────────────┼───────────────────────────┤    │
│  │  방향         │ 외부 → Workflow         │ 외부 → Workflow → 외부    │    │
│  │  목적         │ 상태 변경 요청          │ 상태 조회                 │    │
│  │  동기/비동기   │ 비동기 (fire & forget) │ 동기 (응답 대기)          │    │
│  │  상태 변경     │ ✅ 가능                │ ❌ 불가                   │    │
│  │  리턴 값      │ 없음 (void)             │ 있음                      │    │
│  │  History 저장 │ ✅ 저장됨               │ ❌ 저장 안 됨             │    │
│  │  비유         │ 편지 보내기             │ 전화로 물어보기            │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  Signal = 편지                              Query = 전화                     │
│  ────────────                               ───────────                      │
│  - 보내고 끝 (응답 안 기다림)               - 즉시 응답                      │
│  - 상대방이 읽고 행동할 수 있음             - 읽기만 가능                    │
│  - 기록에 남음                              - 기록에 안 남음                 │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Signal 구현 완전 가이드

```java
// ═══════════════════════════════════════════════════════════════════════════
// 1. Workflow 인터페이스에 Signal 정의
// ═══════════════════════════════════════════════════════════════════════════

@WorkflowInterface
public interface OrderWorkflow {

    @WorkflowMethod
    OrderResult processOrder(OrderRequest request);

    // Signal 메서드들 (여러 개 가능)
    @SignalMethod
    void cancelOrder(String reason);

    @SignalMethod
    void updatePaymentMethod(String newCardToken);

    @SignalMethod
    void addNote(String note);
}

// ═══════════════════════════════════════════════════════════════════════════
// 2. Workflow 구현에서 Signal 처리
// ═══════════════════════════════════════════════════════════════════════════

public class OrderWorkflowImpl implements OrderWorkflow {

    // Signal로 변경될 상태 변수들
    private boolean cancelRequested = false;
    private String cancelReason = null;
    private String cardToken = null;
    private List<String> notes = new ArrayList<>();

    @Override
    public OrderResult processOrder(OrderRequest request) {
        Long orderId = activities.createOrder(request);

        // ─────────────────────────────────────────────────────────────────
        // Signal 체크 포인트 1
        // ─────────────────────────────────────────────────────────────────
        // Signal이 언제 올지 모르므로, 중요한 지점마다 체크
        if (cancelRequested) {
            activities.cancelOrder(orderId, cancelReason);
            return new OrderResult(orderId, "CANCELLED", cancelReason);
        }

        activities.reserveStock(...);

        // ─────────────────────────────────────────────────────────────────
        // Signal 체크 포인트 2
        // ─────────────────────────────────────────────────────────────────
        if (cancelRequested) {
            // 이미 재고 예약됐으므로 보상 필요
            activities.cancelReservation(...);
            activities.cancelOrder(orderId, cancelReason);
            return new OrderResult(orderId, "CANCELLED", cancelReason);
        }

        // cardToken이 Signal로 변경됐을 수 있음
        activities.processPayment(orderId, cardToken != null ? cardToken : request.getCardToken());

        return new OrderResult(orderId, "COMPLETED", null);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Signal 핸들러들
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void cancelOrder(String reason) {
        this.cancelRequested = true;
        this.cancelReason = reason;
        // 다음 체크 포인트에서 취소 처리됨
    }

    @Override
    public void updatePaymentMethod(String newCardToken) {
        this.cardToken = newCardToken;
        // 결제 처리 시 새 카드 사용됨
    }

    @Override
    public void addNote(String note) {
        this.notes.add(note);
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 3. 외부에서 Signal 보내기
// ═══════════════════════════════════════════════════════════════════════════

@RestController
public class OrderController {

    private final WorkflowClient workflowClient;

    @PostMapping("/orders/{workflowId}/cancel")
    public ResponseEntity<?> cancelOrder(
            @PathVariable String workflowId,
            @RequestBody CancelRequest request) {

        // Workflow Stub 생성 (실행 중인 Workflow에 연결)
        OrderWorkflow workflow = workflowClient.newWorkflowStub(
            OrderWorkflow.class,
            workflowId  // Workflow ID로 특정 인스턴스 찾음
        );

        // Signal 전송!
        workflow.cancelOrder(request.getReason());

        // 비동기이므로 즉시 반환
        return ResponseEntity.ok("취소 요청이 전송되었습니다");
    }
}
```

### Query 구현 완전 가이드

```java
// ═══════════════════════════════════════════════════════════════════════════
// 1. Workflow 인터페이스에 Query 정의
// ═══════════════════════════════════════════════════════════════════════════

@WorkflowInterface
public interface OrderWorkflow {

    @WorkflowMethod
    OrderResult processOrder(OrderRequest request);

    // Query 메서드들 (여러 개 가능)
    @QueryMethod
    String getStatus();

    @QueryMethod
    List<String> getCompletedSteps();

    @QueryMethod
    OrderDetail getDetail();
}

// ═══════════════════════════════════════════════════════════════════════════
// 2. Workflow 구현에서 Query 응답
// ═══════════════════════════════════════════════════════════════════════════

public class OrderWorkflowImpl implements OrderWorkflow {

    // Query로 조회될 상태 변수들
    private String currentStatus = "INITIALIZED";
    private List<String> completedSteps = new ArrayList<>();
    private Long orderId = null;

    @Override
    public OrderResult processOrder(OrderRequest request) {
        currentStatus = "CREATING_ORDER";
        orderId = activities.createOrder(request);
        completedSteps.add("ORDER_CREATED");

        currentStatus = "RESERVING_STOCK";
        activities.reserveStock(...);
        completedSteps.add("STOCK_RESERVED");

        currentStatus = "PROCESSING_PAYMENT";
        activities.processPayment(...);
        completedSteps.add("PAYMENT_PROCESSED");

        currentStatus = "COMPLETED";
        return new OrderResult(orderId, "COMPLETED", null);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Query 핸들러들 (읽기만!)
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public String getStatus() {
        return this.currentStatus;  // 단순히 현재 상태 반환
    }

    @Override
    public List<String> getCompletedSteps() {
        // 방어적 복사 (외부에서 리스트 변경 방지)
        return new ArrayList<>(this.completedSteps);
    }

    @Override
    public OrderDetail getDetail() {
        return new OrderDetail(orderId, currentStatus, completedSteps);
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 3. 외부에서 Query 호출
// ═══════════════════════════════════════════════════════════════════════════

@RestController
public class OrderController {

    @GetMapping("/orders/{workflowId}/status")
    public ResponseEntity<?> getStatus(@PathVariable String workflowId) {

        OrderWorkflow workflow = workflowClient.newWorkflowStub(
            OrderWorkflow.class,
            workflowId
        );

        // Query 호출 - 동기적으로 즉시 결과 반환!
        String status = workflow.getStatus();

        return ResponseEntity.ok(Map.of("status", status));
    }

    @GetMapping("/orders/{workflowId}/detail")
    public ResponseEntity<?> getDetail(@PathVariable String workflowId) {

        OrderWorkflow workflow = workflowClient.newWorkflowStub(
            OrderWorkflow.class,
            workflowId
        );

        OrderDetail detail = workflow.getDetail();

        return ResponseEntity.ok(detail);
    }
}
```

### Query 사용 시 주의사항

```java
// ═══════════════════════════════════════════════════════════════════════════
// Query에서 하면 안 되는 것들
// ═══════════════════════════════════════════════════════════════════════════

// ❌ 잘못된 예 1: Query에서 상태 변경 시도
@QueryMethod
public String getStatus() {
    this.accessCount++;  // ❌ 상태 변경하면 안 됨!
    return this.currentStatus;
}

// ❌ 잘못된 예 2: Query에서 Activity 호출
@QueryMethod
public OrderDetail getDetail() {
    // ❌ Activity 호출하면 안 됨!
    return activities.fetchOrderDetail(orderId);
}

// ❌ 잘못된 예 3: Query에서 외부 호출
@QueryMethod
public ExternalData getExternalData() {
    // ❌ 외부 API 호출하면 안 됨!
    return restTemplate.getForObject(...);
}

// ✅ 올바른 예: 순수하게 메모리 상태만 반환
@QueryMethod
public String getStatus() {
    return this.currentStatus;  // 단순 읽기만!
}

@QueryMethod
public OrderDetail getDetail() {
    // 이미 저장된 상태만 조합하여 반환
    return new OrderDetail(orderId, currentStatus, completedSteps);
}
```

### 실전 패턴: Signal + Query 조합

```java
// 취소 요청 후 결과 확인하기
@PostMapping("/orders/{workflowId}/cancel")
public ResponseEntity<?> cancelOrder(@PathVariable String workflowId) {

    OrderWorkflow workflow = workflowClient.newWorkflowStub(
        OrderWorkflow.class, workflowId
    );

    // 1. 취소 Signal 전송 (비동기)
    workflow.cancelOrder("고객 변심");

    // 2. Query로 상태 확인 (폴링)
    for (int i = 0; i < 10; i++) {
        String status = workflow.getStatus();

        if ("CANCELLED".equals(status)) {
            return ResponseEntity.ok("취소 완료!");
        }
        if ("COMPLETED".equals(status)) {
            return ResponseEntity.badRequest().body("이미 완료된 주문입니다");
        }

        Thread.sleep(500);  // 0.5초 대기
    }

    return ResponseEntity.ok("취소 처리 중...");
}
```

---

## 6. 인프라 구성과 Spring 연동

### 전체 인프라 구조도

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           전체 인프라 구성도                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │                    Temporal Cluster (Docker)                          │   │
│  │                                                                       │   │
│  │  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐                 │   │
│  │  │  Frontend   │   │   History   │   │  Matching   │                 │   │
│  │  │  Service    │   │   Service   │   │   Service   │                 │   │
│  │  │  (gRPC)     │   │             │   │             │                 │   │
│  │  │  :21733     │   │             │   │             │                 │   │
│  │  └──────┬──────┘   └──────┬──────┘   └──────┬──────┘                 │   │
│  │         │                 │                 │                         │   │
│  │         └─────────────────┼─────────────────┘                         │   │
│  │                           │                                           │   │
│  │                    ┌──────▼──────┐                                    │   │
│  │                    │  PostgreSQL │  ← Event History 저장              │   │
│  │                    │   :21432    │                                    │   │
│  │                    └─────────────┘                                    │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                           │                                                  │
│                           │ gRPC (:21733)                                   │
│                           │                                                  │
│  ┌────────────────────────▼─────────────────────────────────────────────┐   │
│  │              Spring Boot Application (Worker)                         │   │
│  │                                                                       │   │
│  │   WorkflowClient ──────────────────────────────────────┐              │   │
│  │         │                                              │              │   │
│  │         │ gRPC                                         │              │   │
│  │         ▼                                              ▼              │   │
│  │   ┌──────────────┐                            ┌───────────────┐      │   │
│  │   │    Worker    │  ◀── Long Polling ──────── │  Task Queue   │      │   │
│  │   │              │      (gRPC Stream)         │               │      │   │
│  │   │  ┌────────┐  │                            └───────────────┘      │   │
│  │   │  │Workflow│  │                                                   │   │
│  │   │  └────────┘  │                                                   │   │
│  │   │  ┌────────┐  │                                                   │   │
│  │   │  │Activity│──┼──────── HTTP ────────────▶ [Order Service]        │   │
│  │   │  └────────┘  │                          ▶ [Inventory Service]    │   │
│  │   └──────────────┘                          ▶ [Payment Service]      │   │
│  │                                                                       │   │
│  └───────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │                         Temporal UI                                   │   │
│  │                         :21088                                        │   │
│  │                         http://localhost:21088                        │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Temporal Server 내부 구조

| 서비스 | 역할 | 설명 |
|--------|------|------|
| **Frontend Service** | 클라이언트 요청 수신 | gRPC API Gateway 역할, 모든 요청의 진입점 |
| **History Service** | Workflow 상태 관리 | Event History 기록, Workflow 상태 추적 |
| **Matching Service** | Task 분배 | Task Queue 관리, Worker에게 Task 분배 |
| **Worker Service** | 내부 Workflow 실행 | 시스템 내부 Workflow (타이머, 스케줄 등) |

### 포트 정리

| 구성 요소 | 포트 | 용도 |
|----------|------|------|
| Temporal Server gRPC | 21733 | SDK가 연결하는 포트 |
| Temporal PostgreSQL | 21432 | Temporal 내부용 (직접 접근 X) |
| Temporal UI | 21088 | 브라우저로 접속하여 모니터링 |
| orchestrator-temporal | 21081 | Temporal Workflow API |
| service-order | 21082 | 주문 서비스 |
| service-inventory | 21083 | 재고 서비스 |
| service-payment | 21084 | 결제 서비스 |

---

## 7. gRPC와 Long Polling 동작 원리

### gRPC 통신의 장점

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           gRPC 통신 특징                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Temporal은 HTTP/2 기반 gRPC를 사용합니다:                                   │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  gRPC의 장점:                                                        │    │
│  │                                                                      │    │
│  │  1. Binary Protocol (Protobuf)                                       │    │
│  │     → JSON보다 빠른 직렬화/역직렬화                                  │    │
│  │     → 네트워크 트래픽 감소                                           │    │
│  │                                                                      │    │
│  │  2. HTTP/2 Multiplexing                                              │    │
│  │     → 단일 TCP 연결로 다중 요청 처리                                 │    │
│  │     → 연결 오버헤드 감소                                             │    │
│  │                                                                      │    │
│  │  3. Streaming 지원                                                   │    │
│  │     → Long Polling에 적합                                            │    │
│  │     → 양방향 통신 가능                                               │    │
│  │                                                                      │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  Spring Boot                           Temporal Server                       │
│  ┌────────────┐                        ┌────────────┐                       │
│  │            │════ TCP/TLS (HTTP/2) ═▶│            │                       │
│  │ gRPC       │                        │ :7233      │                       │
│  │ Channel    │                        │            │                       │
│  │            │◀═══════════════════════│            │                       │
│  └────────────┘     Bi-directional     └────────────┘                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Long Polling 동작 원리

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       Long Polling 동작 원리                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│   ┌─────────────────┐                          ┌─────────────────────────┐  │
│   │     Worker      │                          │    Temporal Server      │  │
│   │   (Spring Boot) │                          │    (Matching Service)   │  │
│   └────────┬────────┘                          └────────────┬────────────┘  │
│            │                                                │               │
│            │  PollWorkflowTaskQueue                         │               │
│            │  (gRPC Request)                                │               │
│            │  "order-task-queue 작업 있으면 줘"              │               │
│            ├───────────────────────────────────────────────▶│               │
│            │                                                │               │
│            │                        (작업 없으면 대기...)    │               │
│            │                        (최대 60초까지)         │               │
│            │                                                │               │
│            │  ─────── 새 Workflow 시작 요청 도착 ──────────▶│               │
│            │                                                │               │
│            │  PollWorkflowTaskQueue Response                │               │
│            │  (WorkflowTask 반환)                           │               │
│            │◀───────────────────────────────────────────────┤               │
│            │                                                │               │
│            │  Workflow 코드 실행...                         │               │
│            │                                                │               │
│            │  RespondWorkflowTaskCompleted                  │               │
│            │  (실행 결과 + 다음 작업 요청)                   │               │
│            ├───────────────────────────────────────────────▶│               │
│            │                                                │               │
│                                                                              │
│   Long Polling 특징:                                                        │
│   ─────────────────                                                          │
│   • 연결을 열어두고 작업이 올 때까지 대기 (Push 효과)                         │
│   • 타임아웃 시 재연결 (기본 60초)                                           │
│   • 서버 부하 ↓ (지속적인 폴링보다 효율적)                                    │
│   • 실시간성 ↑ (작업 도착 즉시 전달)                                         │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Spring Boot 시작 시 연결 과정

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Spring Boot 시작 시 연결 과정                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  [Step 1] WorkflowServiceStubs 생성 (gRPC 채널)                              │
│           → Temporal Server 연결 (localhost:21733)                           │
│           → 연결 실패 시 애플리케이션 시작 실패!                              │
│                                                                              │
│  [Step 2] WorkflowClient 생성                                                │
│           → Namespace 설정 ("default")                                       │
│           → Workflow 시작/Signal/Query에 사용                                │
│                                                                              │
│  [Step 3] OrderActivitiesImpl Bean 생성 (@Component)                         │
│           → RestClient 초기화                                                │
│           → Repository, 기타 의존성 주입                                     │
│                                                                              │
│  [Step 4] Worker Bean 생성                                                   │
│           → registerWorkflowImplementationTypes(OrderWorkflowImpl.class)    │
│           → registerActivitiesImplementations(orderActivities)              │
│                                                                              │
│  [Step 5] factory.start()                                                    │
│           → 별도 스레드에서 Long Polling 시작!                               │
│           → PollWorkflowTaskQueue, PollActivityTaskQueue                    │
│                                                                              │
│  [Step 6] 애플리케이션 Ready                                                 │
│           → Controller가 요청 받을 준비 완료                                 │
│           → Worker가 백그라운드에서 Task 대기 중                             │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 8. Workflow ID와 Run ID는 무엇인가?

### 핵심 질문
> "Workflow ID와 Run ID가 뭐가 다른가?"

### 답변

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       Workflow ID vs Run ID                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Workflow ID = 비즈니스 식별자 (당신이 정함)                                 │
│  ─────────────────────────────────────────                                   │
│  • 예: "order-12345", "payment-67890"                                        │
│  • 비즈니스적으로 의미 있는 ID                                               │
│  • 중복 실행 방지에 사용                                                     │
│  • 같은 ID로 Workflow를 재시작할 수 있음                                     │
│                                                                              │
│  Run ID = 실행 식별자 (Temporal이 생성)                                      │
│  ────────────────────────────────────                                        │
│  • 예: "a1b2c3d4-e5f6-7890-abcd-ef1234567890"                               │
│  • UUID 형식, Temporal이 자동 생성                                           │
│  • 같은 Workflow ID라도 실행마다 다른 Run ID                                 │
│  • Event History를 구분하는 데 사용                                          │
│                                                                              │
│  비유:                                                                       │
│  ──────                                                                      │
│  Workflow ID = 학번 (고유, 변하지 않음)                                      │
│  Run ID = 학기 등록 번호 (매 학기마다 다름)                                   │
│                                                                              │
│  같은 학생(Workflow ID: student-123)이                                       │
│  1학기(Run ID: run-abc), 2학기(Run ID: run-xyz)... 계속 등록                │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 중복 실행 방지

```java
// Workflow ID를 사용한 중복 실행 방지
WorkflowOptions options = WorkflowOptions.newBuilder()
    .setTaskQueue("order-queue")
    // 비즈니스 ID를 Workflow ID로 사용
    .setWorkflowId("order-" + orderId)  // 예: "order-12345"
    // 중복 실행 정책
    .setWorkflowIdReusePolicy(
        WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE
    )
    .build();

// 같은 orderId로 다시 시작하려고 하면?
// → WorkflowExecutionAlreadyStartedException 발생!
```

---

## 9. Child Workflow는 언제 사용하는가?

### 핵심 질문
> "Child Workflow가 뭐고 언제 사용하는가?"

### 답변

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Child Workflow                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Child Workflow = Workflow 안에서 다른 Workflow를 실행하는 것                │
│                                                                              │
│  Parent Workflow                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  processOrder() {                                                    │    │
│  │      createOrder();          // Activity                             │    │
│  │                                                                      │    │
│  │      // Child Workflow 실행                                          │    │
│  │      paymentWorkflow.processPayment(orderId);  // ← Child!          │    │
│  │                                                                      │    │
│  │      confirmOrder();         // Activity                             │    │
│  │  }                                                                   │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                    │                                                         │
│                    ▼                                                         │
│  Child Workflow (PaymentWorkflow)                                            │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  processPayment() {                                                  │    │
│  │      validateCard();                                                 │    │
│  │      chargeCard();                                                   │    │
│  │      sendReceipt();                                                  │    │
│  │  }                                                                   │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 언제 사용하는가?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     Child Workflow 사용 시점                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. 로직이 복잡하여 분리가 필요할 때                                          │
│     → 결제 로직이 너무 복잡해서 별도 Workflow로 분리                          │
│                                                                              │
│  2. 다른 팀이 관리하는 로직을 호출할 때                                       │
│     → 결제팀의 PaymentWorkflow를 주문팀에서 호출                              │
│                                                                              │
│  3. 부분 재시도가 필요할 때                                                   │
│     → Parent가 실패해도 Child는 유지                                         │
│     → Child만 따로 재시도 가능                                               │
│                                                                              │
│  4. Event History 크기 관리                                                   │
│     → 하나의 Workflow에 이벤트가 너무 많으면 분리                             │
│                                                                              │
│  주의: 간단한 경우는 Activity로 충분!                                         │
│        Child Workflow는 오버헤드가 있으므로 신중하게 사용                      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 코드 예시

```java
public class OrderWorkflowImpl implements OrderWorkflow {

    // Child Workflow Stub 생성
    private final PaymentWorkflow paymentWorkflow = Workflow.newChildWorkflowStub(
        PaymentWorkflow.class,
        ChildWorkflowOptions.newBuilder()
            .setWorkflowId("payment-" + Workflow.getInfo().getWorkflowId())
            .setTaskQueue("payment-queue")
            .build()
    );

    @Override
    public OrderResult processOrder(OrderRequest request) {
        Long orderId = activities.createOrder(request);

        // Child Workflow 호출 (동기적으로 결과 대기)
        PaymentResult paymentResult = paymentWorkflow.processPayment(orderId);

        if (!paymentResult.isSuccess()) {
            activities.cancelOrder(orderId);
            return OrderResult.failure("결제 실패");
        }

        activities.confirmOrder(orderId);
        return OrderResult.success(orderId);
    }
}
```

---

## 10. Temporal Cloud vs Self-Hosted

### 비교표

| 항목 | Temporal Cloud | Self-Hosted |
|------|---------------|-------------|
| **운영 부담** | 없음 (관리형) | 높음 (직접 운영) |
| **비용** | 사용량 기반 과금 | 인프라 비용 |
| **확장성** | 자동 (무제한) | 직접 설정 |
| **가용성** | SLA 보장 (99.99%) | 직접 구성 |
| **보안** | SOC2, HIPAA 등 | 직접 구성 |
| **커스터마이징** | 제한적 | 자유로움 |
| **학습/개발** | 무료 티어 제공 | Docker로 간단히 시작 |

### 언제 무엇을 선택?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        선택 가이드                                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Temporal Cloud 선택:                                                        │
│  ────────────────────                                                        │
│  • 운영 인력이 부족할 때                                                      │
│  • 빠르게 프로덕션에 배포해야 할 때                                           │
│  • SLA가 중요할 때                                                           │
│  • 글로벌 서비스일 때 (Multi-Region)                                         │
│                                                                              │
│  Self-Hosted 선택:                                                           │
│  ──────────────────                                                          │
│  • 학습/개발 목적                                                            │
│  • 데이터 주권이 중요할 때 (금융, 의료)                                       │
│  • 기존 인프라와 통합이 필요할 때                                             │
│  • 비용 최적화가 필요할 때 (대규모)                                           │
│                                                                              │
│  현재 프로젝트 (학습 목적):                                                   │
│  → Self-Hosted (Docker Compose)로 충분!                                      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 11. 성능과 한계

### Temporal의 성능 특성

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         성능 수치 (참고용)                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Event History 크기 제한:                                                    │
│  • 기본: 50,000 이벤트                                                       │
│  • 이벤트가 많으면 Child Workflow로 분리                                     │
│                                                                              │
│  Workflow 실행 시간:                                                         │
│  • 제한 없음 (며칠, 몇 달, 몇 년도 가능)                                      │
│  • 단, Event History 크기는 관리 필요                                         │
│                                                                              │
│  처리량 (Temporal Cloud 기준):                                               │
│  • 초당 수만 개의 Workflow 시작 가능                                         │
│  • Worker 수에 따라 확장                                                     │
│                                                                              │
│  지연 시간:                                                                  │
│  • Workflow 시작: < 50ms                                                     │
│  • Activity 스케줄: < 50ms                                                   │
│  • 실제 실행 시간은 비즈니스 로직에 따라 다름                                 │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Event History 크기 관리

```java
// Event History가 너무 커지면?
// 해결책 1: ContinueAsNew
public class LongRunningWorkflowImpl implements LongRunningWorkflow {

    private int eventCount = 0;

    @Override
    public void run() {
        while (true) {
            activities.doSomething();
            eventCount++;

            // 이벤트가 많아지면 새 실행으로 이어서 계속
            if (eventCount > 10000) {
                Workflow.continueAsNew();  // 새 Run ID로 재시작
            }
        }
    }
}

// 해결책 2: Child Workflow로 분리
public class ParentWorkflowImpl implements ParentWorkflow {
    @Override
    public void process(List<Item> items) {
        // 아이템이 많으면 각각 Child Workflow로 처리
        for (Item item : items) {
            childWorkflow.processItem(item);  // 별도 History로 관리
        }
    }
}
```

---

## 12. 모니터링과 디버깅

### Temporal UI 활용

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Temporal UI 기능                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  URL: http://localhost:21088                                                 │
│                                                                              │
│  1. Workflow 목록 조회                                                        │
│     • 상태별 필터 (Running, Completed, Failed, Canceled)                     │
│     • 날짜, Workflow Type, Workflow ID로 검색                                │
│                                                                              │
│  2. Workflow 상세 보기                                                        │
│     • 입력/출력 값 확인                                                       │
│     • Event History 전체 조회                                                 │
│     • 현재 대기 중인 Activity 확인                                            │
│     • 타임라인 시각화                                                         │
│                                                                              │
│  3. 작업 도구                                                                 │
│     • Signal 전송                                                            │
│     • Query 실행                                                             │
│     • Workflow 취소/종료                                                     │
│     • 재시작 (Reset)                                                         │
│                                                                              │
│  4. 시스템 모니터링                                                           │
│     • Task Queue 상태                                                        │
│     • Worker 연결 상태                                                        │
│     • Namespace 설정                                                         │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 디버깅 팁

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         디버깅 팁                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. Event History 분석                                                        │
│     • Temporal UI에서 각 이벤트 클릭하여 상세 정보 확인                       │
│     • 언제 어떤 Activity가 실행됐는지 확인                                    │
│     • 실패한 Activity의 에러 메시지 확인                                      │
│                                                                              │
│  2. 로깅                                                                     │
│     • Activity에서 로깅 자유롭게 가능                                         │
│     • Workflow에서도 로깅 가능 (Replay 시에도 실행됨, 주의!)                  │
│                                                                              │
│  3. Query로 상태 확인                                                         │
│     • 실행 중인 Workflow의 현재 상태 확인                                     │
│     • 어떤 단계에서 멈춰있는지 파악                                           │
│                                                                              │
│  4. 로컬 테스트                                                               │
│     • TestWorkflowEnvironment로 단위 테스트                                   │
│     • 시간을 빠르게 진행시켜 타이머 테스트                                    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 13. 일반적인 실수와 트러블슈팅

### 자주 하는 실수들

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          자주 하는 실수들                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  실수 1: Workflow에서 @Autowired 사용                                        │
│  ───────────────────────────────────                                         │
│  ❌ @Autowired private SomeService service;  // 작동 안 함!                  │
│  ✅ Activity를 통해 서비스 호출                                              │
│                                                                              │
│  실수 2: Workflow에서 Math.random() 사용                                     │
│  ───────────────────────────────────────                                     │
│  ❌ if (Math.random() > 0.5) { ... }                                         │
│  ✅ if (Workflow.newRandom().nextDouble() > 0.5) { ... }                     │
│                                                                              │
│  실수 3: Workflow에서 Thread.sleep() 사용                                    │
│  ────────────────────────────────────────                                    │
│  ❌ Thread.sleep(5000);                                                      │
│  ✅ Workflow.sleep(Duration.ofSeconds(5));                                   │
│                                                                              │
│  실수 4: Activity를 타입으로 등록                                             │
│  ─────────────────────────────────                                           │
│  ❌ worker.registerActivitiesImplementations(OrderActivities.class);         │
│  ✅ worker.registerActivitiesImplementations(orderActivitiesBean);           │
│                                                                              │
│  실수 5: Workflow 수정 시 Versioning 누락                                     │
│  ────────────────────────────────────────                                    │
│  ❌ 기존 Workflow 실행 중에 코드만 변경                                       │
│  ✅ Workflow.getVersion()으로 분기 처리                                      │
│                                                                              │
│  실수 6: Query에서 상태 변경                                                  │
│  ──────────────────────────────                                              │
│  ❌ @QueryMethod에서 this.count++                                            │
│  ✅ Query는 읽기만!                                                          │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 트러블슈팅 가이드

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        트러블슈팅 가이드                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  증상: "Workflow가 시작되지 않음"                                             │
│  ─────────────────────────────────                                           │
│  원인 1: Worker가 시작되지 않음                                               │
│  → factory.start() 호출 확인                                                 │
│  → Temporal Server 연결 확인 (로그에 에러 없는지)                             │
│                                                                              │
│  원인 2: Task Queue 불일치                                                    │
│  → Workflow 시작 시 지정한 Queue와 Worker가 리스닝하는 Queue가 같은지 확인     │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  증상: "Non-Deterministic Error"                                             │
│  ─────────────────────────────────                                           │
│  원인: Workflow 코드가 Replay 시 다른 결과 생성                               │
│  → Math.random(), LocalDateTime.now(), UUID.randomUUID() 사용 확인           │
│  → Workflow.newRandom(), Workflow.currentTimeMillis() 등으로 교체            │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  증상: "Activity가 실행되지 않음"                                             │
│  ───────────────────────────────────                                         │
│  원인 1: Activity 등록 안 됨                                                  │
│  → worker.registerActivitiesImplementations() 확인                           │
│                                                                              │
│  원인 2: Activity가 다른 Task Queue                                           │
│  → ActivityOptions에서 setTaskQueue() 확인                                   │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  증상: "연결 타임아웃"                                                        │
│  ────────────────────                                                        │
│  원인: Temporal Server에 연결 불가                                            │
│  → Docker 컨테이너 실행 중인지 확인                                           │
│  → 포트 번호 확인 (기본: 7233)                                               │
│  → 방화벽 설정 확인                                                          │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 요약

| 질문 | 답변 |
|------|------|
| Workflow를 Spring이 관리하나? | ❌ Temporal이 생성/관리, Spring DI 불가 |
| Activity를 Spring이 관리하나? | ✅ Spring Bean으로 관리, DI 가능 |
| Workflow 코드가 DB에 저장되나? | ❌ Event History만 저장, 코드는 Worker에 있음 |
| Workflow 수정하면? | Worker 재배포 + Versioning 필요 |
| 재시도 다 실패하면? | ActivityFailure → Saga 보상 또는 Workflow 실패 |
| Signal은 뭔가? | 외부 → Workflow 상태 변경 (비동기) |
| Query는 뭔가? | 외부 → Workflow 상태 조회 (동기) |
| Workflow ID와 Run ID 차이? | Workflow ID는 비즈니스 ID, Run ID는 실행 ID |
| Child Workflow는 언제? | 복잡한 로직 분리, 팀 간 협업 시 |
| 성능 제한? | Event 50,000개, 실행 시간 무제한 |

---

## 다음 단계

- [01-temporal-concepts.md](./01-temporal-concepts.md) - Temporal 핵심 개념 상세
- [02-temporal-spring.md](./02-temporal-spring.md) - Spring Boot 연동 가이드
- [00-temporal-deep-dive.md](./00-temporal-deep-dive.md) - Temporal 심층 이해
- [06-temporal-activity-design-guide.md](./06-temporal-activity-design-guide.md) - **Activity 설계 가이드** (멱등성, 동시성, Saga 격리 해결법)
