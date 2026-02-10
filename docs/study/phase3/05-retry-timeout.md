# 재시도 정책과 타임아웃

> **관련 문서**: [06-signal-query-update.md](./06-signal-query-update.md) - Signal, Query, Update
> **선행 문서**: [04-temporal-msa-architecture-flow.md](./04-temporal-msa-architecture-flow.md) - MSA 아키텍처 흐름

---

## 목차

1. [Retry Policy 4가지 설정](#1-retry-policy-4가지-설정)
2. [4가지 타임아웃](#2-4가지-타임아웃)
3. [타임아웃 다이어그램](#3-타임아웃-다이어그램)
4. [재시도 계속 실패하면?](#4-재시도-계속-실패하면)
5. [재시도하면 안 되는 에러 (NonRetryableErrorTypes)](#5-재시도하면-안-되는-에러-nonretryableerrortypes)
6. [Java 코드 예시: RetryOptions + ActivityOptions](#6-java-코드-예시-retryoptions--activityoptions)
7. [실전 팁 정리](#7-실전-팁-정리)

---

## 1. Retry Policy 4가지 설정

Temporal의 Retry Policy는 Activity 실패 시 자동 재시도 동작을 제어하는 4가지 핵심 설정으로 구성된다.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     Retry Policy 4가지 설정 요소                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. InitialInterval (초기 대기 시간)                                         │
│     첫 번째 재시도 전 대기하는 시간. 기본값: 1초                             │
│                                                                              │
│  2. BackoffCoefficient (백오프 계수)                                         │
│     재시도할 때마다 대기 시간에 곱하는 배수. 기본값: 2.0                     │
│     예: 계수 2.0이면 1초 → 2초 → 4초 → 8초 ...                              │
│     ※ 1.0으로 설정하면 매번 같은 간격으로 재시도                             │
│                                                                              │
│  3. MaximumAttempts (최대 시도 횟수)                                         │
│     총 시도 횟수 (첫 시도 포함). 기본값: 0 (무제한)                          │
│     예: 3으로 설정 → 첫 시도 + 재시도 2회 = 총 3번                          │
│                                                                              │
│  4. MaximumInterval (최대 대기 시간)                                         │
│     지수 백오프로 증가하는 대기 시간의 상한선                                │
│     기본값: InitialInterval x 100                                            │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 지수 백오프 계산 예시

```
InitialInterval = 1초, BackoffCoefficient = 2.0, MaximumInterval = 30초

시도 1 → 실패 → 대기  1초  (1 x 2^0)
시도 2 → 실패 → 대기  2초  (1 x 2^1)
시도 3 → 실패 → 대기  4초  (1 x 2^2)
시도 4 → 실패 → 대기  8초  (1 x 2^3)
시도 5 → 실패 → 대기 16초  (1 x 2^4)
시도 6 → 실패 → 대기 30초  (1 x 2^5 = 32 → MaximumInterval 적용)
시도 7 → 실패 → 대기 30초  (계속 30초 유지)
```

> **핵심**: MaximumAttempts를 0(무제한)으로 두면 ScheduleToCloseTimeout에 도달할 때까지 계속 재시도한다. 반드시 MaximumAttempts 또는 ScheduleToCloseTimeout 중 하나를 설정하자.

---

## 2. 4가지 타임아웃

Temporal Activity에는 4가지 타임아웃이 있으며, 각각 다른 구간을 보호한다.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       Activity 4가지 타임아웃                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. Schedule-To-Start Timeout                                                │
│     Task Queue에 들어간 후 Worker가 픽업하기까지의 최대 시간                 │
│     → Worker가 모두 바빠서 대기열에서 오래 기다리는 것을 감지                │
│                                                                              │
│  2. Start-To-Close Timeout                                                   │
│     Worker가 Activity를 시작한 후 완료까지의 최대 시간                       │
│     → 단일 실행의 최대 허용 시간 (가장 중요한 타임아웃, 필수급)              │
│                                                                              │
│  3. Schedule-To-Close Timeout                                                │
│     Activity가 스케줄된 후 최종 완료까지의 최대 시간 (재시도 포함!)          │
│     → 모든 재시도를 합산한 전체 소요 시간의 상한선                           │
│                                                                              │
│  4. Heartbeat Timeout                                                        │
│     Activity가 주기적으로 보내는 "나 살아있어" 신호의 간격 제한              │
│     → 장시간 Activity에서 Worker 장애 감지용                                 │
│     → 미수신 시 Activity를 실패 처리하고 다른 Worker에 재할당                │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

| 타임아웃 | 보호 대상 | 언제 사용? | 기본값 |
|---------|----------|-----------|--------|
| Schedule-To-Start | Worker 가용성 | Worker 부족 감지 | 없음 |
| Start-To-Close | 단일 실행 시간 | **항상 설정 (필수급)** | 없음 |
| Schedule-To-Close | 전체 재시도 시간 | 재시도 포함 전체 시간 제한 | 없음 |
| Heartbeat | 장시간 Activity | 대용량 파일 처리, 배치 작업 | 없음 |

---

## 3. 타임아웃 다이어그램

```
                         ScheduleToCloseTimeout (전체)
  ◀════════════════════════════════════════════════════════════════════▶

  ┌──────────────────────────────────────────────────────────────────┐
  │                                                                  │
  │   ScheduleToStart        StartToClose         StartToClose       │
  │  ◀───────────────▶  ◀──────────────────▶  ◀──────────────────▶  │
  │                                                                  │
  │  ┌─────────────┐   ┌──────────────────┐   ┌──────────────────┐  │
  │  │ Task Queue  │   │   시도 1 (실패)   │   │   시도 2 (성공)  │  │
  │  │  대기 중    │──▶│ Worker 실행      │──▶│ Worker 실행      │  │
  │  └─────────────┘   └──────────────────┘   └──────────────────┘  │
  │                                                                  │
  │  스케줄링     Worker 픽업    실패(재시도)   시작         완료     │
  │  ────●────────●──────────────●─────────────●────────────●────   │
  └──────────────────────────────────────────────────────────────────┘

  Heartbeat (장시간 Activity인 경우):

  ┌──────────────────────────────────────────────────────────────────┐
  │                    StartToClose                                  │
  │  ◀──────────────────────────────────────────────────────────▶   │
  │                                                                  │
  │  Activity 시작                                     Activity 완료 │
  │  ────●──────●──────●──────●──────●──────●──────●──────●────     │
  │       ♥      ♥      ♥      ♥      ♥      ♥      ♥              │
  │     Heartbeat 신호 (주기적으로 전송)                             │
  │                                                                  │
  │  ※ HeartbeatTimeout 내에 ♥가 안 오면 → Activity 실패 처리       │
  └──────────────────────────────────────────────────────────────────┘
```

### 타임아웃 관계

```
ScheduleToCloseTimeout >= ScheduleToStartTimeout + (StartToCloseTimeout x 재시도횟수)
```

- `StartToCloseTimeout`만 설정: 각 시도마다 이 시간 내에 완료해야 함. 재시도는 무한히 가능.
- `ScheduleToCloseTimeout`만 설정: 재시도 포함 전체 시간 제한. 개별 시도 시간은 무제한.
- **둘 다 설정 (권장)**: 개별 시도도 보호하고, 전체 시간도 보호.

---

## 4. 재시도 계속 실패하면?

MaximumAttempts만큼 재시도했는데 계속 실패하면 어떻게 될까? 3가지 시나리오로 나누어 살펴본다.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           재시도 흐름                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  시도 1 ────▶ 실패 ────▶ 1초 대기                                           │
│                           │                                                  │
│                           ▼                                                  │
│  시도 2 ────▶ 실패 ────▶ 2초 대기 (1초 x 2.0)                               │
│                           │                                                  │
│                           ▼                                                  │
│  시도 3 ────▶ 실패 ────▶ MaximumAttempts 도달!                              │
│                           │                                                  │
│                           ▼                                                  │
│                 ActivityFailure 예외 발생                                    │
│                           │                                                  │
│             ┌─────────────┴─────────────┐                                   │
│             ▼                           ▼                                   │
│  [예외 처리 없으면]              [Saga 패턴 사용 시]                         │
│  Workflow: FAILED                보상 트랜잭션 실행                          │
│  수동 개입 필요                  Workflow: COMPLETED                         │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 시나리오 1: 예외 처리 없음 --> Workflow 실패

```java
public OrderResult processOrder(OrderRequest request) {
    Order order = activities.createOrder(request);     // 성공
    activities.reserveStock(...);   // 3번 다 실패! → ActivityFailure 발생
    activities.processPayment(...); // 여기 도달 못 함
    return new OrderResult(...);   // 여기 도달 못 함
}
// 결과: Workflow FAILED, Temporal UI에서 확인 가능, 수동 개입 필요
// 문제: 주문은 생성됐지만 재고 예약은 안 됨 → 데이터 불일치!
```

### 시나리오 2: Saga 패턴으로 보상 실행

```java
public OrderResult processOrder(OrderRequest request) {
    Saga saga = new Saga(new Saga.Options.Builder().build());
    try {
        Long orderId = activities.createOrder(request);
        saga.addCompensation(() -> activities.cancelOrder(orderId));

        activities.reserveStock(...);  // 여기서 3번 실패!
        saga.addCompensation(() -> activities.cancelReservation(...));
    } catch (ActivityFailure e) {
        saga.compensate();  // 등록된 역순으로 보상 실행
        return OrderResult.failure(e.getMessage());
    }
}
// T1: createOrder  성공 → T2: reserveStock 3번 실패 → catch 진입
// C1: cancelOrder  보상 실행 → Workflow: COMPLETED (정상 종료!)
```

### 시나리오 3: 보상도 실패하면?

```java
Saga saga = new Saga(new Saga.Options.Builder()
    .setContinueWithError(true)  // 보상 하나 실패해도 나머지 계속 진행
    .build());
try {
    // 비즈니스 로직...
} catch (ActivityFailure e) {
    try {
        saga.compensate();
    } catch (Exception compensationError) {
        // 보상도 실패 → 알림 + 수동 처리 대기열에 추가
        activities.notifyOperator("보상 실패! 수동 확인 필요",
            request, compensationError.getMessage());
    }
    return OrderResult.failure(e.getMessage());
}
```

**보상 실패 대응 3단계:**

| 단계 | 전략 | 설명 |
|-----|------|------|
| 1 | `setContinueWithError(true)` | 보상 A 실패해도 B, C 계속 실행 |
| 2 | 보상 Activity에도 RetryOptions | 보상도 일시 장애일 수 있으므로 재시도 (멱등성 필수) |
| 3 | 운영자 알림 | Slack/이메일 알림 + Dead Letter Queue 저장 |

---

## 5. 재시도하면 안 되는 에러 (NonRetryableErrorTypes)

**재시도해도 결과가 바뀌지 않는 에러**는 즉시 실패 처리해야 한다.

```java
RetryOptions retryOptions = RetryOptions.newBuilder()
    .setMaximumAttempts(5)
    .setDoNotRetry(
        IllegalArgumentException.class.getName(),    // 잘못된 입력
        InsufficientStockException.class.getName(),  // 재고 부족
        AuthorizationException.class.getName(),      // 권한 없음
        DuplicateOrderException.class.getName()      // 중복 주문
    )
    .build();
```

```
┌──────────────────────────────────┬──────────────────────────────────────────┐
│     재시도 O (일시적 장애)        │     재시도 X (영구적 오류)               │
├──────────────────────────────────┼──────────────────────────────────────────┤
│ 네트워크 타임아웃                │ 재고 부족 (InsufficientStock)            │
│ 일시적 서버 오류 (503)           │ 잘못된 요청/입력 (400)                   │
│ 데이터베이스 연결 끊김           │ 비즈니스 규칙 위반                       │
│ 외부 API 일시 장애               │ 권한 없음 (401, 403)                     │
│ Worker 크래시 / OOM              │ 중복 요청 (이미 처리됨)                  │
├──────────────────────────────────┼──────────────────────────────────────────┤
│ 시간이 지나면 해결될 수 있음     │ 100번 재시도해도 같은 결과               │
└──────────────────────────────────┴──────────────────────────────────────────┘
```

> **판별 기준**: "같은 입력으로 다시 시도하면 성공할 가능성이 있는가?" Yes면 재시도, No면 `setDoNotRetry`에 추가.

---

## 6. Java 코드 예시: RetryOptions + ActivityOptions

### 6.1 기본 Activity 설정

```java
public class OrderWorkflowImpl implements OrderWorkflow {

    private final OrderActivities activities = Workflow.newActivityStub(
        OrderActivities.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setScheduleToCloseTimeout(Duration.ofMinutes(5))
            .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setBackoffCoefficient(2.0)
                .setMaximumInterval(Duration.ofSeconds(30))
                .setMaximumAttempts(3)
                .setDoNotRetry(
                    IllegalArgumentException.class.getName(),
                    InsufficientStockException.class.getName()
                )
                .build())
            .build()
    );

    @Override
    public OrderResult processOrder(OrderRequest request) {
        Long orderId = activities.createOrder(request);
        activities.reserveStock(orderId, request.getItems());
        activities.processPayment(orderId, request.getPaymentInfo());
        return new OrderResult(orderId, "COMPLETED");
    }
}
```

### 6.2 Activity별 다른 정책 설정

서비스 특성에 따라 Activity마다 다른 타임아웃/재시도 정책을 적용할 수 있다.

```java
public class OrderWorkflowImpl implements OrderWorkflow {

    // 빠른 응답 Activity (재고 확인)
    private final InventoryActivities inventoryActivities =
        Workflow.newActivityStub(InventoryActivities.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .setRetryOptions(RetryOptions.newBuilder()
                    .setMaximumAttempts(3)
                    .setInitialInterval(Duration.ofMillis(500))
                    .setDoNotRetry(InsufficientStockException.class.getName())
                    .build())
                .build());

    // 느린 외부 연동 Activity (결제 처리)
    private final PaymentActivities paymentActivities =
        Workflow.newActivityStub(PaymentActivities.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(2))
                .setScheduleToCloseTimeout(Duration.ofMinutes(10))
                .setRetryOptions(RetryOptions.newBuilder()
                    .setMaximumAttempts(5)
                    .setInitialInterval(Duration.ofSeconds(2))
                    .setMaximumInterval(Duration.ofSeconds(60))
                    .setDoNotRetry(PaymentDeclinedException.class.getName())
                    .build())
                .build());

    // 장시간 Activity (대용량 파일) - Heartbeat 사용
    private final FileActivities fileActivities =
        Workflow.newActivityStub(FileActivities.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofHours(1))
                .setHeartbeatTimeout(Duration.ofMinutes(1))
                .setRetryOptions(RetryOptions.newBuilder()
                    .setMaximumAttempts(2)
                    .build())
                .build());
}
```

### 6.3 Heartbeat 전송 Activity 구현

```java
public class FileActivitiesImpl implements FileActivities {
    @Override
    public void processLargeFile(String filePath) {
        List<String> lines = readFile(filePath);
        int total = lines.size();
        for (int i = 0; i < total; i++) {
            processLine(lines.get(i));
            if (i % 100 == 0) {
                // HeartbeatTimeout(1분) 내에 호출되지 않으면 Activity 실패 처리
                Activity.getExecutionContext().heartbeat(
                    "진행률: " + (i * 100 / total) + "%");
            }
        }
    }
}
```

### 6.4 Saga + 재시도 통합 예시

```java
public OrderResult processOrder(OrderRequest request) {
    Saga saga = new Saga(new Saga.Options.Builder()
        .setContinueWithError(true).build());
    try {
        Long orderId = activities.createOrder(request);
        saga.addCompensation(() -> activities.cancelOrder(orderId));

        activities.reserveStock(orderId, request.getItems());
        saga.addCompensation(() ->
            activities.cancelReservation(orderId, request.getItems()));

        activities.processPayment(orderId, request.getPaymentInfo());
        saga.addCompensation(() -> activities.refundPayment(orderId));

        return new OrderResult(orderId, "COMPLETED");
    } catch (ActivityFailure e) {
        saga.compensate();  // 재시도 모두 소진 → 보상 실행
        return OrderResult.failure(e.getMessage());
    }
}
```

---

## 7. 실전 팁 정리

### 타임아웃 설정 가이드라인

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        타임아웃 설정 가이드라인                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  [일반 API 호출]                                                             │
│  StartToCloseTimeout: 10~30초 / MaximumAttempts: 3 / InitialInterval: 1초   │
│                                                                              │
│  [결제 등 외부 연동]                                                         │
│  StartToCloseTimeout: 1~2분 / ScheduleToCloseTimeout: 10분                  │
│  MaximumAttempts: 5 / InitialInterval: 2초                                   │
│                                                                              │
│  [배치/파일 처리]                                                            │
│  StartToCloseTimeout: 1시간 / HeartbeatTimeout: 1분 / MaximumAttempts: 2    │
│                                                                              │
│  [꼭 기억할 것]                                                              │
│  - StartToCloseTimeout은 항상 설정하라                                       │
│  - 재시도 무제한(0)이면 ScheduleToCloseTimeout 반드시 설정                   │
│  - Heartbeat은 장시간 Activity에만 사용                                      │
│  - 보상 Activity에도 RetryOptions를 설정하라                                 │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 재시도 vs 타임아웃 상호작용

```
MaximumAttempts=3, StartToCloseTimeout=30초, ScheduleToCloseTimeout=2분

사례 1: 정상 재시도
  시도1(5초, 실패) → 1초 → 시도2(5초, 실패) → 2초 → 시도3(3초, 성공)
  총 16초 → ScheduleToClose 내에 완료

사례 2: MaximumAttempts 도달
  시도1(5초, 실패) → 1초 → 시도2(5초, 실패) → 2초 → 시도3(5초, 실패)
  총 18초 → MaximumAttempts 도달 → ActivityFailure

사례 3: StartToClose로 개별 시도 타임아웃
  시도1(30초 경과, 미완료) → StartToCloseTimeout → 강제 종료 → 재시도
```

---

> **다음 학습**: [06-signal-query-update.md](./06-signal-query-update.md) - Signal, Query, Update를 활용한 Workflow 외부 소통
