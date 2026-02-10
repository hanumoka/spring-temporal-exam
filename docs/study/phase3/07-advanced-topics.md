# Temporal 심화 기능

> **전제**: `00-temporal-deep-dive.md`, `01-temporal-concepts.md` 학습 완료
> **작성일**: 2026-02-10
> **관련 문서**: [05-temporal-faq.md](./05-temporal-faq.md), [01-temporal-advanced-concepts.md](./01-temporal-advanced-concepts.md)

---

## 목차

1. [Workflow ID와 Run ID](#1-workflow-id와-run-id)
2. [Timer와 Durable Sleep](#2-timer와-durable-sleep)
3. [Child Workflow](#3-child-workflow)
4. [Continue-As-New](#4-continue-as-new)
5. [Activity Heartbeat 심화](#5-activity-heartbeat-심화)

---

## 1. Workflow ID와 Run ID

### 1.1 개념 비교

```
┌───────────────────────────────────────────────────────────────┐
│                   Workflow ID vs Run ID                        │
├───────────────────────────────────────────────────────────────┤
│                                                                │
│  Workflow ID = 비즈니스 식별자 (개발자가 지정)                 │
│  - 예: "order-12345", "payment-67890"                          │
│  - 중복 실행 방지에 사용                                       │
│  - 같은 ID로 Workflow를 재시작할 수 있음                       │
│                                                                │
│  Run ID = 실행 식별자 (Temporal이 자동 생성)                   │
│  - UUID 형식, 매 실행마다 새로 생성                            │
│  - 같은 Workflow ID라도 실행마다 다른 Run ID                   │
│  - Event History를 구분하는 데 사용                            │
│                                                                │
│  비유:                                                         │
│  Workflow ID = 학번 (고유, 변하지 않음)                        │
│  Run ID = 학기 등록 번호 (매 학기마다 다름)                     │
│                                                                │
└───────────────────────────────────────────────────────────────┘
```

### 1.2 중복 실행 방지

```java
WorkflowOptions options = WorkflowOptions.newBuilder()
    .setTaskQueue("order-queue")
    .setWorkflowId("order-" + orderId)  // 비즈니스 ID 사용
    .setWorkflowIdReusePolicy(
        WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE
    )
    .build();
// 같은 orderId로 다시 시작하면 WorkflowExecutionAlreadyStartedException 발생
```

### 1.3 Workflow ID Reuse Policy

| Policy | 설명 |
|--------|------|
| **REJECT_DUPLICATE** | 같은 ID의 Workflow가 존재하면 거부 |
| **ALLOW_DUPLICATE** | 이전 실행이 Closed 상태면 허용 |
| **ALLOW_DUPLICATE_FAILED_ONLY** | 이전 실행이 Failed/Canceled/Terminated면 허용 |
| **TERMINATE_IF_RUNNING** | 기존 실행을 종료하고 새로 시작 |

---

## 2. Timer와 Durable Sleep

### 2.1 Durable Timer 개념

> 핵심: `Thread.sleep()`은 절대 사용하면 안 된다. `Workflow.sleep()`을 사용해야 한다.

```
┌───────────────────────────────────────────────────────────────┐
│               Thread.sleep vs Workflow.sleep                   │
├───────────────────────────────────────────────────────────────┤
│                                                                │
│  Thread.sleep(60000):                                         │
│  1. Worker 스레드가 1분간 블로킹                              │
│  2. Worker 크래시 시 → 상태 유실, 처음부터 다시               │
│  3. 다른 Workflow 처리 못함 (스레드 낭비)                     │
│  4. Workflow 결정적 규칙 위반!                                │
│                                                                │
│  Workflow.sleep(Duration.ofMinutes(1)):                        │
│  1. TimerStarted 이벤트 저장                                  │
│  2. Worker 스레드 즉시 반환 (다른 작업 가능)                  │
│  3. Temporal Server가 타이머 관리                             │
│  4. 1분 후 TimerFired 이벤트 → Workflow 재개                  │
│  5. Worker 크래시 시 → 타이머 상태 유지, 자동 복구            │
│                                                                │
│  핵심: 타이머 동안 Worker 리소스 0 소비!                      │
│                                                                │
└───────────────────────────────────────────────────────────────┘
```

### 2.2 awaitCondition vs sleep

```java
// 방법 1: sleep + 폴링 (비권장 - 5분 간격 지연, Timer 이벤트 누적)
for (int i = 0; i < 6; i++) {
    Workflow.sleep(Duration.ofMinutes(5));
    if (paymentCompleted) break;
}

// 방법 2: Workflow.await (권장 - 조건 충족 즉시 재개, 이벤트 최소화)
boolean completed = Workflow.await(
    Duration.ofMinutes(30),         // 최대 대기 시간
    () -> this.paymentCompleted     // 조건 (Signal로 변경됨)
);
if (!completed) {
    return OrderResult.failure("결제 시간 초과");
}
```

### 2.3 실용 예제: Signal + await 결합

```java
public class OrderWorkflowImpl implements OrderWorkflow {
    private boolean paymentCompleted = false;

    @SignalMethod
    public void onPaymentCompleted() { this.paymentCompleted = true; }

    @Override
    public OrderResult processOrder(OrderRequest request) {
        Long orderId = activities.createOrder(request.customerId());

        // 결제 대기 (최대 30분, Signal 수신 시 즉시 진행)
        boolean paid = Workflow.await(
            Duration.ofMinutes(30), () -> this.paymentCompleted
        );
        if (!paid) {
            saga.compensate();
            return OrderResult.failure("결제 시간 초과");
        }
        activities.confirmOrder(orderId);
        return OrderResult.success(orderId);
    }
}
```

---

## 3. Child Workflow

### 3.1 개념

Child Workflow는 Workflow 안에서 다른 Workflow를 실행하는 패턴이다.
각 Child는 독립적인 Event History를 가지며, Parent와 별도로 관리된다.

```
┌───────────────────────────────────────────────────────────────┐
│                Parent-Child Workflow 관계                       │
├───────────────────────────────────────────────────────────────┤
│                                                                │
│  Parent Workflow (OrderWorkflow)                              │
│  ┌──────────────────────────────────────────────────────┐     │
│  │  1. 주문 생성 (Activity)                              │     │
│  │  2. 재고 예약 (Activity)                              │     │
│  │  3. ┌──────────────────────────────────────┐         │     │
│  │     │ Child Workflow (PaymentWorkflow)     │         │     │
│  │     │  결제 생성 → PG 승인 → 결제 확정    │         │     │
│  │     └──────────────────────────────────────┘         │     │
│  │  4. 주문 확정 (Activity)                              │     │
│  └──────────────────────────────────────────────────────┘     │
│                                                                │
│  특징:                                                        │
│  - Child는 독립적인 Event History를 가짐                      │
│  - Parent 취소 시 Child 처리 방식 설정 가능 (Policy)          │
│  - Parent Event History 크기를 줄임                           │
│                                                                │
└───────────────────────────────────────────────────────────────┘
```

### 3.2 사용 시점

| 사용해야 할 때 | 사용하지 말아야 할 때 |
|---------------|---------------------|
| 복잡한 서브 프로세스 분리 | 단순 코드 정리 목적 |
| 다른 팀이 관리하는 로직 호출 | 항상 함께 성공/실패해야 할 때 |
| 독립적인 재시도 정책 필요 | 같은 Task Queue + 작은 Event History |
| Event History 크기 관리 | 순차 실행만 필요할 때 |
| 다른 Task Queue에서 실행 | - |

> 주의: 간단한 경우는 Activity로 충분하다. Child Workflow는 오버헤드가 있으므로 신중하게 사용해야 한다.

### 3.3 Java 구현 예제

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

        ChildWorkflowOptions childOptions = ChildWorkflowOptions.newBuilder()
                .setWorkflowId("payment-" + orderId)
                .setTaskQueue("payment-task-queue")
                .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_TERMINATE)
                .build();

        PaymentWorkflow paymentWorkflow = Workflow.newChildWorkflowStub(
                PaymentWorkflow.class, childOptions);

        PaymentResult paymentResult = paymentWorkflow.processPayment(
                new PaymentRequest(orderId, request.amount()));

        if (!paymentResult.isSuccess()) {
            saga.compensate();
            return OrderResult.failure(paymentResult.getError());
        }
        activities.confirmOrder(orderId);
        return OrderResult.success(orderId, paymentResult.getPaymentId());
    }
}
```

### 3.4 Parent Close Policy

Parent Workflow가 종료될 때 Child를 어떻게 처리할지 결정하는 정책이다.

| Policy | 설명 | 사용 시점 |
|--------|------|----------|
| **ABANDON** (기본값) | Parent 종료해도 Child 계속 실행 | Child가 독립적으로 완료되어야 할 때 |
| **TERMINATE** | Parent 종료 시 Child도 즉시 종료 | 항상 함께 종료되어야 할 때 |
| **REQUEST_CANCEL** | Parent 종료 시 Child에 취소 요청 | Child에게 정리 기회를 줄 때 |

### 3.5 Child Workflow vs Activity 비교

| 특성 | Activity | Child Workflow |
|------|----------|----------------|
| 복잡도 | 단순 작업 | 복잡한 서브 프로세스 |
| Event History | Parent에 포함 | 독립 History |
| 재시도 | Activity 단위 | Workflow 단위 |
| 상태 추적 | 없음 | Signal, Query 가능 |
| 오버헤드 | 낮음 | 높음 |

---

## 4. Continue-As-New

### 4.1 필요성: Event History 크기 문제

무한 루프나 장기 실행 Workflow는 Event History가 계속 커진다.

```
┌───────────────────────────────────────────────────────────────┐
│              Event History 크기 문제                            │
├───────────────────────────────────────────────────────────────┤
│                                                                │
│  문제 상황: 무한 루프 Workflow                                │
│                                                                │
│  while (true) {                                               │
│      items = activities.fetchNewItems();                      │
│      for (item : items) {                                     │
│          activities.processItem(item);  // 매번 Event 추가   │
│      }                                                         │
│      Workflow.sleep(Duration.ofMinutes(5));                   │
│  }                                                             │
│                                                                │
│  Event History 증가:                                          │
│  - 1시간: ~1,000 이벤트                                       │
│  - 1일: ~24,000 이벤트                                        │
│  - 1주: ~168,000 이벤트                                       │
│                                                                │
│  Temporal 제한:                                               │
│  - 경고: 50,000 이벤트                                        │
│  - 하드 제한: 설정에 따라 다름 (보통 200K~1M)                 │
│                                                                │
└───────────────────────────────────────────────────────────────┘
```

### 4.2 동작 방식

```
┌───────────────────────────────────────────────────────────────┐
│             Continue-As-New 동작 방식                           │
├───────────────────────────────────────────────────────────────┤
│                                                                │
│  Run 1 (Event: 1~10,000)                                      │
│  ┌────────────────────────────────────────────────────┐       │
│  │  Event 1: WorkflowExecutionStarted                  │       │
│  │  Event 2~9,999: Activity, Timer 이벤트들            │       │
│  │  Event 10,000: WorkflowExecutionContinuedAsNew      │       │
│  └────────────────────────────────────────────────────┘       │
│                          │                                     │
│                          ▼ (상태 전달: processedCount=50000)   │
│  Run 2 (Event: 1~10,000)                                      │
│  ┌────────────────────────────────────────────────────┐       │
│  │  Event 1: WorkflowExecutionStarted (ContinueAsNew)  │       │
│  │  Event 2~9,999: Activity, Timer 이벤트들            │       │
│  │  Event 10,000: WorkflowExecutionContinuedAsNew      │       │
│  └────────────────────────────────────────────────────┘       │
│                          │                                     │
│                          ▼                                     │
│  Run 3 ...                                                     │
│                                                                │
│  핵심: 같은 Workflow ID, 다른 Run ID, 깨끗한 Event History    │
│                                                                │
└───────────────────────────────────────────────────────────────┘
```

### 4.3 Java 구현 예제

```java
public class BatchProcessorWorkflowImpl implements BatchProcessorWorkflow {

    private static final int BATCH_SIZE = 1000;

    @Override
    public void processBatch(BatchState state) {
        int processedInThisRun = 0;

        while (true) {
            List<Item> items = activities.fetchItems(
                state.getLastProcessedId(), 100
            );

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

@Data
public class BatchState {
    private Long lastProcessedId = 0L;
    private int processedCount = 0;
}
```

### 4.4 Continue-As-New vs Child Workflow

| 특성 | Continue-As-New | Child Workflow |
|------|-----------------|----------------|
| **목적** | Event History 크기 관리 | 논리적 분리 |
| **Workflow ID** | 동일 | 다름 |
| **실행 연속성** | 연속적 (같은 "작업") | 독립적 (다른 "작업") |
| **상태 전달** | 명시적 파라미터 | 반환값 또는 Signal |
| **사용 예** | 무한 루프, 배치 처리 | 서브 프로세스 분리 |

### 4.5 사용 시 주의사항

```java
// 1. 상태는 반드시 파라미터로 전달해야 함
Workflow.continueAsNew(state);

// 2. continueAsNew() 호출 후 코드는 실행되지 않음
Workflow.continueAsNew(state);
logger.info("이 로그는 출력되지 않음");  // 도달 불가

// 3. Signal/Query 핸들러는 새 Run에서도 자동으로 등록됨

// 4. 진행 중인 Child Workflow가 있으면?
//    → Parent Close Policy에 따라 처리됨
//    → 보통 Child 완료 후 continueAsNew 호출하는 것이 안전
```

---

## 5. Activity Heartbeat 심화

### 5.1 Heartbeat가 필요한 이유

장기 실행 Activity에서 Worker 장애를 빠르게 감지하기 위한 메커니즘이다.

```
┌───────────────────────────────────────────────────────────────┐
│           Heartbeat 없이 vs 있을 때 비교                       │
├───────────────────────────────────────────────────────────────┤
│                                                                │
│  Heartbeat 없이 (StartToCloseTimeout = 5시간):                │
│  T+0분   : Activity 시작 (10GB 파일 처리)                     │
│  T+30분  : Worker 크래시!                                     │
│  T+5시간 : Temporal이 타임아웃 감지 → 재시도                  │
│  → 4시간 30분 낭비!                                           │
│                                                                │
│  Heartbeat 사용 시 (HeartbeatTimeout = 1분):                  │
│  T+0분   : Activity 시작                                      │
│  T+30분  : Worker 크래시! (마지막 Heartbeat T+29분 30초)      │
│  T+31분  : Heartbeat 미수신 감지 → 즉시 재시도                │
│  → 1분 만에 문제 감지!                                        │
│                                                                │
└───────────────────────────────────────────────────────────────┘
```

### 5.2 Heartbeat 구현 (진행 상태 저장 + 복구)

```java
@Component
public class FileProcessorActivitiesImpl implements FileProcessorActivities {
    @Override
    public ProcessResult processLargeFile(String filePath) {
        ActivityExecutionContext context = Activity.getExecutionContext();
        File file = new File(filePath);
        long totalSize = file.length();
        long processedSize = 0;

        // 이전 Heartbeat에서 저장한 진행 상태 복구 (재시도 시)
        Optional<Long> lastProgress = context.getHeartbeatDetails(Long.class);
        if (lastProgress.isPresent()) {
            processedSize = lastProgress.get();
            skipToPosition(file, processedSize);  // 중단된 위치부터 재개
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                processLine(line);
                processedSize += line.length();
                if (processedSize % (1024 * 1024) == 0) {  // 매 1MB마다
                    context.heartbeat(processedSize);       // 진행 상태 저장
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

### 5.3 Heartbeat Details로 복구

Heartbeat Details는 재시도 시 이전 진행 상태를 복구하는 핵심 메커니즘이다.

```
┌───────────────────────────────────────────────────────────────┐
│              Heartbeat Details로 복구 흐름                      │
├───────────────────────────────────────────────────────────────┤
│                                                                │
│  1차 시도 (Worker A):                                         │
│    처리 시작 → Heartbeat(1MB) → (2MB) → (3MB) → 크래시!      │
│                                                                │
│  2차 시도 (Worker B):                                         │
│    getHeartbeatDetails() → 3MB → 3MB부터 재개 → 완료!         │
│                                                                │
│  결과: 0~3MB 재처리 없이 3MB부터 계속                         │
│                                                                │
└───────────────────────────────────────────────────────────────┘
```

### 5.4 Heartbeat 설정 가이드

```java
ActivityOptions options = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofHours(5))     // 전체 실행 시간
        .setHeartbeatTimeout(Duration.ofMinutes(1))      // 장애 감지 속도
        .setRetryOptions(RetryOptions.newBuilder()
                .setMaximumAttempts(3)
                .build())
        .build();
```

| 상황 | HeartbeatTimeout | 이유 |
|------|-----------------|------|
| 빠른 장애 감지 필요 | 30초 ~ 1분 | 장애 시 즉시 대응 |
| 네트워크 불안정 | 2분 ~ 5분 | 일시적 끊김 허용 |
| 배치 작업 (느린 진행) | 5분 ~ 10분 | Heartbeat 주기보다 여유 있게 |

### 5.5 구현 시 주의사항

```java
// 1. Heartbeat 간격은 HeartbeatTimeout의 80% 이하로
//    HeartbeatTimeout = 1분이면 → 매 40~50초마다 전송

// 2. Heartbeat Details에 직렬화 가능한 객체만 저장
context.heartbeat(processedSize);           // Long (OK)
context.heartbeat(new ProgressState(...));  // 커스텀 객체 (OK, 직렬화 가능해야)

// 3. 너무 빈번한 Heartbeat 호출은 SDK가 자동 throttling
//    → 매 줄마다 heartbeat() 호출해도 실제 전송은 간격 조절됨

// 4. Heartbeat는 장기 Activity에만 필요
//    → 수 초 내 완료되는 Activity에는 불필요
//    → HeartbeatTimeout 미설정 시 Heartbeat 무시됨
```

---

## 요약: 기능별 사용 시점

```
┌───────────────────────────────────────────────────────────────┐
│                  심화 기능 사용 시점 정리                       │
├───────────────────────────────────────────────────────────────┤
│                                                                │
│  Workflow.sleep / Workflow.await                              │
│  → 일정 시간 대기 또는 조건 충족 대기                         │
│  → 결제 대기, 승인 대기, 스케줄링                             │
│                                                                │
│  Child Workflow                                               │
│  → 복잡한 서브 프로세스 분리                                  │
│  → 팀 간 Workflow 호출, Event History 크기 관리               │
│                                                                │
│  Continue-As-New                                              │
│  → 무한 루프 / 장기 실행 Workflow의 Event History 관리       │
│  → 배치 처리에서 주기적으로 History 초기화                    │
│                                                                │
│  Activity Heartbeat                                           │
│  → 장기 실행 Activity (분~시간 단위)                          │
│  → 장애 빠른 감지 + 진행 상태 복구                            │
│                                                                │
└───────────────────────────────────────────────────────────────┘
```

---

## 참고 자료

### 공식 문서
- [Workflow Execution](https://docs.temporal.io/workflow-execution)
- [Child Workflows](https://docs.temporal.io/child-workflows)
- [Continue-As-New](https://docs.temporal.io/workflow-execution/continue-as-new)
- [Detecting Activity Failures](https://docs.temporal.io/encyclopedia/detecting-activity-failures)

### 블로그
- [Activity Timeouts](https://temporal.io/blog/activity-timeouts)
- [Very Long-Running Workflows](https://temporal.io/blog/very-long-running-workflows)

---

*다음 학습 -> [08-spring-integration.md](./08-spring-integration.md)*
