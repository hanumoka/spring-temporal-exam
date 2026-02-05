# Temporal 심화 개념

> **전제**: `00-temporal-deep-dive.md` 학습 완료
> **작성일**: 2026-02-05

---

## 목차

1. [Workflow 실행 상태](#1-workflow-실행-상태)
2. [Event Types 완전 정리](#2-event-types-완전-정리)
3. [Signal, Query, Update](#3-signal-query-update)
4. [Timer와 Durable Sleep](#4-timer와-durable-sleep)
5. [Child Workflow](#5-child-workflow)
6. [Continue-As-New](#6-continue-as-new)
7. [Activity Heartbeat 심화](#7-activity-heartbeat-심화)

---

## 1. Workflow 실행 상태

### 1.1 Workflow Execution Lifecycle

> **출처**: [Temporal Workflow Execution](https://docs.temporal.io/workflow-execution)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Workflow Execution 상태 전이도                        │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│                         ┌─────────────┐                                 │
│                         │   START     │                                 │
│                         └──────┬──────┘                                 │
│                                │                                        │
│                                ▼                                        │
│                         ┌─────────────┐                                 │
│                    ┌────│   RUNNING   │────┐                            │
│                    │    └──────┬──────┘    │                            │
│                    │           │           │                            │
│         Signal     │           │           │    Cancel                  │
│         Query      │           │           │    Request                 │
│         Update     │           │           │                            │
│                    │           │           │                            │
│      ┌─────────────┴───────────┼───────────┴─────────────┐             │
│      │             │           │           │             │             │
│      ▼             ▼           ▼           ▼             ▼             │
│  ┌────────┐  ┌──────────┐ ┌────────┐ ┌──────────┐ ┌───────────┐       │
│  │CONTINUED│  │COMPLETED │ │ FAILED │ │CANCELED  │ │TIMED_OUT  │       │
│  │ AS NEW  │  │          │ │        │ │          │ │           │       │
│  └────┬───┘  └──────────┘ └────────┘ └──────────┘ └───────────┘       │
│       │                                                                 │
│       │ 새 Workflow                     ┌───────────┐                   │
│       │ 시작                            │TERMINATED │ (강제 종료)       │
│       └───────────────────────────────→ └───────────┘                   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 1.2 각 상태 상세 설명

| 상태 | 설명 | 발생 조건 | 복구 가능 |
|------|------|----------|----------|
| **Running** | 실행 중 | Workflow 시작됨 | - |
| **Completed** | 정상 완료 | return 문 실행 | N/A |
| **Failed** | 실패 | 예외 발생 + 재시도 소진 | 재시작 가능 |
| **Canceled** | 취소됨 | Cancel 요청 + 처리 완료 | 재시작 가능 |
| **Terminated** | 강제 종료 | terminate API 호출 | 재시작 가능 |
| **Timed Out** | 타임아웃 | Execution Timeout 초과 | 재시작 가능 |
| **Continued-As-New** | 새 실행으로 전환 | ContinueAsNew 호출 | 자동 연속 |

### 1.3 Closed 상태 특징

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Closed 상태들                                   │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Completed, Failed, Canceled, Terminated, Timed Out, Continued-As-New  │
│                                                                         │
│  공통점:                                                                │
│  • 새로운 Command 생성 불가                                             │
│  • Event History 수정 불가 (불변)                                       │
│  • 리소스 소비 없음                                                     │
│  • 조회는 가능 (Event History, 결과)                                   │
│                                                                         │
│  차이점:                                                                │
│  • Completed: 성공 결과 포함                                           │
│  • Failed: 에러 정보 포함                                              │
│  • Canceled: 취소 세부 정보 포함                                       │
│  • Terminated: 종료 사유 포함                                          │
│  • Timed Out: 어떤 타임아웃인지 포함                                   │
│  • Continued-As-New: 새 Workflow Run ID 포함                           │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 1.4 Java 코드에서 상태 확인

```java
// Workflow 상태 조회
WorkflowStub workflowStub = workflowClient.newUntypedWorkflowStub("order-abc123");
WorkflowExecution execution = workflowStub.getExecution();

// 상태 확인
DescribeWorkflowExecutionResponse description =
    workflowClient.getWorkflowServiceStubs()
        .blockingStub()
        .describeWorkflowExecution(
            DescribeWorkflowExecutionRequest.newBuilder()
                .setNamespace("default")
                .setExecution(execution)
                .build()
        );

WorkflowExecutionStatus status = description.getWorkflowExecutionInfo().getStatus();

switch (status) {
    case WORKFLOW_EXECUTION_STATUS_RUNNING:
        System.out.println("실행 중");
        break;
    case WORKFLOW_EXECUTION_STATUS_COMPLETED:
        System.out.println("완료됨");
        break;
    case WORKFLOW_EXECUTION_STATUS_FAILED:
        System.out.println("실패함");
        break;
    case WORKFLOW_EXECUTION_STATUS_CANCELED:
        System.out.println("취소됨");
        break;
    case WORKFLOW_EXECUTION_STATUS_TERMINATED:
        System.out.println("종료됨");
        break;
    case WORKFLOW_EXECUTION_STATUS_TIMED_OUT:
        System.out.println("타임아웃");
        break;
    case WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW:
        System.out.println("새 실행으로 전환됨");
        break;
}
```

---

## 2. Event Types 완전 정리

> **출처**: [Temporal Events Reference](https://docs.temporal.io/references/events)

### 2.1 Event 분류 체계

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Event 분류 체계                                 │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  1. Workflow Execution Events (워크플로우 실행)                         │
│     └── Started, Completed, Failed, TimedOut, Canceled, Terminated...  │
│                                                                         │
│  2. Workflow Task Events (워크플로우 태스크)                            │
│     └── Scheduled, Started, Completed, TimedOut, Failed                │
│                                                                         │
│  3. Activity Task Events (액티비티 태스크)                              │
│     └── Scheduled, Started, Completed, Failed, TimedOut, Canceled      │
│                                                                         │
│  4. Timer Events (타이머)                                               │
│     └── Started, Fired, Canceled                                       │
│                                                                         │
│  5. Child Workflow Events (자식 워크플로우)                             │
│     └── Initiated, Started, Completed, Failed, Canceled...             │
│                                                                         │
│  6. Signal Events (시그널)                                              │
│     └── Received, ExternalInitiated, ExternalFailed                    │
│                                                                         │
│  7. Marker Events (마커)                                                │
│     └── Recorded (Side Effect, Version 등)                             │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Workflow Execution Events

| Event | 설명 | 포함 정보 |
|-------|------|----------|
| **WorkflowExecutionStarted** | 항상 첫 번째 이벤트 | 입력값, 타임아웃 설정, 부모 정보 |
| **WorkflowExecutionCompleted** | 정상 완료 | 반환값 |
| **WorkflowExecutionFailed** | 실패 | 에러 타입, 메시지, 스택트레이스 |
| **WorkflowExecutionTimedOut** | 타임아웃 | 타임아웃 종류 |
| **WorkflowExecutionCancelRequested** | 취소 요청됨 | 요청자 정보 |
| **WorkflowExecutionCanceled** | 취소 완료 | 취소 세부 정보 |
| **WorkflowExecutionTerminated** | 강제 종료 | 종료 사유, 요청자 |
| **WorkflowExecutionContinuedAsNew** | 새 실행 전환 | 새 Run ID, 입력값 |
| **WorkflowExecutionSignaled** | 시그널 수신 | 시그널 이름, 페이로드 |

### 2.3 Workflow Task Events

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Workflow Task 이벤트 흐름                            │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Temporal Server                         Worker                         │
│       │                                    │                            │
│       │  WorkflowTaskScheduled            │                            │
│       │──────────────────────────────────→│                            │
│       │  "워크플로우 태스크 준비됨"        │                            │
│       │                                    │                            │
│       │  WorkflowTaskStarted              │                            │
│       │←──────────────────────────────────│                            │
│       │  "워커가 태스크 수신함"            │                            │
│       │                                    │                            │
│       │         [Workflow 코드 실행]       │                            │
│       │                                    │                            │
│       │  WorkflowTaskCompleted            │                            │
│       │←──────────────────────────────────│                            │
│       │  "Commands: [ScheduleActivity,   │                            │
│       │             StartTimer, ...]"     │                            │
│       │                                    │                            │
│                                                                         │
│  만약 실패하면?                                                         │
│  • WorkflowTaskFailed: Non-Determinism 에러 등                         │
│  • WorkflowTaskTimedOut: Worker가 응답 못함                            │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2.4 Activity Task Events

| Event | 설명 | 중요 정보 |
|-------|------|----------|
| **ActivityTaskScheduled** | Activity 예약됨 | Activity Type, 입력값, 타임아웃 |
| **ActivityTaskStarted** | Activity 시작 | Worker Identity, 시도 횟수 |
| **ActivityTaskCompleted** | 성공 완료 | 결과값 |
| **ActivityTaskFailed** | 실패 | 에러 정보, 시도 횟수 |
| **ActivityTaskTimedOut** | 타임아웃 | 타임아웃 종류, 마지막 Heartbeat |
| **ActivityTaskCancelRequested** | 취소 요청 | 요청 시간 |
| **ActivityTaskCanceled** | 취소 완료 | 취소 세부 정보 |

### 2.5 Event History 예시 (주문 성공)

```
┌─────┬────────────────────────────────┬─────────────────────────────────┐
│ ID  │ Event Type                     │ Details                         │
├─────┼────────────────────────────────┼─────────────────────────────────┤
│ 1   │ WorkflowExecutionStarted       │ input: {customerId:1, ...}      │
│ 2   │ WorkflowTaskScheduled          │ taskQueue: order-task-queue     │
│ 3   │ WorkflowTaskStarted            │ worker: worker-host-1           │
│ 4   │ WorkflowTaskCompleted          │ commands: [ScheduleActivity]    │
│ 5   │ ActivityTaskScheduled          │ activityType: createOrder       │
│ 6   │ ActivityTaskStarted            │ attempt: 1                      │
│ 7   │ ActivityTaskCompleted          │ result: {orderId: 123}          │
│ 8   │ WorkflowTaskScheduled          │                                 │
│ 9   │ WorkflowTaskStarted            │                                 │
│ 10  │ WorkflowTaskCompleted          │ commands: [ScheduleActivity]    │
│ 11  │ ActivityTaskScheduled          │ activityType: reserveStock      │
│ 12  │ ActivityTaskStarted            │ attempt: 1                      │
│ 13  │ ActivityTaskCompleted          │ result: success                 │
│ ... │ ...                            │ ...                             │
│ N   │ WorkflowExecutionCompleted     │ result: {success:true, ...}     │
└─────┴────────────────────────────────┴─────────────────────────────────┘
```

---

## 3. Signal, Query, Update

> **출처**: [Workflow Message Passing](https://docs.temporal.io/encyclopedia/workflow-message-passing)

### 3.1 세 가지 메시지 타입 비교

```
┌─────────────────────────────────────────────────────────────────────────┐
│                Signal vs Query vs Update 비교                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│            Signal              Query               Update               │
│            ──────              ─────               ──────               │
│  방향      Write (→)           Read (←)            Read/Write (↔)       │
│  동기화    Fire & Forget       동기 응답            동기 응답            │
│  상태변경  O (가능)            X (금지)             O (가능)             │
│  History   O (기록됨)          X (기록안됨)         O (기록됨)           │
│  응답      없음                있음                 있음                 │
│  실패처리  재시도 가능          실패 시 재시도       실패 시 재시도       │
│                                                                         │
│  사용 예시:                                                              │
│  • Signal: "결제 완료됨" 알림, "주문 취소" 요청                         │
│  • Query: "현재 진행 단계는?", "주문 상태 조회"                         │
│  • Update: "배송지 변경" (검증 + 변경 + 결과 반환)                      │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Signal 상세

```java
// Signal 정의 (Workflow 인터페이스)
@WorkflowInterface
public interface OrderWorkflow {
    @WorkflowMethod
    OrderResult processOrder(OrderRequest request);

    // Signal 정의 - 반환값 없음!
    @SignalMethod
    void cancelOrder(String reason);

    @SignalMethod
    void updateShippingAddress(String newAddress);
}

// Signal 구현 (Workflow 구현체)
public class OrderWorkflowImpl implements OrderWorkflow {
    private boolean cancelRequested = false;
    private String shippingAddress;

    @Override
    public void cancelOrder(String reason) {
        this.cancelRequested = true;
        // Signal은 상태만 변경, 로직은 메인 Workflow에서 처리
    }

    @Override
    public void updateShippingAddress(String newAddress) {
        this.shippingAddress = newAddress;
    }

    @Override
    public OrderResult processOrder(OrderRequest request) {
        // ... 주문 처리 중 ...

        // Signal 체크
        if (cancelRequested) {
            saga.compensate();
            return OrderResult.canceled("사용자 요청으로 취소");
        }

        // ... 계속 진행 ...
    }
}

// Signal 전송 (클라이언트)
OrderWorkflow workflow = workflowClient.newWorkflowStub(
        OrderWorkflow.class,
        "order-abc123"
);
workflow.cancelOrder("고객 변심");  // Fire & Forget
```

### 3.3 Query 상세

```java
// Query 정의 (Workflow 인터페이스)
@WorkflowInterface
public interface OrderWorkflow {
    @WorkflowMethod
    OrderResult processOrder(OrderRequest request);

    // Query 정의 - 반환값 있음!
    @QueryMethod
    String getCurrentStep();

    @QueryMethod
    OrderStatus getStatus();
}

// Query 구현 (Workflow 구현체)
public class OrderWorkflowImpl implements OrderWorkflow {
    private String currentStep = "INITIALIZED";
    private OrderStatus status = OrderStatus.PENDING;

    @Override
    public String getCurrentStep() {
        return currentStep;  // 읽기만! 상태 변경 금지!
    }

    @Override
    public OrderStatus getStatus() {
        return status;
    }

    @Override
    public OrderResult processOrder(OrderRequest request) {
        currentStep = "CREATING_ORDER";
        Long orderId = activities.createOrder(...);

        currentStep = "RESERVING_STOCK";
        activities.reserveStock(...);

        currentStep = "PROCESSING_PAYMENT";
        // ...
    }
}

// Query 호출 (클라이언트)
OrderWorkflow workflow = workflowClient.newWorkflowStub(
        OrderWorkflow.class,
        "order-abc123"
);
String step = workflow.getCurrentStep();  // 동기 응답: "RESERVING_STOCK"
System.out.println("현재 단계: " + step);
```

### 3.4 Update 상세 (Temporal 1.21+)

```java
// Update 정의 (Workflow 인터페이스)
@WorkflowInterface
public interface OrderWorkflow {
    @WorkflowMethod
    OrderResult processOrder(OrderRequest request);

    // Update 정의 - 반환값 있음 + 상태 변경 가능!
    @UpdateMethod
    UpdateAddressResult updateShippingAddress(String newAddress);

    // Update Validator (선택적)
    @UpdateValidatorMethod(updateName = "updateShippingAddress")
    void validateAddressUpdate(String newAddress);
}

// Update 구현
public class OrderWorkflowImpl implements OrderWorkflow {
    private String shippingAddress;
    private String currentStep;

    @Override
    public void validateAddressUpdate(String newAddress) {
        // Validator: 상태 변경 없이 검증만
        if (currentStep.equals("SHIPPED")) {
            throw new IllegalStateException("이미 배송됨, 변경 불가");
        }
        if (newAddress == null || newAddress.isBlank()) {
            throw new IllegalArgumentException("주소가 비어있음");
        }
    }

    @Override
    public UpdateAddressResult updateShippingAddress(String newAddress) {
        // Handler: 검증 통과 후 실행
        String oldAddress = this.shippingAddress;
        this.shippingAddress = newAddress;

        // Activity 호출도 가능!
        activities.notifyAddressChange(oldAddress, newAddress);

        return new UpdateAddressResult(true, oldAddress, newAddress);
    }
}

// Update 호출 (클라이언트)
OrderWorkflow workflow = workflowClient.newWorkflowStub(
        OrderWorkflow.class,
        "order-abc123"
);
UpdateAddressResult result = workflow.updateShippingAddress("서울시 강남구...");
// 동기 응답: 검증 + 실행 + 결과 반환
```

### 3.5 언제 무엇을 사용할까?

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       사용 시나리오 가이드                               │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Signal 사용:                                                           │
│  ─────────────                                                          │
│  • "주문 취소해줘" (응답 불필요, 비동기)                                │
│  • "결제 완료됐어" (외부 시스템에서 알림)                               │
│  • "새로운 아이템 추가" (배치 처리 중 추가)                             │
│                                                                         │
│  Query 사용:                                                            │
│  ────────────                                                           │
│  • "현재 몇 단계야?" (상태 조회)                                        │
│  • "처리된 아이템 개수?" (통계 조회)                                    │
│  • "에러 발생했어?" (상태 확인)                                         │
│  ※ History에 기록 안 됨 → 빈번한 조회에 적합                           │
│                                                                         │
│  Update 사용:                                                           │
│  ─────────────                                                          │
│  • "배송지 변경" (검증 + 변경 + 확인)                                   │
│  • "할인 코드 적용" (유효성 검증 필요)                                  │
│  • "결제 금액 수정" (변경 전 확인 필요)                                 │
│  ※ 검증 실패 시 클라이언트가 즉시 알 수 있음                           │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Timer와 Durable Sleep

### 4.1 Durable Timer 개념

> 일반 `Thread.sleep()` vs `Workflow.sleep()`

```
┌─────────────────────────────────────────────────────────────────────────┐
│                   Thread.sleep vs Workflow.sleep                        │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Thread.sleep(60000):                                                  │
│  ─────────────────────                                                  │
│  1. Worker 스레드가 1분간 블로킹                                       │
│  2. Worker 크래시 시 → 상태 유실, 처음부터 다시                        │
│  3. 다른 Workflow 처리 못함 (스레드 낭비)                              │
│  4. Workflow 결정적 규칙 위반!                                         │
│                                                                         │
│  Workflow.sleep(Duration.ofMinutes(1)):                                │
│  ─────────────────────────────────────                                  │
│  1. TimerStarted 이벤트 저장                                           │
│  2. Worker 스레드 즉시 반환 (다른 작업 가능)                           │
│  3. Temporal Server가 타이머 관리                                      │
│  4. 1분 후 TimerFired 이벤트 → Workflow 재개                           │
│  5. Worker 크래시 시 → 타이머 상태 유지, 자동 복구                     │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Timer Event 흐름

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      Timer 이벤트 흐름                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Event History:                                                        │
│                                                                         │
│  │ Event 10: WorkflowTaskCompleted                                     │
│  │           commands: [StartTimer(id=1, duration=60s)]                │
│  │                                                                      │
│  │ Event 11: TimerStarted                                              │
│  │           timerId: 1                                                │
│  │           startToFireTimeout: 60s                                   │
│  │                                                                      │
│  │  ─────── [60초 경과, Worker 없어도 됨] ───────                      │
│  │                                                                      │
│  │ Event 12: TimerFired                                                │
│  │           timerId: 1                                                │
│  │                                                                      │
│  │ Event 13: WorkflowTaskScheduled                                     │
│  │           (Workflow 재개)                                           │
│  │                                                                      │
│                                                                         │
│  핵심: 타이머 동안 Worker 리소스 0 소비!                               │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 4.3 실용 예제: 결제 대기

```java
public class OrderWorkflowImpl implements OrderWorkflow {

    @Override
    public OrderResult processOrder(OrderRequest request) {
        Long orderId = activities.createOrder(request.customerId());

        // 결제 대기 (최대 30분)
        boolean paymentReceived = false;

        for (int i = 0; i < 6; i++) {  // 5분 x 6 = 30분
            // 5분 대기 (Durable!)
            Workflow.sleep(Duration.ofMinutes(5));

            // Signal로 결제 완료 알림 받았는지 확인
            if (this.paymentCompleted) {
                paymentReceived = true;
                break;
            }

            // 결제 상태 확인 (Activity)
            PaymentStatus status = activities.checkPaymentStatus(orderId);
            if (status == PaymentStatus.COMPLETED) {
                paymentReceived = true;
                break;
            }
        }

        if (!paymentReceived) {
            saga.compensate();
            return OrderResult.failure("결제 시간 초과");
        }

        // 결제 완료 후 처리 계속...
    }
}
```

### 4.4 awaitCondition vs sleep

```java
// 방법 1: sleep으로 폴링
for (int i = 0; i < 6; i++) {
    Workflow.sleep(Duration.ofMinutes(5));
    if (paymentCompleted) break;
}

// 방법 2: Workflow.await로 조건 대기 (권장)
boolean completed = Workflow.await(
    Duration.ofMinutes(30),   // 최대 대기 시간
    () -> this.paymentCompleted  // 조건
);

if (!completed) {
    // 30분 내 paymentCompleted가 true가 되지 않음
    return OrderResult.failure("결제 시간 초과");
}
```

---

## 5. Child Workflow

> **출처**: [Child Workflows](https://docs.temporal.io/child-workflows)

### 5.1 Child Workflow 개념

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      Parent-Child Workflow 관계                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│   Parent Workflow (OrderWorkflow)                                      │
│   ┌───────────────────────────────────────────────────────────────┐    │
│   │                                                                │    │
│   │  1. 주문 생성                                                  │    │
│   │  2. 재고 예약                                                  │    │
│   │  3. ┌─────────────────────────────────────────────┐           │    │
│   │     │ Child Workflow (PaymentWorkflow)           │           │    │
│   │     │                                            │           │    │
│   │     │  3.1 결제 생성                             │           │    │
│   │     │  3.2 PG 승인                               │           │    │
│   │     │  3.3 결제 확정                             │           │    │
│   │     │                                            │           │    │
│   │     └─────────────────────────────────────────────┘           │    │
│   │  4. 주문 확정                                                  │    │
│   │  5. 알림 발송                                                  │    │
│   │                                                                │    │
│   └───────────────────────────────────────────────────────────────┘    │
│                                                                         │
│   특징:                                                                 │
│   • Child는 독립적인 Event History 가짐                                │
│   • Parent 취소 시 Child도 취소 가능 (설정)                            │
│   • Parent Event History 크기 줄임                                     │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 5.2 Child Workflow 사용 시점

| 사용해야 할 때 | 사용하지 말아야 할 때 |
|---------------|---------------------|
| 복잡한 서브 프로세스 분리 | 단순 코드 정리 목적 |
| 독립적인 재시도 정책 필요 | 항상 함께 성공/실패해야 할 때 |
| Event History 크기 관리 | 작은 Event History |
| 병렬 실행이 필요할 때 | 순차 실행만 필요할 때 |
| 다른 Task Queue 사용 | 같은 Task Queue 사용 |

### 5.3 Java 구현 예제

```java
// Child Workflow 인터페이스
@WorkflowInterface
public interface PaymentWorkflow {
    @WorkflowMethod
    PaymentResult processPayment(PaymentRequest request);
}

// Parent Workflow에서 Child 호출
public class OrderWorkflowImpl implements OrderWorkflow {

    @Override
    public OrderResult processOrder(OrderRequest request) {
        Long orderId = activities.createOrder(request.customerId());
        activities.reserveStock(request.productId(), request.quantity());

        // Child Workflow 옵션
        ChildWorkflowOptions childOptions = ChildWorkflowOptions.newBuilder()
                .setWorkflowId("payment-" + orderId)
                .setTaskQueue("payment-task-queue")  // 다른 Task Queue 가능
                .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_TERMINATE)
                .build();

        // Child Workflow Stub 생성
        PaymentWorkflow paymentWorkflow = Workflow.newChildWorkflowStub(
                PaymentWorkflow.class,
                childOptions
        );

        // Child Workflow 실행 (동기)
        PaymentResult paymentResult = paymentWorkflow.processPayment(
                new PaymentRequest(orderId, request.amount())
        );

        if (!paymentResult.isSuccess()) {
            saga.compensate();
            return OrderResult.failure(paymentResult.getError());
        }

        activities.confirmOrder(orderId);
        return OrderResult.success(orderId, paymentResult.getPaymentId());
    }
}
```

### 5.4 Parent Close Policy

| Policy | 설명 |
|--------|------|
| **ABANDON** | Parent 종료해도 Child 계속 실행 |
| **TERMINATE** | Parent 종료 시 Child도 종료 |
| **REQUEST_CANCEL** | Parent 종료 시 Child에 취소 요청 |

---

## 6. Continue-As-New

> **출처**: [Continue-As-New](https://docs.temporal.io/workflow-execution/continue-as-new)

### 6.1 Continue-As-New 필요성

```
┌─────────────────────────────────────────────────────────────────────────┐
│                  Event History 크기 문제                                │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  문제 상황: 무한 루프 Workflow                                         │
│                                                                         │
│  while (true) {                                                        │
│      items = activities.fetchNewItems();                               │
│      for (item : items) {                                              │
│          activities.processItem(item);  // 매번 Event 추가            │
│      }                                                                  │
│      Workflow.sleep(Duration.ofMinutes(5));  // Timer Event 추가      │
│  }                                                                      │
│                                                                         │
│  Event History 증가:                                                   │
│  • 1시간: ~1,000 이벤트                                                │
│  • 1일: ~24,000 이벤트                                                 │
│  • 1주: ~168,000 이벤트                                                │
│  • 1달: ~720,000 이벤트  ← 메모리 문제!                                │
│                                                                         │
│  Temporal 제한:                                                        │
│  • 기본: 50,000 이벤트 경고                                            │
│  • 하드 제한: 설정에 따라 다름 (보통 200K~1M)                          │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 6.2 Continue-As-New 동작

```
┌─────────────────────────────────────────────────────────────────────────┐
│                 Continue-As-New 동작 방식                               │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Run 1 (Event: 1~10,000)                                               │
│  ┌─────────────────────────────────────────────────────────────┐       │
│  │  Event 1: WorkflowExecutionStarted                          │       │
│  │  Event 2~9,999: Activity, Timer 이벤트들                    │       │
│  │  Event 10,000: WorkflowExecutionContinuedAsNew              │       │
│  │               → newRunId: "run-2-xyz"                       │       │
│  └─────────────────────────────────────────────────────────────┘       │
│                              │                                          │
│                              ▼ (상태 전달)                              │
│  Run 2 (Event: 1~10,000)   processedCount: 50000                       │
│  ┌─────────────────────────────────────────────────────────────┐       │
│  │  Event 1: WorkflowExecutionStarted (from ContinueAsNew)     │       │
│  │  Event 2~9,999: Activity, Timer 이벤트들                    │       │
│  │  Event 10,000: WorkflowExecutionContinuedAsNew              │       │
│  └─────────────────────────────────────────────────────────────┘       │
│                              │                                          │
│                              ▼                                          │
│  Run 3 ...                                                              │
│                                                                         │
│  핵심: 같은 Workflow ID, 다른 Run ID, 깨끗한 Event History             │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 6.3 Java 구현 예제

```java
public class BatchProcessorWorkflowImpl implements BatchProcessorWorkflow {

    private static final int BATCH_SIZE = 1000;

    @Override
    public void processBatch(BatchState state) {
        int processedInThisRun = 0;

        while (true) {
            List<Item> items = activities.fetchItems(state.getLastProcessedId(), 100);

            if (items.isEmpty()) {
                Workflow.sleep(Duration.ofMinutes(5));
                continue;
            }

            for (Item item : items) {
                activities.processItem(item);
                state.setLastProcessedId(item.getId());
                state.incrementProcessedCount();
                processedInThisRun++;
            }

            // Event History 크기 관리: 1000개 처리마다 Continue-As-New
            if (processedInThisRun >= BATCH_SIZE) {
                Workflow.continueAsNew(state);  // 상태 전달하며 새 Run 시작
                return;  // 현재 Run 종료 (실제로 여기 도달 안 함)
            }
        }
    }
}

// 상태 클래스
@Data
public class BatchState {
    private Long lastProcessedId = 0L;
    private int processedCount = 0;
}
```

### 6.4 Continue-As-New vs Child Workflow

| 특성 | Continue-As-New | Child Workflow |
|------|-----------------|----------------|
| 목적 | Event History 크기 관리 | 논리적 분리 |
| Workflow ID | 동일 | 다름 |
| 실행 연속성 | 연속적 (같은 "작업") | 독립적 (다른 "작업") |
| 상태 전달 | 명시적 파라미터 | 반환값 또는 Signal |
| 사용 예 | 무한 루프, 배치 처리 | 서브 프로세스 분리 |

---

## 7. Activity Heartbeat 심화

> **출처**: [Detecting Activity Failures](https://docs.temporal.io/encyclopedia/detecting-activity-failures)

### 7.1 Heartbeat가 필요한 이유

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Heartbeat 없이 장기 Activity                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  설정: StartToCloseTimeout = 5시간 (파일 처리 최대 시간)               │
│                                                                         │
│  시나리오:                                                              │
│  T+0분: Activity 시작 (10GB 파일 처리)                                 │
│  T+30분: Worker 크래시! 💥                                              │
│  T+5시간: Temporal이 타임아웃 감지                                     │
│  T+5시간: Activity 재시도 시작                                          │
│                                                                         │
│  문제: 4시간 30분 낭비!                                                │
│                                                                         │
│  ─────────────────────────────────────────────────────────────────────  │
│                                                                         │
│  Heartbeat 사용 시:                                                    │
│                                                                         │
│  설정: StartToCloseTimeout = 5시간                                     │
│        HeartbeatTimeout = 1분                                          │
│                                                                         │
│  시나리오:                                                              │
│  T+0분: Activity 시작                                                   │
│  T+30초: Heartbeat 전송 ✓                                              │
│  T+30분: Worker 크래시! 💥 (마지막 Heartbeat T+29분 30초)              │
│  T+30분 30초: Heartbeat 없음 감지                                      │
│  T+31분: Temporal이 타임아웃 감지 (HeartbeatTimeout 초과)              │
│  T+31분: Activity 재시도 시작                                          │
│                                                                         │
│  결과: 1분 만에 문제 감지!                                             │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 7.2 Heartbeat 구현

```java
// Activity 구현
@Component
public class FileProcessorActivitiesImpl implements FileProcessorActivities {

    @Override
    public ProcessResult processLargeFile(String filePath) {
        ActivityExecutionContext context = Activity.getExecutionContext();

        File file = new File(filePath);
        long totalSize = file.length();
        long processedSize = 0;

        // 이전 Heartbeat에서 저장한 진행 상태 복구
        Optional<Long> lastProgress = context.getHeartbeatDetails(Long.class);
        if (lastProgress.isPresent()) {
            processedSize = lastProgress.get();
            // 중단된 위치부터 재개
            skipToPosition(file, processedSize);
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                processLine(line);
                processedSize += line.length();

                // 매 1MB마다 Heartbeat
                if (processedSize % (1024 * 1024) == 0) {
                    // 진행 상태를 Heartbeat에 저장
                    context.heartbeat(processedSize);

                    // 취소 확인
                    if (context.isCancellationRequested()) {
                        return ProcessResult.canceled(processedSize, totalSize);
                    }
                }
            }
        }

        return ProcessResult.success(processedSize);
    }
}
```

### 7.3 Heartbeat Details 활용

```
┌─────────────────────────────────────────────────────────────────────────┐
│                   Heartbeat Details로 복구                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  1차 시도 (Worker A):                                                  │
│  ┌─────────────────────────────────────────────────────────────┐       │
│  │  처리 시작: 0 bytes                                          │       │
│  │  Heartbeat(1MB)  ← 저장                                      │       │
│  │  Heartbeat(2MB)  ← 저장                                      │       │
│  │  Heartbeat(3MB)  ← 저장                                      │       │
│  │  💥 Worker 크래시!                                           │       │
│  └─────────────────────────────────────────────────────────────┘       │
│                                                                         │
│  2차 시도 (Worker B):                                                  │
│  ┌─────────────────────────────────────────────────────────────┐       │
│  │  getHeartbeatDetails() → 3MB (마지막 저장값)                 │       │
│  │  3MB 위치부터 재개!                                          │       │
│  │  Heartbeat(4MB)                                              │       │
│  │  Heartbeat(5MB)                                              │       │
│  │  완료!                                                       │       │
│  └─────────────────────────────────────────────────────────────┘       │
│                                                                         │
│  결과: 0~3MB 재처리 없이 3MB부터 계속                                  │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 7.4 Heartbeat 설정 권장사항

```java
ActivityOptions options = ActivityOptions.newBuilder()
        // 전체 실행 시간 (충분히 크게)
        .setStartToCloseTimeout(Duration.ofHours(5))

        // Heartbeat 타임아웃 (장애 감지 속도)
        .setHeartbeatTimeout(Duration.ofMinutes(1))

        // 재시도 정책
        .setRetryOptions(RetryOptions.newBuilder()
                .setMaximumAttempts(3)
                .build())
        .build();
```

| 상황 | HeartbeatTimeout 권장 |
|------|---------------------|
| 빠른 장애 감지 필요 | 30초 ~ 1분 |
| 네트워크 불안정 | 2분 ~ 5분 |
| 배치 작업 (느린 진행) | 5분 ~ 10분 |

---

## 참고 자료

### 공식 문서
- [Workflow Execution](https://docs.temporal.io/workflow-execution)
- [Events Reference](https://docs.temporal.io/references/events)
- [Message Passing](https://docs.temporal.io/encyclopedia/workflow-message-passing)
- [Child Workflows](https://docs.temporal.io/child-workflows)
- [Continue-As-New](https://docs.temporal.io/workflow-execution/continue-as-new)
- [Detecting Activity Failures](https://docs.temporal.io/encyclopedia/detecting-activity-failures)

### 블로그
- [Activity Timeouts](https://temporal.io/blog/activity-timeouts)
- [Very Long-Running Workflows](https://temporal.io/blog/very-long-running-workflows)

---

*다음 문서: `02-temporal-production.md` (Versioning, Schedule, 프로덕션 배포)*
