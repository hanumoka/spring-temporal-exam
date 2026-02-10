# Durable Execution과 Event History

> **전제**: `01-temporal-concepts.md`, `02-temporal-spring.md` 학습 완료
> **목표**: Durable Execution의 원리, Event History 구조, Replay 메커니즘, 결정적 코드 규칙 완전 이해
> **참고**: [Temporal 공식 문서 - Durable Execution](https://docs.temporal.io/encyclopedia/durable-execution)

---

## 목차

1. [Durable Execution 개념](#1-durable-execution-개념)
2. [Event History 구조와 Replay 동작](#2-event-history-구조와-replay-동작)
3. [Event Types 분류](#3-event-types-분류)
4. [결정적(Deterministic) 코드 규칙](#4-결정적deterministic-코드-규칙)
5. [Workflow 실행 상태 Lifecycle](#5-workflow-실행-상태-lifecycle)
6. [Non-Determinism Error 예시](#6-non-determinism-error-예시)

---

## 1. Durable Execution 개념

### 1.1 한 줄 정의

> **Durable Execution**: 프로세스가 크래시해도 중단된 지점부터 이어서 실행되는 것

Temporal은 Workflow의 모든 실행 단계를 **이벤트 소싱(Event Sourcing)** 방식으로 기록한다.
이벤트 소싱에서는 "현재 상태"를 저장하는 대신, **"상태 변경 이력"을 전부 기록**하고
필요할 때 이력을 재생(Replay)하여 상태를 복원한다.

### 1.2 이벤트 소싱 비유

```
┌──────────────────────────────────────────────────────────────────────┐
│                    일반 상태 저장 vs 이벤트 소싱                       │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [일반 상태 저장 - 스냅샷 방식]                                      │
│                                                                      │
│   잔액: 10,000원                                                    │
│   → 5,000원 입금 → 잔액: 15,000원 (덮어쓰기)                       │
│   → 3,000원 출금 → 잔액: 12,000원 (덮어쓰기)                       │
│                                                                      │
│   문제: "왜 12,000원인지?" 과정을 알 수 없다                        │
│                                                                      │
│  ─────────────────────────────────────────────────────────────────── │
│                                                                      │
│  [이벤트 소싱 - Temporal 방식]                                       │
│                                                                      │
│   Event 1: 계좌 개설 (잔액 10,000원)                                │
│   Event 2: 입금 5,000원                                              │
│   Event 3: 출금 3,000원                                              │
│                                                                      │
│   현재 잔액? → Event 1~3을 재생하면 12,000원                        │
│   장점: 모든 과정이 기록되어 있고, 어느 시점이든 복원 가능           │
│                                                                      │
│   Temporal도 동일: Workflow의 모든 단계를 Event로 기록               │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

### 1.3 동작 원리: 실행 가상화 (Execution Virtualization)

```
  [일반 코드]                       [Temporal 코드]

  프로세스 A:                       프로세스 A:
  Step 1: 주문 생성 → 성공          Step 1: 주문 생성 → 성공 → 기록
  Step 2: 재고 예약 → 성공          Step 2: 재고 예약 → 성공 → 기록
  Step 3: 결제 처리 → 크래시!       Step 3: 결제 처리 → 크래시!

  → 모든 것이 사라짐               프로세스 B에서 자동 재개:
  → 처음부터 다시                   History 확인: "Step 1, 2 완료됨"
                                    Step 3: 결제 처리 → 이어서 실행!
```

### 1.4 Durable Execution의 4가지 특성

| 특성 | 설명 | 예시 |
|------|------|------|
| **실행 가상화** | 여러 프로세스에서 순차 실행 가능 | Worker A 크래시 → Worker B가 투명하게 재개 |
| **시간 제약 없음** | 초단위~수년 단위 실행 지원 | `Workflow.sleep(Duration.ofDays(30))` 가능 |
| **자동 상태 보존** | 모든 변수가 자동 저장됨 | 별도 DB 없이 상태 보호 |
| **하드웨어 독립성** | 특정 서버에 의존하지 않음 | 어느 Worker에서든 실행 가능 |

---

## 2. Event History 구조와 Replay 동작

### 2.1 Event History란?

> **"Workflow 실행의 완전한 기록"** -- 추가 전용 로그(Append-only Log)

한 번 기록된 이벤트는 수정되거나 삭제되지 않는다.
Temporal Server가 이 기록을 관리하며, Worker 크래시 시 이 기록을 기반으로 상태를 복원한다.

```
┌─────┬──────────────────────────┬────────────────────────────────────┐
│ ID  │ Event Type               │ 상세 정보                          │
├─────┼──────────────────────────┼────────────────────────────────────┤
│  1  │ WorkflowExecutionStarted │ input: {customerId:1, productId:2} │
│  2  │ WorkflowTaskScheduled    │                                    │
│  3  │ WorkflowTaskStarted      │                                    │
│  4  │ WorkflowTaskCompleted    │ commands: [ScheduleActivity]       │
│  5  │ ActivityTaskScheduled    │ type: "createOrder"                │
│  6  │ ActivityTaskStarted      │ attempt: 1                         │
│  7  │ ActivityTaskCompleted    │ result: {orderId: 123}             │
│  8  │ WorkflowTaskScheduled    │                                    │
│  9  │ WorkflowTaskStarted      │                                    │
│ 10  │ WorkflowTaskCompleted    │ commands: [ScheduleActivity]       │
│ 11  │ ActivityTaskScheduled    │ type: "reserveStock"               │
│ 12  │ ActivityTaskStarted      │ attempt: 1                         │
│ 13  │ ActivityTaskCompleted    │ result: success                    │
│ ... │ ...                      │ ...                                │
│  N  │ WorkflowExecutionCompleted│ result: {success:true, ...}       │
└─────┴──────────────────────────┴────────────────────────────────────┘
```

### 2.2 Replay (재생) 메커니즘

> **"Event History를 재생해서 상태를 복구한다"**

Worker가 크래시하면, 새 Worker가 Event History를 읽어서 Workflow 코드를 처음부터 재실행한다.
단, **이미 완료된 Activity는 실제로 다시 호출하지 않고**, Event History에 저장된 결과를 그대로 반환한다.

```
┌──────────────────────────────────────────────────────────────────────┐
│                        Replay 동작 과정                               │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [최초 실행]                                                        │
│                                                                      │
│   Workflow 시작                                                      │
│        │                                                            │
│        ▼                                                            │
│   activities.createOrder()   → HTTP 호출 → orderId: 123            │
│   Event 기록: ActivityTaskCompleted(result: 123)                    │
│        │                                                            │
│        ▼                                                            │
│   activities.reserveStock()  → HTTP 호출 → success                 │
│   Event 기록: ActivityTaskCompleted(result: success)                │
│        │                                                            │
│        ▼                                                            │
│   activities.processPayment() 실행 중... Worker 크래시!             │
│                                                                      │
│  ─────────────────────────────────────────────────────────────────── │
│                                                                      │
│  [Replay - 새 Worker에서]                                           │
│                                                                      │
│   Event History 읽기:                                                │
│   - createOrder 완료됨 (result: 123)                                │
│   - reserveStock 완료됨 (result: success)                           │
│                                                                      │
│   Workflow 코드 재실행:                                              │
│   activities.createOrder()   → HTTP 호출 안 함! 저장된 결과: 123   │
│   activities.reserveStock()  → HTTP 호출 안 함! 저장된 결과: success│
│   activities.processPayment() → 여기서부터 실제 실행!               │
│                                                                      │
│   핵심: 완료된 Activity는 재호출하지 않음!                          │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

### 2.3 Event History 제한사항

| 제한 | 값 | 발생 시 |
|------|-----|---------|
| 이벤트 개수 경고 | 10,240개 | 경고 발생 |
| 이벤트 개수 제한 | 51,200개 | Workflow 종료 |
| Update 개수 | 2,000개 | Workflow 종료 |
| Signal 개수 | 10,000개 | Workflow 종료 |

**해결책**: Continue-As-New를 사용하여 Event History를 리셋하며 새 Workflow로 전환한다.

---

## 3. Event Types 분류

> **출처**: [Temporal Events Reference](https://docs.temporal.io/references/events)

### 3.1 Event 분류 체계 (7가지 카테고리)

```
┌──────────────────────────────────────────────────────────────────────┐
│                         Event 분류 체계                               │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  1. Workflow Execution Events (워크플로우 실행)                     │
│     └── Started, Completed, Failed, TimedOut, Canceled,             │
│         Terminated, ContinuedAsNew, Signaled                        │
│                                                                      │
│  2. Workflow Task Events (워크플로우 태스크)                        │
│     └── Scheduled, Started, Completed, TimedOut, Failed             │
│                                                                      │
│  3. Activity Task Events (액티비티 태스크)                          │
│     └── Scheduled, Started, Completed, Failed, TimedOut, Canceled   │
│                                                                      │
│  4. Timer Events (타이머)                                           │
│     └── Started, Fired, Canceled                                    │
│                                                                      │
│  5. Child Workflow Events (자식 워크플로우)                         │
│     └── Initiated, Started, Completed, Failed, Canceled...          │
│                                                                      │
│  6. Signal Events (시그널)                                          │
│     └── Received, ExternalInitiated, ExternalFailed                 │
│                                                                      │
│  7. Marker Events (마커)                                            │
│     └── Recorded (Side Effect, Version 등)                          │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

### 3.2 주요 Event Types 상세

#### Workflow Execution Events

| Event | 설명 | 포함 정보 |
|-------|------|----------|
| **WorkflowExecutionStarted** | 항상 첫 번째 이벤트 | 입력값, 타임아웃 설정, 부모 정보 |
| **WorkflowExecutionCompleted** | 정상 완료 | 반환값 |
| **WorkflowExecutionFailed** | 실패 | 에러 타입, 메시지, 스택트레이스 |
| **WorkflowExecutionTimedOut** | 타임아웃 | 타임아웃 종류 |
| **WorkflowExecutionCanceled** | 취소 완료 | 취소 세부 정보 |
| **WorkflowExecutionTerminated** | 강제 종료 | 종료 사유, 요청자 |
| **WorkflowExecutionContinuedAsNew** | 새 실행 전환 | 새 Run ID, 입력값 |
| **WorkflowExecutionSignaled** | 시그널 수신 | 시그널 이름, 페이로드 |

#### Activity Task Events

| Event | 설명 | 중요 정보 |
|-------|------|----------|
| **ActivityTaskScheduled** | Activity 예약됨 | Activity Type, 입력값, 타임아웃 |
| **ActivityTaskStarted** | Activity 시작 | Worker Identity, 시도 횟수 |
| **ActivityTaskCompleted** | 성공 완료 | 결과값 |
| **ActivityTaskFailed** | 실패 | 에러 정보, 시도 횟수 |
| **ActivityTaskTimedOut** | 타임아웃 | 타임아웃 종류, 마지막 Heartbeat |
| **ActivityTaskCanceled** | 취소 완료 | 취소 세부 정보 |

### 3.3 Workflow Task 이벤트 흐름

Workflow Task는 **Temporal Server와 Worker 사이의 통신 단위**다.
Worker가 Workflow 코드를 실행하고, 그 결과로 Command(예: ScheduleActivity)를 Server에 반환한다.

```
┌──────────────────────────────────────────────────────────────────────┐
│                    Workflow Task 이벤트 흐름                          │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Temporal Server                       Worker                       │
│       │                                  │                          │
│       │ WorkflowTaskScheduled            │                          │
│       │────────────────────────────────→ │                          │
│       │ "워크플로우 태스크 준비됨"        │                          │
│       │                                  │                          │
│       │ WorkflowTaskStarted              │                          │
│       │ ←────────────────────────────────│                          │
│       │ "워커가 태스크 수신함"            │                          │
│       │                                  │                          │
│       │       [Workflow 코드 실행]        │                          │
│       │                                  │                          │
│       │ WorkflowTaskCompleted            │                          │
│       │ ←────────────────────────────────│                          │
│       │ "Commands: [ScheduleActivity,    │                          │
│       │            StartTimer, ...]"     │                          │
│       │                                  │                          │
│                                                                      │
│  실패 시:                                                           │
│  - WorkflowTaskFailed: Non-Determinism 에러 등                     │
│  - WorkflowTaskTimedOut: Worker가 응답하지 못함                    │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 4. 결정적(Deterministic) 코드 규칙

### 4.1 왜 결정적이어야 하는가?

Replay가 정상 동작하려면, **같은 입력에 항상 같은 결과**가 나와야 한다.
Workflow 코드가 비결정적이면 Replay 시 Event History와 실행 경로가 달라져서
**Non-Deterministic Error**가 발생하고 Workflow가 멈춘다.

```
같은 코드 + 같은 Event History → 반드시 같은 실행 경로

                이것이 "결정적(Deterministic)"의 의미
```

### 4.2 DO: Workflow에서 해도 되는 것

```java
// 1. Temporal의 결정적 API 사용
long now = Workflow.currentTimeMillis();       // 시간 (Replay 안전)
Workflow.sleep(Duration.ofMinutes(5));          // 대기 (Replay 안전)
Random random = Workflow.newRandom();           // 랜덤 (Replay 안전)
String uuid = Workflow.randomUUID().toString(); // UUID (Replay 안전)

// 2. 조건 대기
Workflow.await(() -> this.approved);                        // 조건까지 대기
Workflow.await(Duration.ofHours(1), () -> this.approved);   // 타임아웃 포함

// 3. Activity 호출 (모든 외부 작업은 Activity로!)
Long orderId = activities.createOrder(request);
activities.reserveStock(productId, quantity);

// 4. 비동기 실행
Promise<String> promise = Async.function(activities::doSomething, arg);
String result = promise.get();  // 결과 대기

// 5. Workflow 내부 변수로 상태 관리
private boolean cancelRequested = false;
private String currentStep = "INIT";
```

### 4.3 DON'T: Workflow에서 하면 안 되는 것

| 금지 항목 | 이유 | 대안 |
|----------|------|------|
| `new Date()`, `LocalDateTime.now()` | Replay마다 다른 값 | `Workflow.currentTimeMillis()` |
| `Math.random()` | Replay마다 다른 값 | `Workflow.newRandom()` |
| `UUID.randomUUID()` | Replay마다 다른 값 | `Workflow.randomUUID()` |
| `Thread.sleep()` | Replay 시 실제로 대기함 | `Workflow.sleep()` |
| HTTP 호출 | Replay마다 다른 결과 가능 | Activity 사용 |
| DB 조회/수정 | Replay마다 다른 결과 가능 | Activity 사용 |
| 파일 읽기/쓰기 | Replay마다 다른 결과 가능 | Activity 사용 |
| 전역 변수 수정 | 예측 불가 | Workflow 내부 변수만 사용 |
| `new Thread()` | Temporal이 관리 못함 | `Async.function()` 사용 |
| 비결정적 라이브러리 호출 | Replay 불일치 | Activity로 감싸기 |

### 4.4 올바른 코드 vs 잘못된 코드

```java
// ====================================
// DON'T - 잘못된 Workflow 코드
// ====================================
public class OrderWorkflowImpl implements OrderWorkflow {

    @Override
    public OrderResult processOrder(OrderRequest request) {
        // X: 현재 시간 직접 사용
        if (LocalDateTime.now().getHour() < 9) {
            return OrderResult.failure("영업시간 아님");
        }

        // X: 랜덤 직접 사용
        if (Math.random() > 0.5) {
            activities.sendPromotion();
        }

        // X: HTTP 직접 호출
        String rate = httpClient.get("/exchange-rate");
    }
}

// ====================================
// DO - 올바른 Workflow 코드
// ====================================
public class OrderWorkflowImpl implements OrderWorkflow {

    @Override
    public OrderResult processOrder(OrderRequest request) {
        // O: Workflow API로 시간 확인
        long now = Workflow.currentTimeMillis();
        if (toHour(now) < 9) {
            return OrderResult.failure("영업시간 아님");
        }

        // O: Activity에서 판단
        boolean shouldSendPromo = activities.checkPromoEligibility();
        if (shouldSendPromo) {
            activities.sendPromotion();
        }

        // O: Activity로 외부 호출
        String rate = activities.getExchangeRate();
    }
}
```

### 4.5 핵심 원칙 한 줄 요약

```
Workflow 코드 = 순수 오케스트레이션 로직 (분기, 반복, 상태 관리)
Activity 코드 = 모든 부수효과 (I/O, DB, HTTP, 파일, 랜덤 등)
```

---

## 5. Workflow 실행 상태 Lifecycle

### 5.1 상태 전이도

```
┌──────────────────────────────────────────────────────────────────────┐
│                    Workflow Execution 상태 전이도                     │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│                         ┌─────────────┐                              │
│                         │    START    │                              │
│                         └──────┬──────┘                              │
│                                │                                    │
│                                ▼                                    │
│                         ┌─────────────┐                              │
│                    ┌────│   RUNNING   │────┐                        │
│                    │    └──────┬──────┘    │                        │
│                    │           │           │                        │
│         Signal     │           │           │    Cancel              │
│         Query      │           │           │    Request             │
│         Update     │           │           │                        │
│                    │           │           │                        │
│      ┌─────────────┴───────────┼───────────┴───────────┐           │
│      │             │           │           │           │           │
│      ▼             ▼           ▼           ▼           ▼           │
│  ┌────────┐  ┌──────────┐ ┌────────┐ ┌────────┐ ┌──────────┐     │
│  │CONTINUED│  │COMPLETED │ │ FAILED │ │CANCELED│ │TIMED_OUT │     │
│  │ AS NEW  │  │          │ │        │ │        │ │          │     │
│  └────┬───┘  └──────────┘ └────────┘ └────────┘ └──────────┘     │
│       │                                                            │
│       │  새 Workflow                    ┌───────────┐              │
│       │  시작                           │TERMINATED │ (강제 종료)  │
│       └───────────────────────────────→ └───────────┘              │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

### 5.2 각 상태 상세 설명

| 상태 | 설명 | 발생 조건 | 복구 가능 |
|------|------|----------|----------|
| **Running** | 실행 중 | Workflow 시작됨 | - |
| **Completed** | 정상 완료 | return 문 실행 | N/A |
| **Failed** | 실패 | 예외 발생 + 재시도 소진 | 재시작 가능 |
| **Canceled** | 취소됨 | Cancel 요청 + 처리 완료 | 재시작 가능 |
| **Terminated** | 강제 종료 | terminate API 호출 | 재시작 가능 |
| **Timed Out** | 타임아웃 | Execution Timeout 초과 | 재시작 가능 |
| **Continued-As-New** | 새 실행으로 전환 | ContinueAsNew 호출 | 자동 연속 |

### 5.3 Closed 상태 공통 특징

모든 Closed 상태(Completed, Failed, Canceled, Terminated, Timed Out, Continued-As-New)의 공통점:

- 새로운 Command 생성 불가
- Event History 수정 불가 (불변)
- 리소스 소비 없음
- 조회는 가능 (Event History, 결과)

각 상태별 고유 정보: Completed는 성공 결과, Failed는 에러 정보, Canceled는 취소 세부 정보,
Terminated는 종료 사유, Timed Out은 타임아웃 종류, Continued-As-New는 새 Run ID를 포함한다.

### 5.4 Java 코드에서 상태 확인

```java
WorkflowStub stub = workflowClient.newUntypedWorkflowStub("order-abc123");

DescribeWorkflowExecutionResponse desc = workflowClient
    .getWorkflowServiceStubs().blockingStub()
    .describeWorkflowExecution(DescribeWorkflowExecutionRequest.newBuilder()
        .setNamespace("default")
        .setExecution(stub.getExecution())
        .build());

WorkflowExecutionStatus status = desc.getWorkflowExecutionInfo().getStatus();
// WORKFLOW_EXECUTION_STATUS_RUNNING / COMPLETED / FAILED /
// CANCELED / TERMINATED / TIMED_OUT / CONTINUED_AS_NEW
```

---

## 6. Non-Determinism Error 예시

### 6.1 발생 원리

Non-Determinism Error는 **Replay 시 코드의 실행 경로가 원래 실행과 달라질 때** 발생한다.
Temporal은 Replay 중 Workflow 코드를 재실행하면서 Event History와 비교하는데,
코드가 History에 없는 Activity를 호출하거나, History에 있는 Activity를 건너뛰면 에러가 난다.

### 6.2 예시 1: 시간 기반 분기

```java
// 최초 실행: 09:00 (오전) → processUrgent 호출 → Event History에 기록
// Replay:    10:30 (오전) → processNormal 호출하려 함 → 불일치!

// X: 잘못된 코드
if (LocalDateTime.now().getHour() < 10) {
    activities.processUrgent();    // 최초 실행: 이쪽
} else {
    activities.processNormal();    // Replay: 이쪽으로 가려고 함!
}
// → Non-Deterministic Error!
//   "History says processUrgent, but code says processNormal"
```

### 6.3 예시 2: 배포 중 코드 변경

```java
// v1 배포 (기존 Workflow들이 실행 중)
activities.createOrder(request);
activities.reserveStock(request);
activities.processPayment(request);  // v1: 세 번째
activities.confirmOrder(orderId);

// v2 배포 (새 검증 Activity 추가)
activities.createOrder(request);
activities.reserveStock(request);
activities.validateOrder(request);   // v2: 새로 추가!
activities.processPayment(request);  // v2: 네 번째로 밀림
activities.confirmOrder(orderId);
```

```
┌──────────────────────────────────────────────────────────────────────┐
│                 배포 중 Non-Determinism 시나리오                      │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  v1으로 실행 시작된 Workflow의 Event History:                       │
│  - Event 5: ActivityTaskScheduled(type: "createOrder")              │
│  - Event 8: ActivityTaskScheduled(type: "reserveStock")             │
│  - Event 11: ActivityTaskScheduled(type: "processPayment")         │
│                                                                      │
│  v2 코드로 Replay 시:                                               │
│  - createOrder    → History 일치 OK                                 │
│  - reserveStock   → History 일치 OK                                 │
│  - validateOrder  → History에 없음! Non-Deterministic Error!       │
│                                                                      │
│  해결책: Workflow.getVersion()을 사용한 버전 관리                   │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

### 6.4 해결책: Workflow.getVersion()

```java
// 안전한 코드 변경 방법
activities.createOrder(request);
activities.reserveStock(request);

// Version 분기: 기존 Workflow는 DEFAULT_VERSION, 새 Workflow는 1
int version = Workflow.getVersion(
    "add-validation",           // 변경 식별자
    Workflow.DEFAULT_VERSION,   // 최소 버전 (기존)
    1                           // 최대 버전 (현재)
);

if (version >= 1) {
    // v2 이상에서만 실행
    activities.validateOrder(request);
}

activities.processPayment(request);
activities.confirmOrder(orderId);
```

`Workflow.getVersion()`은 **Marker Event**로 기록되므로 Replay 시에도 결정적이다.
기존 Workflow는 `DEFAULT_VERSION`으로 분기하고, 새 Workflow는 버전 1로 분기한다.

### 6.5 Non-Determinism Error 예방 체크리스트

```
+----+--------------------------------------+-----------------------------------+
| #  | 점검 항목                             | 확인                              |
+----+--------------------------------------+-----------------------------------+
| 1  | Workflow에서 현재 시간 직접 사용?      | Workflow.currentTimeMillis() 사용 |
| 2  | Workflow에서 랜덤값 직접 생성?         | Workflow.newRandom() 사용         |
| 3  | Workflow에서 I/O 직접 수행?            | Activity로 분리                   |
| 4  | 코드 변경 시 기존 Workflow 고려?        | Workflow.getVersion() 사용        |
| 5  | Activity 호출 순서 변경?               | getVersion()으로 분기             |
| 6  | 조건 분기에 비결정적 값 사용?           | Activity 결과만 사용              |
+----+--------------------------------------+-----------------------------------+
```

---

## 참고 자료

### 공식 문서
- [Durable Execution](https://docs.temporal.io/encyclopedia/durable-execution)
- [Workflow Execution](https://docs.temporal.io/workflow-execution)
- [Events Reference](https://docs.temporal.io/references/events)
- [Deterministic Constraints](https://docs.temporal.io/workflows#deterministic-constraints)

### 관련 학습 문서
- [01-temporal-concepts.md](./01-temporal-concepts.md) - 핵심 개념 5가지
- [02-temporal-spring.md](./02-temporal-spring.md) - Spring Boot 통합
- [05-temporal-faq.md](./05-temporal-faq.md) - 자주 묻는 질문

---

*다음 학습 -> [04-worker-event-flow.md](./04-worker-event-flow.md) (Worker 구조와 이벤트 흐름)*
