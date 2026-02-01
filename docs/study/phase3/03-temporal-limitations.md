# Temporal 한계와 보완 전략

## 이 문서에서 배우는 것

- Temporal이 해결하는 분산 시스템 문제 8가지
- Temporal이 해결하지 못하는 문제 6가지
- 각 한계에 대한 보완 전략과 구현 방법
- Phase 2에서 배운 기술들이 Temporal과 함께 필요한 이유

---

## 1. Temporal의 위치: 만능 해결사가 아니다

### Temporal은 무엇을 하는 도구인가?

```
┌─────────────────────────────────────────────────────────────────┐
│                    Temporal의 핵심 역할                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Temporal = "Workflow Orchestration Platform"                    │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  "코드의 실행 흐름을 안정적으로 보장"                       │    │
│  │                                                          │    │
│  │  - 상태 저장 ✓                                           │    │
│  │  - 재시도 ✓                                              │    │
│  │  - 복구 ✓                                                │    │
│  │  - 추적 ✓                                                │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
│  그러나...                                                       │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  "비즈니스 로직 자체의 문제는 해결 못함"                    │    │
│  │                                                          │    │
│  │  - 동시성 제어 ✗                                          │    │
│  │  - 데이터 정합성 ✗                                        │    │
│  │  - 외부 서비스 멱등성 ✗                                   │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 중요한 인식

```
┌─────────────────────────────────────────────────────────────────┐
│                                                                  │
│   "Temporal을 도입해도 Phase 2에서 배운 기술들은 여전히 필요하다" │
│                                                                  │
│   Phase 2-A: 분산 락, 멱등성, 낙관적 락, Saga 보상                │
│   Phase 2-B: Redis 캐싱, Outbox 패턴, 모니터링                   │
│                                                                  │
│   이것들은 Temporal과 "함께" 사용되어야 한다                      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. Temporal이 해결하는 8가지 문제

### 해결 목록

| # | 문제 | Temporal 해결 방식 |
|---|------|-------------------|
| 1 | 상태 유실 | Event Sourcing으로 모든 상태 자동 저장 |
| 2 | 자동 재시도 | Retry Policy 선언적 설정 |
| 3 | 중복 실행 방지 | Workflow ID 기반 멱등성 |
| 4 | Saga 보상 순서 | 내장 Saga 패턴으로 역순 보상 보장 |
| 5 | 순차 실행 | Workflow 내 Activity 순차 실행 보장 |
| 6 | At-least-once 전달 | Activity 재시도로 최소 1회 실행 보장 |
| 7 | 실행 이력 추적 | Event History로 완벽한 추적 |
| 8 | 타임아웃 처리 | Activity/Workflow 타임아웃 내장 |

### 해결 1: 상태 유실 → Event Sourcing

```
┌─────────────────────────────────────────────────────────────────┐
│                  문제: 서버 장애 시 상태 유실                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  [Phase 2-A 방식]                                                │
│  ────────────────                                                │
│  Step 1 실행 → DB 저장 → Step 2 실행 → 서버 다운!                │
│                                      ↑                          │
│                              어디까지 완료됐지?                   │
│                              재시작하면 처음부터?                 │
│                                                                  │
│  ────────────────────────────────────────────────────────────   │
│                                                                  │
│  [Temporal 방식]                                                 │
│  ──────────────                                                  │
│  Event #1: WorkflowStarted                                      │
│  Event #2: Activity_Step1_Completed(result="order-123")         │
│  Event #3: Activity_Step2_Started  ← 서버 다운!                  │
│                                                                  │
│  재시작 시:                                                      │
│  - Event #1, #2 재생 (replay)                                   │
│  - Step 2부터 이어서 실행                                        │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 해결 2: 자동 재시도 → Retry Policy

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

### 해결 3: 중복 실행 방지 → Workflow ID

```
┌─────────────────────────────────────────────────────────────────┐
│              Workflow ID 기반 멱등성                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  // 같은 Workflow ID로 두 번 시작 요청                           │
│  WorkflowOptions options = WorkflowOptions.newBuilder()         │
│      .setWorkflowId("order-" + orderId)  // ← 고유 ID            │
│      .build();                                                  │
│                                                                  │
│  요청 1: processOrder("order-123")  → Workflow 시작             │
│  요청 2: processOrder("order-123")  → 이미 실행 중! (중복 방지)  │
│                                                                  │
│  Temporal이 자동으로:                                            │
│  - 같은 ID의 Workflow가 이미 있으면 새로 시작 안함               │
│  - 기존 Workflow 참조 반환                                       │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 해결 4: Saga 보상 순서 → 내장 Saga 패턴

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
        saga.compensate();  // ← 역순 자동 실행 (환불 → 재고 복구 → 주문 취소)
        throw e;
    }
}
```

### 해결 5~8: 요약

```
┌─────────────────────────────────────────────────────────────────┐
│                   나머지 해결 항목들                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  5. 순차 실행                                                    │
│     ─────────                                                   │
│     Workflow 코드 순서대로 Activity 실행 보장                    │
│     step1() → step2() → step3() (순서 절대 안 바뀜)             │
│                                                                  │
│  6. At-least-once 전달                                          │
│     ─────────────────                                           │
│     Activity 실패 시 자동 재시도로 최소 1회 실행 보장            │
│     (단, 중복 실행 가능성 있음 → 멱등성 필요)                    │
│                                                                  │
│  7. 실행 이력 추적                                               │
│     ─────────────                                               │
│     Event History + Temporal Web UI                              │
│     - 어떤 Activity가 언제 실행됐는지                            │
│     - 실패 원인이 무엇인지                                       │
│     - 현재 어느 단계인지                                         │
│                                                                  │
│  8. 타임아웃 처리                                                │
│     ────────────                                                │
│     ActivityOptions.setStartToCloseTimeout(Duration.ofMinutes(5))│
│     - 타임아웃 시 자동 재시도 또는 실패 처리                     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. Temporal이 해결하지 못하는 6가지 문제

### 미해결 목록

| # | 문제 | 왜 Temporal이 해결 못하나 | 보완 전략 |
|---|------|-------------------------|----------|
| 1 | 동시성 제어 | Workflow 외부 문제 | 분산 락, DB 락 |
| 2 | 외부 서비스 멱등성 | 외부 시스템 영역 | Idempotency Key 설계 |
| 3 | 최종 일관성 | 분산 시스템 근본 한계 | Saga + 보상 설계 |
| 4 | 비즈니스 로직 | 개발자 영역 | 도메인 설계 |
| 5 | 스키마 진화 | 데이터 영역 | 버전 관리, 호환성 |
| 6 | 테스트 복잡성 | 도구 한계 | Testcontainers, 모킹 |

### 미해결 1: 동시성 제어

```
┌─────────────────────────────────────────────────────────────────┐
│            문제: 동시 요청 시 Race Condition                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  재고: 10개                                                      │
│                                                                  │
│  Workflow A: reserveStock(5)  ─┐                                │
│                                ├─ 동시 실행                      │
│  Workflow B: reserveStock(8)  ─┘                                │
│                                                                  │
│  문제:                                                          │
│  - A와 B가 각각 재고 10 확인                                     │
│  - A가 5개 예약 → 재고 5                                        │
│  - B가 8개 예약 → 재고 -3 (오버셀링!)                           │
│                                                                  │
│  왜 Temporal이 해결 못하나?                                      │
│  ─────────────────────────                                      │
│  Temporal은 "Workflow 실행 흐름"을 관리                          │
│  "외부 서비스의 데이터 동시성"은 관리 대상 아님                   │
│                                                                  │
│  각 Workflow는 독립적으로 실행됨                                 │
│  Workflow 간 조율은 Temporal의 역할이 아님                       │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**보완 전략:**

```java
// 방법 1: Activity 내에서 분산 락 사용
@Component
public class InventoryActivitiesImpl implements InventoryActivities {

    private final RedissonClient redisson;

    @Override
    public String reserveStock(String productId, int quantity) {
        RLock lock = redisson.getLock("inventory:" + productId);

        try {
            if (lock.tryLock(5, 10, TimeUnit.SECONDS)) {
                // 재고 확인 및 예약 (락 안에서 원자적 실행)
                return doReserve(productId, quantity);
            }
            throw new LockAcquisitionException("재고 락 획득 실패");
        } finally {
            lock.unlock();
        }
    }
}

// 방법 2: DB 레벨 Atomic UPDATE
@Repository
public class InventoryRepository {

    @Query("""
        UPDATE inventory
        SET quantity = quantity - :amount
        WHERE product_id = :productId
          AND quantity >= :amount
        """)
    int decreaseStock(String productId, int amount);
    // 영향받은 row가 0이면 재고 부족
}
```

### 미해결 2: 외부 서비스 멱등성

```
┌─────────────────────────────────────────────────────────────────┐
│          문제: Activity 재시도 시 중복 처리                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Temporal Activity 실행 흐름:                                    │
│                                                                  │
│  시도 1: processPayment(orderId) → 결제 API 호출 → 타임아웃     │
│                                     (실제로는 결제 성공)         │
│                                                                  │
│  시도 2: processPayment(orderId) → 결제 API 호출 → 응답 수신    │
│                                     (또 결제됨 = 이중 결제!)     │
│                                                                  │
│  왜 Temporal이 해결 못하나?                                      │
│  ─────────────────────────                                      │
│  Temporal의 멱등성 = "같은 Workflow ID로 중복 시작 방지"         │
│  Activity 내부에서 외부 API를 어떻게 호출하는지는 모름           │
│                                                                  │
│  외부 서비스(결제 API)가 멱등성을 지원해야 함                    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**보완 전략:**

```java
// Idempotency Key 패턴
@Component
public class PaymentActivitiesImpl implements PaymentActivities {

    @Override
    public String processPayment(String orderId, BigDecimal amount) {
        // Activity 실행마다 고유한 Idempotency Key 생성
        String idempotencyKey = "payment-" + orderId + "-" +
                               Activity.getExecutionContext().getInfo().getActivityId();

        PaymentRequest request = PaymentRequest.builder()
            .orderId(orderId)
            .amount(amount)
            .idempotencyKey(idempotencyKey)  // ← 핵심!
            .build();

        // 결제 API가 idempotencyKey로 중복 요청 걸러냄
        return paymentClient.process(request);
    }
}
```

```
┌─────────────────────────────────────────────────────────────────┐
│              Idempotency Key 동작 방식                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  결제 서비스:                                                    │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                                                           │   │
│  │  1. 요청 수신: { idempotencyKey: "payment-order123-act1" }│   │
│  │                                                           │   │
│  │  2. DB 조회: 이 키로 처리된 결제 있나?                     │   │
│  │     - 있음 → 기존 결과 반환 (재처리 안함)                  │   │
│  │     - 없음 → 결제 처리 후 키 저장                         │   │
│  │                                                           │   │
│  │  3. 같은 키로 재요청 시 → "이미 처리됨" 응답               │   │
│  │                                                           │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 미해결 3: 최종 일관성

```
┌─────────────────────────────────────────────────────────────────┐
│        문제: 분산 시스템의 근본적 한계 (CAP 정리)                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  시나리오:                                                       │
│  ─────────                                                      │
│  주문 서비스: 주문 상태 = "결제완료"                             │
│  재고 서비스: 재고 예약 상태 = "예약중" (아직 확정 안됨)          │
│  결제 서비스: 결제 상태 = "완료"                                 │
│                                                                  │
│  → 같은 시점에 3개 서비스가 서로 다른 상태!                      │
│  → 이것이 "최종 일관성" (Eventual Consistency)                   │
│                                                                  │
│  왜 Temporal이 해결 못하나?                                      │
│  ─────────────────────────                                      │
│  - 각 서비스가 독립된 DB를 사용 (MSA 근본 특성)                  │
│  - Temporal이 트랜잭션을 묶어줄 수 없음                          │
│  - 분산 트랜잭션의 근본적 한계 (2PC 없이)                        │
│                                                                  │
│  Temporal은 "흐름의 완료"는 보장하지만                           │
│  "모든 시점에 일관성"은 보장 못함                                │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**보완 전략:**

```
┌─────────────────────────────────────────────────────────────────┐
│                최종 일관성 대응 전략                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. Saga 보상 패턴 (이미 사용 중)                                │
│     - 실패 시 역순으로 보상 트랜잭션 실행                        │
│     - Temporal의 Saga 클래스 활용                                │
│                                                                  │
│  2. 재고 예약 패턴                                               │
│     - 즉시 차감 대신 "예약" 상태 사용                            │
│     - 모든 단계 완료 후 "확정"                                   │
│     - 실패 시 "예약 취소"로 복구 용이                            │
│                                                                  │
│  3. 상태 조회 API                                                │
│     - 사용자에게 "처리 중" 상태 노출                             │
│     - 최종 상태가 될 때까지 폴링 또는 웹소켓                     │
│                                                                  │
│  4. 비동기 알림                                                  │
│     - 최종 상태 확정 시 사용자에게 알림                          │
│     - "주문이 완료되었습니다" 푸시/이메일                        │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 미해결 4: 비즈니스 로직

```
┌─────────────────────────────────────────────────────────────────┐
│            문제: 도메인 로직의 정확성                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Temporal이 해주는 것:                                           │
│  - "step1 → step2 → step3 순서로 실행하겠다"                    │
│  - "실패하면 재시도하겠다"                                       │
│  - "상태를 저장하겠다"                                           │
│                                                                  │
│  Temporal이 모르는 것:                                           │
│  - step1에서 재고가 마이너스가 되면 안 된다                      │
│  - step2에서 결제 금액이 0보다 커야 한다                         │
│  - step3에서 주문 상태 전이 규칙이 뭔지                          │
│                                                                  │
│  잘못된 비즈니스 로직:                                           │
│  ──────────────────                                             │
│  if (stock < 0) {                                               │
│      // 이 조건을 개발자가 안 넣으면?                            │
│      // Temporal은 모른다!                                       │
│  }                                                              │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**보완 전략:**

```java
// 1. 도메인 모델에서 검증
public class Inventory {
    private int quantity;

    public void decrease(int amount) {
        if (amount <= 0) {
            throw new InvalidAmountException("차감량은 양수여야 합니다");
        }
        if (this.quantity < amount) {
            throw new InsufficientStockException("재고 부족: " +
                this.quantity + " < " + amount);
        }
        this.quantity -= amount;
    }
}

// 2. Bean Validation 활용
public record OrderRequest(
    @NotNull String productId,
    @Min(1) @Max(100) int quantity,
    @NotNull @Positive BigDecimal price
) {}

// 3. Activity에서 검증
@Override
public String reserveStock(ReserveRequest request) {
    // 비즈니스 규칙 검증
    validator.validate(request);

    // 도메인 로직 실행
    return inventoryService.reserve(request);
}
```

### 미해결 5: 스키마 진화

```
┌─────────────────────────────────────────────────────────────────┐
│           문제: 장기 실행 Workflow의 스키마 변경                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  시나리오:                                                       │
│  ─────────                                                      │
│  Day 1: Workflow 시작 (버전 1)                                  │
│         OrderRequest { productId, quantity }                    │
│                                                                  │
│  Day 3: 스키마 변경 (버전 2)                                     │
│         OrderRequest { productId, quantity, priority }  // 추가 │
│                                                                  │
│  Day 5: 버전 1 Workflow가 Activity 실행                          │
│         → priority 필드가 없는 구버전 데이터!                    │
│         → NullPointerException? 역직렬화 실패?                   │
│                                                                  │
│  왜 Temporal이 해결 못하나?                                      │
│  ─────────────────────────                                      │
│  Temporal은 이벤트를 저장하고 재생(replay)                       │
│  저장된 데이터의 스키마는 개발자가 관리해야 함                    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**보완 전략:**

```java
// 1. Workflow Versioning API 사용
public class OrderWorkflowImpl implements OrderWorkflow {

    @Override
    public OrderResult processOrder(OrderRequest request) {
        // 버전 분기
        int version = Workflow.getVersion(
            "add-priority-field",  // 변경 식별자
            Workflow.DEFAULT_VERSION,  // 최소 버전
            1  // 최대 버전
        );

        if (version == Workflow.DEFAULT_VERSION) {
            // 기존 로직 (priority 없음)
            return processOrderV1(request);
        } else {
            // 새 로직 (priority 있음)
            return processOrderV2(request);
        }
    }
}

// 2. 하위 호환성 유지
public record OrderRequest(
    String productId,
    int quantity,
    Integer priority  // nullable로 추가 (기존 호환)
) {
    public int getPriorityOrDefault() {
        return priority != null ? priority : 0;
    }
}

// 3. 점진적 마이그레이션
// - 새 Workflow는 새 버전으로 시작
// - 기존 실행 중인 Workflow는 구버전으로 완료
// - 모든 구버전 완료 후 구버전 코드 제거
```

### 미해결 6: 테스트 복잡성

```
┌─────────────────────────────────────────────────────────────────┐
│            문제: 분산 시스템 테스트의 어려움                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  테스트 시나리오:                                                │
│  ───────────────                                                │
│  1. 주문 생성 후 재고 예약 중 결제 서비스 다운                   │
│  2. 결제 타임아웃 후 재시도 시 재고 서비스 다운                  │
│  3. 보상 트랜잭션 중 주문 서비스 다운                            │
│  4. 동시에 같은 상품 10개 주문                                   │
│                                                                  │
│  어떻게 테스트?                                                  │
│  ─────────────                                                  │
│  - 실제 서비스 다운시키기? → 환경 구성 복잡                     │
│  - 모킹? → 실제 동작과 다를 수 있음                             │
│  - 통합 테스트? → 느리고 불안정                                 │
│                                                                  │
│  왜 Temporal이 해결 못하나?                                      │
│  ─────────────────────────                                      │
│  Temporal은 "실행"을 담당, "테스트"는 별도 영역                  │
│  TestWorkflowEnvironment 제공하지만 한계 있음                    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**보완 전략:**

```java
// 1. Temporal TestWorkflowEnvironment
@Test
void testOrderWorkflow() {
    TestWorkflowEnvironment testEnv = TestWorkflowEnvironment.newInstance();
    Worker worker = testEnv.newWorker("order-queue");

    // Mock Activities
    OrderActivities mockActivities = mock(OrderActivities.class);
    when(mockActivities.createOrder(any())).thenReturn("order-123");
    when(mockActivities.reserveStock(any())).thenThrow(new RuntimeException("재고 부족"));

    worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);
    worker.registerActivitiesImplementations(mockActivities);
    testEnv.start();

    // Workflow 실행 및 검증
    OrderWorkflow workflow = testEnv.getWorkflowClient()
        .newWorkflowStub(OrderWorkflow.class);

    assertThrows(WorkflowException.class,
        () -> workflow.processOrder(request));

    // 보상 트랜잭션 호출 검증
    verify(mockActivities).cancelOrder("order-123");
}

// 2. Testcontainers로 통합 테스트
@Testcontainers
class OrderIntegrationTest {

    @Container
    static GenericContainer<?> temporal = new GenericContainer<>("temporalio/auto-setup:1.22")
        .withExposedPorts(7233);

    @Test
    void testFullOrderFlow() {
        // 실제 Temporal 서버에서 테스트
    }
}

// 3. Chaos Engineering
// - Toxiproxy로 네트워크 장애 시뮬레이션
// - 랜덤 서비스 종료 테스트
```

---

## 4. Phase 2 기술과 Temporal의 조합

### 조합 매트릭스

```
┌─────────────────────────────────────────────────────────────────┐
│              Phase 2 기술 + Temporal 조합                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Phase 2 기술          Temporal 역할        조합 방식            │
│  ─────────────────────────────────────────────────────────────  │
│                                                                  │
│  분산 락 (Redisson)    Workflow 실행 보장    Activity 내에서      │
│                                              락 사용             │
│                                                                  │
│  멱등성 Key            중복 Workflow 방지    Activity에서         │
│                        (Workflow ID)        외부 API 호출 시     │
│                                                                  │
│  Saga 패턴             Saga 클래스 제공     Workflow에서          │
│                                              Saga 사용           │
│                                                                  │
│  낙관적 락             해당 없음             Activity 내에서      │
│  (Version 체크)                              DB 업데이트 시       │
│                                                                  │
│  Outbox 패턴           이벤트 전달 보장      Activity로           │
│                        (Activity 재시도)     대체 가능            │
│                                                                  │
│  Redis 캐싱            해당 없음             Activity에서         │
│                                              캐시 활용           │
│                                                                  │
│  Circuit Breaker       재시도 정책으로       필요시 추가          │
│                        일부 대체             보호막으로 사용      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

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
                request.productId(),
                request.quantity()
            );
            saga.addCompensation(() -> activities.releaseStock(reservationId));

            // Step 3: 결제 처리 (멱등성 Key 전달)
            String paymentId = activities.processPaymentIdempotent(
                orderId,
                request.amount()
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

## 5. 학습 정리: 무엇을 언제 사용하나?

### 의사결정 플로우차트

```
┌─────────────────────────────────────────────────────────────────┐
│                    문제 유형별 해결 도구                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  문제를 만났을 때:                                               │
│                                                                  │
│  Q1: 실행 흐름/상태 관리 문제인가?                               │
│      │                                                          │
│      ├─ YES → Temporal 사용                                     │
│      │        - Workflow로 흐름 정의                             │
│      │        - Activity로 작업 분리                             │
│      │        - 자동 재시도/복구                                 │
│      │                                                          │
│      └─ NO → Q2로                                               │
│                                                                  │
│  Q2: 동시성/Race Condition 문제인가?                            │
│      │                                                          │
│      ├─ YES → 분산 락 또는 DB 락                                │
│      │        - Redisson 분산 락                                 │
│      │        - Atomic UPDATE                                   │
│      │        - 낙관적 락 (Version)                             │
│      │                                                          │
│      └─ NO → Q3로                                               │
│                                                                  │
│  Q3: 중복 요청/멱등성 문제인가?                                  │
│      │                                                          │
│      ├─ YES → Idempotency Key                                   │
│      │        - 요청마다 고유 키 생성                            │
│      │        - DB에 처리 기록 저장                              │
│      │                                                          │
│      └─ NO → Q4로                                               │
│                                                                  │
│  Q4: 일관성/데이터 정합성 문제인가?                              │
│      │                                                          │
│      ├─ YES → 패턴 조합                                         │
│      │        - Saga + 예약 패턴                                 │
│      │        - Outbox 패턴                                     │
│      │        - 보상 트랜잭션                                    │
│      │                                                          │
│      └─ NO → 비즈니스 로직 검토                                  │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 핵심 정리

```
┌─────────────────────────────────────────────────────────────────┐
│                         핵심 정리                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. Temporal은 만능이 아니다                                     │
│     - "실행 흐름"을 안정적으로 만들어주는 도구                   │
│     - 비즈니스 로직/동시성/멱등성은 여전히 개발자 책임           │
│                                                                  │
│  2. Phase 2 기술은 Temporal과 함께 사용된다                      │
│     - 분산 락: Activity 내 동시성 제어                           │
│     - 멱등성: 외부 API 호출 시 필수                              │
│     - 예약 패턴: 안전한 Saga 보상                                │
│                                                                  │
│  3. 적절한 도구를 적절한 문제에                                  │
│     - 모든 문제를 Temporal로 해결하려 하지 말 것                 │
│     - 문제 유형에 맞는 도구 선택                                 │
│                                                                  │
│  4. 근본적 한계는 인정                                           │
│     - 최종 일관성은 분산 시스템의 특성                           │
│     - 완벽한 일관성이 필요하면 MSA가 아닌 다른 선택             │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 6. 실습 과제

### 과제 1: Activity에 분산 락 적용

다음 Activity에 Redisson 분산 락을 적용해보세요:

```java
// Before: 락 없음
@Override
public String reserveStock(String productId, int quantity) {
    Inventory inventory = inventoryRepository.findByProductId(productId);
    if (inventory.getQuantity() < quantity) {
        throw new InsufficientStockException();
    }
    inventory.decrease(quantity);
    return reservationRepository.save(new Reservation(...)).getId();
}

// TODO: After - 분산 락 적용
```

### 과제 2: Idempotency Key 구현

결제 Activity에 Idempotency Key 패턴을 적용해보세요:

1. Activity에서 고유 키 생성
2. 결제 서비스에 키 전달
3. 결제 서비스에서 중복 체크 로직 구현

### 과제 3: Workflow Versioning 연습

기존 Workflow에 새 필드를 추가하는 시나리오:

1. 기존 `OrderRequest`에 `couponCode` 필드 추가
2. `Workflow.getVersion()` 사용하여 버전 분기
3. 기존 실행 중인 Workflow와 새 Workflow 모두 정상 동작 확인

---

## 참고 자료

- [Temporal Failure Handling](https://docs.temporal.io/dev-guide/java/failure-handling)
- [Temporal Versioning](https://docs.temporal.io/dev-guide/java/versioning)
- [Temporal Testing](https://docs.temporal.io/dev-guide/java/testing)
- [Saga Pattern with Temporal](https://temporal.io/blog/saga-pattern-made-easy)

---

## 다음 단계

학습한 내용을 바탕으로 Phase 3 실습에서 Temporal Workflow를 직접 구현합니다.
- Activity에 분산 락, 멱등성 적용
- Saga 보상 패턴 구현
- 장애 시나리오 테스트
