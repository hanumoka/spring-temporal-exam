# Temporal 한계와 Phase 2 기술 조합

## 이 문서에서 배우는 것

- Temporal이 해결하는 문제와 해결하지 못하는 문제의 명확한 경계
- Phase 2에서 배운 기술들이 Temporal과 함께 필요한 이유
- 문제 유형별 올바른 도구 선택법

---

## 1. Temporal의 위치: 만능 해결사가 아니다

### 핵심 인식

```
+-------------------------------------------------------------------+
|                    Temporal의 핵심 역할                            |
+-------------------------------------------------------------------+
|                                                                    |
|  Temporal = "Workflow Orchestration Platform"                      |
|                                                                    |
|  +-------------------------------------------------------------+  |
|  |  "코드의 실행 흐름을 안정적으로 보장"                        |  |
|  |                                                              |  |
|  |  - 상태 저장 O       - 재시도 O                              |  |
|  |  - 복구 O            - 추적 O                                |  |
|  +-------------------------------------------------------------+  |
|                                                                    |
|  그러나...                                                         |
|                                                                    |
|  +-------------------------------------------------------------+  |
|  |  "비즈니스 로직 자체의 문제는 해결 못함"                     |  |
|  |                                                              |  |
|  |  - 동시성 제어 X     - 데이터 정합성 X                       |  |
|  |  - 외부 서비스 멱등성 X                                      |  |
|  +-------------------------------------------------------------+  |
|                                                                    |
|  "Temporal을 도입해도 Phase 2에서 배운 기술들은 여전히 필요하다"   |
|                                                                    |
|  Phase 2-A: 분산 락, 멱등성, 낙관적 락, Saga 보상                 |
|  Phase 2-B: Redis 캐싱, Outbox 패턴, 모니터링                     |
|                                                                    |
|  이것들은 Temporal과 "함께" 사용되어야 한다                        |
|                                                                    |
+-------------------------------------------------------------------+
```

---

## 2. Temporal이 해결하는 8가지 문제

| # | 문제 | Temporal 해결 방식 |
|---|------|-------------------|
| 1 | **상태 유실** | Event Sourcing으로 모든 상태 자동 저장 |
| 2 | **자동 재시도** | Retry Policy 선언적 설정 |
| 3 | **중복 실행 방지** | Workflow ID 기반 멱등성 |
| 4 | **Saga 보상 순서** | 내장 Saga 패턴으로 역순 보상 보장 |
| 5 | 순차 실행 | Workflow 내 Activity 순차 실행 보장 |
| 6 | At-least-once 전달 | Activity 재시도로 최소 1회 실행 보장 |
| 7 | 실행 이력 추적 | Event History로 완벽한 추적 |
| 8 | 타임아웃 처리 | Activity/Workflow 타임아웃 내장 |

### 상세 1: 상태 유실 -> Event Sourcing

```
+-------------------------------------------------------------------+
|                문제: 서버 장애 시 상태 유실                        |
+-------------------------------------------------------------------+
|                                                                    |
|  [Phase 2-A 방식]                                                  |
|  Step 1 실행 -> DB 저장 -> Step 2 실행 -> 서버 다운!               |
|                                      ^                             |
|                          어디까지 완료됐지? 처음부터?              |
|                                                                    |
|  [Temporal 방식]                                                   |
|  Event #1: WorkflowStarted                                        |
|  Event #2: Activity_Step1_Completed(result="order-123")            |
|  Event #3: Activity_Step2_Started  <-- 서버 다운!                  |
|                                                                    |
|  재시작 시:                                                        |
|  - Event #1, #2 재생 (replay)                                     |
|  - Step 2부터 이어서 실행                                          |
|                                                                    |
+-------------------------------------------------------------------+
```

### 상세 2: Saga 보상 순서 -> 내장 Saga 패턴

```java
// Temporal의 Saga 클래스 사용
public OrderResult processOrder(OrderRequest request) {
    Saga saga = new Saga(new Saga.Options.Builder()
        .setParallelCompensation(false)  // 순차 보상
        .build());

    try {
        // Step 1: 주문 생성
        String orderId = activities.createOrder(request);
        saga.addCompensation(() -> activities.cancelOrder(orderId));

        // Step 2: 재고 예약
        String reservationId = activities.reserveStock(orderId);
        saga.addCompensation(() -> activities.releaseStock(reservationId));

        // Step 3: 결제
        String paymentId = activities.processPayment(orderId);
        saga.addCompensation(() -> activities.refundPayment(paymentId));

        // Step 4: 확정
        activities.confirmOrder(orderId);
        return OrderResult.success(orderId);

    } catch (Exception e) {
        saga.compensate();  // <-- 역순 자동 실행 (환불 -> 재고 복구 -> 주문 취소)
        throw e;
    }
}
```

### 상세 3: 자동 재시도 -> Retry Policy

```java
// Phase 2-A: Resilience4j 설정 + 코드
@Retry(name = "inventoryService", fallbackMethod = "fallback")
public ReservationResponse reserveStock(String orderId) {
    return inventoryClient.reserve(orderId);
}

// Temporal: 선언적 설정
ActivityOptions options = ActivityOptions.newBuilder()
    .setRetryOptions(RetryOptions.newBuilder()
        .setInitialInterval(Duration.ofSeconds(1))
        .setBackoffCoefficient(2.0)
        .setMaximumAttempts(5)
        .setDoNotRetry(InvalidInputException.class)  // 이 예외는 재시도 안함
        .build())
    .build();

// Activity 호출만 하면 자동 재시도
String result = activities.reserveStock(orderId);
```

### 나머지 해결 항목 (5~8) 요약

| # | 항목 | 핵심 |
|---|------|------|
| 5 | 순차 실행 | step1() -> step2() -> step3() 순서 절대 안 바뀜 |
| 6 | At-least-once | 실패 시 자동 재시도 (단, 멱등성 필요) |
| 7 | 실행 이력 | Event History + Temporal Web UI로 완벽한 추적 |
| 8 | 타임아웃 | `setStartToCloseTimeout(Duration.ofMinutes(5))` |

---

## 3. Temporal이 해결하지 못하는 6가지 문제

| # | 문제 | 왜 못 하나 | 보완 전략 |
|---|------|-----------|----------|
| 1 | **동시성 제어** | Workflow 외부 문제 | 분산 락, DB 락 |
| 2 | **외부 서비스 멱등성** | 외부 시스템 영역 | Idempotency Key 설계 |
| 3 | **최종 일관성** | 분산 시스템 근본 한계 | Saga + 보상 설계 |
| 4 | 비즈니스 로직 | 개발자 영역 | 도메인 설계, Bean Validation |
| 5 | 스키마 진화 | 데이터 영역 | Workflow Versioning API |
| 6 | 테스트 복잡성 | 도구 한계 | Testcontainers, 모킹 |

### 상세 1: 동시성 제어

```
+-------------------------------------------------------------------+
|           문제: 동시 요청 시 Race Condition                        |
+-------------------------------------------------------------------+
|                                                                    |
|  재고: 10개                                                        |
|                                                                    |
|  Workflow A: reserveStock(5)  --+                                  |
|                                 +-- 동시 실행                      |
|  Workflow B: reserveStock(8)  --+                                  |
|                                                                    |
|  A와 B가 각각 재고 10 확인 -> A가 5 예약 -> B가 8 예약             |
|  -> 재고 -3 (오버셀링!)                                            |
|                                                                    |
|  Temporal은 "Workflow 실행 흐름"을 관리                            |
|  "외부 서비스의 데이터 동시성"은 관리 대상 아님                     |
|  각 Workflow는 독립적으로 실행, Workflow 간 조율 X                  |
|                                                                    |
+-------------------------------------------------------------------+
```

**보완 전략:**

```java
// 방법 1: Activity 내에서 분산 락 사용
@Override
public String reserveStock(String productId, int quantity) {
    RLock lock = redisson.getLock("inventory:" + productId);
    try {
        if (lock.tryLock(5, 10, TimeUnit.SECONDS)) {
            return doReserve(productId, quantity);  // 락 안에서 원자적 실행
        }
        throw new LockAcquisitionException("재고 락 획득 실패");
    } finally {
        lock.unlock();
    }
}

// 방법 2: DB 레벨 Atomic UPDATE
@Query("""
    UPDATE inventory SET quantity = quantity - :amount
    WHERE product_id = :productId AND quantity >= :amount
    """)
int decreaseStock(String productId, int amount);
// 영향받은 row가 0이면 재고 부족
```

### 상세 2: 외부 서비스 멱등성

```
+-------------------------------------------------------------------+
|         문제: Activity 재시도 시 중복 처리                         |
+-------------------------------------------------------------------+
|                                                                    |
|  시도 1: processPayment(orderId) -> 결제 API -> 타임아웃           |
|                                     (실제로는 결제 성공)           |
|                                                                    |
|  시도 2: processPayment(orderId) -> 결제 API -> 응답 수신          |
|                                     (또 결제됨 = 이중 결제!)       |
|                                                                    |
|  Temporal의 멱등성 = "같은 Workflow ID로 중복 시작 방지"           |
|  Activity 내부에서 외부 API를 어떻게 호출하는지는 모름              |
|                                                                    |
+-------------------------------------------------------------------+
```

**보완 전략:**

```java
// Idempotency Key 패턴
@Override
public String processPayment(String orderId, BigDecimal amount) {
    // Activity 실행마다 고유한 Idempotency Key 생성
    String idempotencyKey = "payment-" + orderId + "-" +
                           Activity.getExecutionContext().getInfo().getActivityId();

    PaymentRequest request = PaymentRequest.builder()
        .orderId(orderId)
        .amount(amount)
        .idempotencyKey(idempotencyKey)  // <-- 핵심!
        .build();

    // 결제 API가 idempotencyKey로 중복 요청 걸러냄
    return paymentClient.process(request);
}
```

```
  결제 서비스 동작:
  1. 요청 수신: { idempotencyKey: "payment-order123-act1" }
  2. DB 조회: 이 키로 처리된 결제 있나?
     - 있음 -> 기존 결과 반환 (재처리 안함)
     - 없음 -> 결제 처리 후 키 저장
  3. 같은 키로 재요청 시 -> "이미 처리됨" 응답
```

### 상세 3: 최종 일관성

```
+-------------------------------------------------------------------+
|       문제: 분산 시스템의 근본적 한계 (CAP 정리)                   |
+-------------------------------------------------------------------+
|                                                                    |
|  주문 서비스: 주문 상태 = "결제완료"                               |
|  재고 서비스: 재고 예약 상태 = "예약중" (아직 확정 안됨)           |
|  결제 서비스: 결제 상태 = "완료"                                   |
|                                                                    |
|  -> 같은 시점에 3개 서비스가 서로 다른 상태!                       |
|                                                                    |
|  Temporal은 "흐름의 완료"는 보장하지만                             |
|  "모든 시점에 일관성"은 보장 못함                                  |
|                                                                    |
|  대응 전략:                                                        |
|  1. Saga 보상 패턴 - 실패 시 역순 보상                             |
|  2. 재고 예약 패턴 - 즉시 차감 대신 "예약" 후 "확정"               |
|  3. 상태 조회 API - "처리 중" 상태 노출                            |
|  4. 비동기 알림 - 최종 확정 시 사용자 알림                         |
|                                                                    |
+-------------------------------------------------------------------+
```

### 나머지 미해결 항목 (4~6) 요약

| # | 문제 | 핵심 보완 전략 |
|---|------|---------------|
| 4 | 비즈니스 로직 | 도메인 모델 검증, Bean Validation, Activity에서 검증 |
| 5 | 스키마 진화 | `Workflow.getVersion()` API, 하위 호환 필드(nullable), 점진적 마이그레이션 |
| 6 | 테스트 복잡성 | `TestWorkflowEnvironment`, Testcontainers, Chaos Engineering |

**스키마 진화 보완 코드 예시:**

```java
// Workflow Versioning API 사용
public OrderResult processOrder(OrderRequest request) {
    int version = Workflow.getVersion(
        "add-priority-field",          // 변경 식별자
        Workflow.DEFAULT_VERSION,      // 최소 버전
        1                              // 최대 버전
    );

    if (version == Workflow.DEFAULT_VERSION) {
        return processOrderV1(request);  // 기존 로직
    } else {
        return processOrderV2(request);  // 새 로직 (priority 포함)
    }
}
```

---

## 4. Phase 2 기술과 Temporal 조합 매트릭스

### 핵심 매트릭스

| Phase 2 기술 | Temporal 역할 | 조합 방식 | 필요도 |
|-------------|-------------|----------|-------|
| **분산 락** (Redisson) | Workflow 실행 보장 | Activity 내에서 락 사용 | **필수** |
| **멱등성 Key** | 중복 Workflow 방지 (Workflow ID) | Activity에서 외부 API 호출 시 | **필수** |
| **Saga 패턴** | Saga 클래스 제공 | Workflow에서 Saga 사용 | **필수** |
| 낙관적 락 (Version) | 해당 없음 | Activity 내 DB 업데이트 시 | 상황별 |
| Outbox 패턴 | Activity 재시도로 이벤트 전달 보장 | Activity로 대체 가능 | 선택적 |
| Redis 캐싱 | 해당 없음 | Activity에서 캐시 활용 | 선택적 |
| Circuit Breaker | 재시도 정책으로 일부 대체 | 필요시 추가 보호막으로 사용 | 선택적 |

### 통합 예시: 완전한 Order Workflow

```java
@WorkflowInterface
public interface OrderWorkflow {
    @WorkflowMethod
    OrderResult processOrder(OrderRequest request);
}

public class OrderWorkflowImpl implements OrderWorkflow {

    private final OrderActivities activities = Workflow.newActivityStub(
        OrderActivities.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .setRetryOptions(RetryOptions.newBuilder()
                .setMaximumAttempts(3)
                .setDoNotRetry(
                    InsufficientStockException.class,  // 비즈니스 예외는 재시도 안함
                    InvalidInputException.class
                )
                .build())
            .build()
    );

    @Override
    public OrderResult processOrder(OrderRequest request) {
        Saga saga = new Saga(new Saga.Options.Builder().build());

        try {
            // Step 1: 주문 생성 (멱등성 Key: Workflow ID 활용)
            String orderId = activities.createOrder(request);
            saga.addCompensation(() -> activities.cancelOrder(orderId));

            // Step 2: 재고 예약 (분산 락 + 예약 패턴)
            String reservationId = activities.reserveStockWithLock(
                request.productId(), request.quantity()
            );
            saga.addCompensation(() -> activities.releaseStock(reservationId));

            // Step 3: 결제 처리 (멱등성 Key 전달)
            String paymentId = activities.processPaymentIdempotent(
                orderId, request.amount()
            );
            saga.addCompensation(() -> activities.refundPayment(paymentId));

            // Step 4: 재고 확정 + 주문 확정
            activities.confirmReservation(reservationId);
            activities.confirmOrder(orderId);

            return OrderResult.success(orderId);

        } catch (Exception e) {
            saga.compensate();
            return OrderResult.failure(e.getMessage());
        }
    }
}
```

---

## 5. 의사결정 플로우차트

```
+-------------------------------------------------------------------+
|                 문제 유형별 해결 도구                               |
+-------------------------------------------------------------------+
|                                                                    |
|  문제를 만났을 때:                                                 |
|                                                                    |
|  Q1: 실행 흐름/상태 관리 문제인가?                                 |
|      |                                                             |
|      +-- YES --> Temporal 사용                                     |
|      |           - Workflow로 흐름 정의                             |
|      |           - Activity로 작업 분리                             |
|      |           - 자동 재시도/복구                                 |
|      |                                                             |
|      +-- NO --> Q2로                                               |
|                                                                    |
|  Q2: 동시성/Race Condition 문제인가?                               |
|      |                                                             |
|      +-- YES --> 분산 락 또는 DB 락                                |
|      |           - Redisson 분산 락                                 |
|      |           - Atomic UPDATE / 낙관적 락                       |
|      |                                                             |
|      +-- NO --> Q3로                                               |
|                                                                    |
|  Q3: 중복 요청/멱등성 문제인가?                                    |
|      |                                                             |
|      +-- YES --> Idempotency Key                                   |
|      |           - 요청마다 고유 키 생성                            |
|      |           - DB에 처리 기록 저장                              |
|      |                                                             |
|      +-- NO --> Q4로                                               |
|                                                                    |
|  Q4: 일관성/데이터 정합성 문제인가?                                |
|      |                                                             |
|      +-- YES --> 패턴 조합                                         |
|      |           - Saga + 예약 패턴                                 |
|      |           - Outbox 패턴 / 보상 트랜잭션                     |
|      |                                                             |
|      +-- NO --> 비즈니스 로직 검토                                  |
|                                                                    |
+-------------------------------------------------------------------+
```

---

## 6. 핵심 정리

```
+-------------------------------------------------------------------+
|                         핵심 정리                                  |
+-------------------------------------------------------------------+
|                                                                    |
|  1. Temporal은 만능이 아니다                                       |
|     - "실행 흐름"을 안정적으로 만들어주는 도구                     |
|     - 비즈니스 로직/동시성/멱등성은 여전히 개발자 책임             |
|                                                                    |
|  2. Phase 2 기술은 Temporal과 함께 사용된다                        |
|     - 분산 락: Activity 내 동시성 제어                             |
|     - 멱등성: 외부 API 호출 시 필수                                |
|     - 예약 패턴: 안전한 Saga 보상                                  |
|                                                                    |
|  3. 적절한 도구를 적절한 문제에                                    |
|     - 모든 문제를 Temporal로 해결하려 하지 말 것                   |
|     - 문제 유형에 맞는 도구 선택                                   |
|                                                                    |
|  4. 근본적 한계는 인정                                             |
|     - 최종 일관성은 분산 시스템의 특성                              |
|     - 완벽한 일관성이 필요하면 MSA가 아닌 다른 선택               |
|                                                                    |
+-------------------------------------------------------------------+
```

### 한 줄 요약

| 주제 | 한 줄 요약 |
|------|-----------|
| Temporal 역할 | 실행 흐름과 상태를 안정적으로 보장하는 오케스트레이션 플랫폼 |
| 해결하는 것 | 상태 유실, 재시도, Saga 보상, 중복 실행, 타임아웃, 이력 추적 |
| 해결 못하는 것 | 동시성 제어, 외부 멱등성, 최종 일관성, 비즈니스 로직 검증 |
| Phase 2 기술 | Temporal 도입 후에도 분산 락/멱등성 Key/예약 패턴은 **필수** |
| 설계 원칙 | Temporal은 "흐름"을 담당, 나머지는 Activity 내부에서 해결 |

---

## 참고 자료

- [Temporal Failure Handling](https://docs.temporal.io/dev-guide/java/failure-handling)
- [Temporal Versioning](https://docs.temporal.io/dev-guide/java/versioning)
- [Temporal Testing](https://docs.temporal.io/dev-guide/java/testing)
- [Saga Pattern with Temporal](https://temporal.io/blog/saga-pattern-made-easy)

---

> **다음 학습** -> [13-production.md](./13-production.md)
