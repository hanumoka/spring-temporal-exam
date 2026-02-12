# 코드 리뷰: 이론과 실제 코드의 연결

> **목적**: Phase 3 학습 내용을 실제 프로젝트 코드에서 발견한 버그와 연결하여 이해를 심화
> **대상**: Phase 3 학습 문서 전체 학습 완료 후 실전 코드 점검
> **전제**: 09-saga-with-temporal.md, 11-activity-design.md, 08-spring-integration.md 학습 완료

---

## 목차

1. [개요](#1-개요)
2. [Saga 보상의 멱등성](#2-saga-보상의-멱등성)
3. [Workflow 예외 처리](#3-workflow-예외-처리)
4. [Activity 구현 안전성](#4-activity-구현-안전성)
5. [멱등성 인프라 (IdempotencyAspect)](#5-멱등성-인프라-idempotencyaspect)
6. [Spring 연동 이슈](#6-spring-연동-이슈)
7. [서비스 도메인 로직](#7-서비스-도메인-로직)
8. [개선 권장 사항 (LOW)](#8-개선-권장-사항-low)
9. [전체 요약](#9-전체-요약)

---

## 1. 개요

### 1.1 리뷰 범위

```
┌─────────────────────────────────────────────────────────┐
│                   코드 리뷰 범위                           │
├─────────────────────────────────────────────────────────┤
│                                                           │
│  대상 모듈:                                              │
│  ├── common/           (DTO, 예외, 멱등성, 필터)          │
│  ├── service-order/    (주문 도메인, Outbox)              │
│  ├── service-inventory/(재고 도메인, Semantic Lock)       │
│  ├── service-payment/  (결제 도메인, PG 연동)             │
│  ├── service-notification/ (알림, Redis Stream)           │
│  ├── orchestrator-pure/    (순수 Saga 구현)              │
│  └── orchestrator-temporal/(Temporal Saga 구현)           │
│                                                           │
│  제외: 미구현 기능 (Phase 2-B, DevOps 등)                │
│                                                           │
│  발견 이슈: 29개                                         │
│  ├── CRITICAL: 4개                                       │
│  ├── HIGH:     3개                                       │
│  ├── MEDIUM:  13개                                       │
│  └── LOW:      9개                                       │
│                                                           │
└─────────────────────────────────────────────────────────┘
```

### 1.2 학습 문서와의 연결

| 학습 문서 | 관련 이슈 | 핵심 교훈 |
|-----------|-----------|-----------|
| 09-saga-with-temporal.md | C-2, M-8, M-9, M-13 | 보상은 반드시 멱등, 예외 처리 주의 |
| 11-activity-design.md | C-1, H-3, M-3, M-11 | Activity 내부 안전성은 개발자 책임 |
| 08-spring-integration.md | C-3, M-7, M-10 | 설정 오류는 런타임에 드러남 |
| 05-retry-timeout.md | C-4, H-1, H-2 | 멱등성 인프라 자체의 견고함 필수 |

---

## 2. Saga 보상의 멱등성

> -> 관련 문서: [09-saga-with-temporal.md](./09-saga-with-temporal.md) 3.4절 "멱등성의 중요성"

### 2.1 C-2: Payment.refund()가 보상 시 예외 발생

**파일**: `service-payment/.../entity/Payment.java`

```java
// 현재 코드
public void refund() {
    if (this.status != PaymentStatus.APPROVED && this.status != PaymentStatus.CONFIRMED) {
        throw new IllegalStateException("승인/확정된 결제만 환불할 수 있습니다");
    }
    this.status = PaymentStatus.REFUNDED;
}
```

```
┌─────────────────────────────────────────────────────────┐
│                  문제 시나리오                              │
├─────────────────────────────────────────────────────────┤
│                                                           │
│  T1: createOrder   OK                                    │
│  T2: reserveStock  OK                                    │
│  T3: createPayment OK → 보상 등록: refundPayment         │
│  T3-2: approvePayment FAIL!                              │
│                                                           │
│  saga.compensate() 실행:                                 │
│  → refundPayment() 호출                                  │
│  → Payment 상태: PENDING (아직 승인 안 됨)               │
│  → refund() 호출 → IllegalStateException! ← 보상 실패   │
│                                                           │
│  결과: 보상 자체가 실패 → 데이터 정합성 깨짐             │
│                                                           │
└─────────────────────────────────────────────────────────┘
```

**학습 포인트**: 보상 트랜잭션의 3대 원칙

```
┌─────────────────────────────────────────────────────────┐
│           보상 트랜잭션 3대 원칙                           │
├─────────────────────────────────────────────────────────┤
│                                                           │
│  1. 멱등성: 여러 번 호출해도 같은 결과                    │
│     → 이미 REFUNDED면 조용히 반환                        │
│                                                           │
│  2. 관대함: 어떤 상태에서든 실패하지 않아야 함            │
│     → PENDING이면 환불 불필요 → 조용히 반환              │
│     → FAILED이면 환불 불필요 → 조용히 반환               │
│                                                           │
│  3. 재시도 가능: Temporal이 보상도 재시도할 수 있음        │
│     → 예외 발생 = 보상 실패 = 시스템 불일치              │
│                                                           │
└─────────────────────────────────────────────────────────┘
```

**수정 방향**:

```java
public void refund() {
    // 이미 환불됨 → 멱등성
    if (this.status == PaymentStatus.REFUNDED) return;
    // 승인/확정 전 → 환불 불필요 (관대함)
    if (this.status == PaymentStatus.PENDING || this.status == PaymentStatus.FAILED) return;
    this.status = PaymentStatus.REFUNDED;
}
```

### 2.2 M-13: confirm 단계 (T4/T5/T6) 보상 미등록

**파일**: `orchestrator-temporal/.../OrderWorkflowImpl.java`

```
┌─────────────────────────────────────────────────────────┐
│               현재 보상 등록 현황                          │
├─────────────────────────────────────────────────────────┤
│                                                           │
│  T1: createOrder      → saga.addCompensation(cancel)  ✅ │
│  T2: reserveStock     → saga.addCompensation(cancel)  ✅ │
│  T3: createPayment    → saga.addCompensation(refund)  ✅ │
│  T3-2: approvePayment → 보상 없음                     ⚠️ │
│  T4: confirmOrder     → 보상 없음                     ⚠️ │
│  T5: confirmReservation → 보상 없음                   ⚠️ │
│  T6: confirmPayment   → 보상 없음                     ⚠️ │
│                                                           │
│  시나리오: T5 실패 시                                    │
│  → cancelReservation 호출 (하지만 이미 confirmed!)       │
│  → 서비스의 상태 기계가 올바르게 처리해야 함             │
│                                                           │
└─────────────────────────────────────────────────────────┘
```

**설계 원칙**: 확정 단계는 "되돌릴 수 없는 작업"이므로 Temporal 재시도로 최대한 성공시킨다.
단, 각 서비스의 보상 메서드가 **확정된 상태도 처리**할 수 있어야 안전하다.

> -> 09-saga-with-temporal.md 3.3절 "확정 단계의 특수성" 참조

---

## 3. Workflow 예외 처리

> -> 관련 문서: [05-retry-timeout.md](./05-retry-timeout.md), [09-saga-with-temporal.md](./09-saga-with-temporal.md)

### 3.1 M-8: generic Exception catch가 CanceledFailure를 가로챔

**파일**: `orchestrator-temporal/.../OrderWorkflowImpl.java:163-170`

```java
// 현재 코드
try {
    // ... 정방향 트랜잭션 ...
} catch (ActivityFailure e) {
    saga.compensate();
    return OrderResult.failure(e.getMessage());
} catch (Exception e) {  // ← 문제!
    saga.compensate();
    return OrderResult.failure(e.getMessage());
}
```

```
┌─────────────────────────────────────────────────────────┐
│               Temporal 예외 체계                           │
├─────────────────────────────────────────────────────────┤
│                                                           │
│  ActivityFailure                                         │
│  └── Activity 실행 실패 (재시도 소진)                    │
│      → 보상 실행 후 실패 결과 반환 ✅                    │
│                                                           │
│  CanceledFailure                                         │
│  └── 외부에서 Workflow 취소 요청                         │
│      → 보상 없이 즉시 종료해야 함                        │
│      → catch (Exception)이 가로채면 취소 신호 무시 ⚠️    │
│                                                           │
│  ChildWorkflowFailure                                    │
│  └── Child Workflow 실패                                 │
│                                                           │
│  TimeoutFailure                                          │
│  └── 타임아웃 발생                                       │
│                                                           │
└─────────────────────────────────────────────────────────┘
```

**수정 방향**:

```java
} catch (ActivityFailure e) {
    saga.compensate();
    return OrderResult.failure(e.getCause().getMessage());
} catch (CanceledFailure e) {
    // Workflow 취소 → 보상 실행 후 예외 재전파
    saga.compensate();
    throw e;  // Temporal에게 취소 완료를 알림
}
// generic Exception catch 제거
```

### 3.2 M-9: 보상 Activity의 재시도 설정 부족

**파일**: `orchestrator-temporal/.../OrderWorkflowImpl.java:46-58`

```
┌─────────────────────────────────────────────────────────┐
│            정방향 vs 보상 재시도 전략                       │
├─────────────────────────────────────────────────────────┤
│                                                           │
│  현재: 정방향/보상 모두 같은 설정                         │
│  ┌─────────────────────────────────────────┐             │
│  │ maxAttempts = 3                          │             │
│  │ backoffCoefficient = 2.0                 │             │
│  │ maxInterval = 30s                        │             │
│  └─────────────────────────────────────────┘             │
│                                                           │
│  문제: 보상은 "반드시 성공"해야 함                        │
│  → 3회만 재시도하면 보상 실패 가능                       │
│  → 데이터 불일치 상태로 남음                             │
│                                                           │
│  권장: 보상용 별도 ActivityOptions                        │
│  ┌─────────────────────────────────────────┐             │
│  │ maxAttempts = 10 (또는 무제한)           │             │
│  │ backoffCoefficient = 2.0                 │             │
│  │ maxInterval = 60s                        │             │
│  └─────────────────────────────────────────┘             │
│                                                           │
└─────────────────────────────────────────────────────────┘
```

**수정 방향**: 보상 전용 Activity Stub을 별도 생성하거나, 보상 Activity에 더 관대한 재시도 정책 적용.

---

## 4. Activity 구현 안전성

> -> 관련 문서: [11-activity-design.md](./11-activity-design.md) 1절 "책임 분리"

### 4.1 C-1: @Transactional 내에서 외부 PG 호출

**파일**: `service-payment/.../PaymentService.java`

```
┌─────────────────────────────────────────────────────────┐
│            DB 트랜잭션 + 외부 호출 문제                    │
├─────────────────────────────────────────────────────────┤
│                                                           │
│  @Transactional {                                        │
│    1. payment = getPayment(id)     ← DB Connection 점유  │
│    2. semaphore.acquire()          ← 최대 5초 대기       │
│    3. paymentGateway.approve(...)  ← 외부 PG 호출 (I/O)  │
│    4. payment.approve(txId)        ← 상태 변경           │
│  }                                                        │
│                                                           │
│  문제:                                                   │
│  - 2~3번 동안 DB Connection을 계속 점유                  │
│  - 외부 PG 지연 (수초) → Connection Pool 고갈            │
│  - 10개 동시 요청 × 5초 = Pool 전체 블로킹               │
│                                                           │
│  해결:                                                   │
│  ┌─ 수정 후 ──────────────────────────────────┐          │
│  │ 1. payment = getPayment(id)  (트랜잭션 1)  │          │
│  │ 2. PG 호출 (트랜잭션 밖)                    │          │
│  │ 3. payment.approve(txId)     (트랜잭션 2)  │          │
│  └────────────────────────────────────────────┘          │
│                                                           │
└─────────────────────────────────────────────────────────┘
```

이 패턴은 11-activity-design.md에서 다룬 **"Activity 내부 안전성은 개발자 책임"** 원칙의 대표적 위반 사례다.
Temporal이 Activity를 재시도해주지만, 트랜잭션 범위 설계까지 관리해주지는 않는다.

### 4.2 H-3: OrderActivitiesImpl - 응답 Map 키 불일치

**파일**: `orchestrator-temporal/.../OrderActivitiesImpl.java`

```java
// Activity 구현에서 기대하는 키
Long orderId = ((Number) data.get("orderId")).longValue();   // ← "orderId" 기대
Long paymentId = ((Number) data.get("paymentId")).longValue(); // ← "paymentId" 기대

// 실제 서비스 API 응답 (ApiResponse.getData())
{ "id": 123, "status": "CREATED", ... }   // ← 실제 키는 "id"
```

```
결과: data.get("orderId") → null → NPE
```

**학습 포인트**: Activity 구현에서 외부 서비스 호출 시 **응답 스키마를 정확히 확인**해야 한다.
Contract Testing (Pact)이 이런 불일치를 사전에 잡아준다.

### 4.3 M-3: @Transactional이 protected 메서드에서 무효

**파일**: `service-inventory/.../InventoryService.java`

```java
@Transactional(timeout = 30)
protected void reserveStockInternal(Long productId, int quantity, String sagaId) {
    // Spring AOP 프록시가 protected 메서드를 인터셉트하지 않을 수 있음!
}
```

```
┌─────────────────────────────────────────────────────────┐
│         Spring AOP 프록시와 접근 제어자                    │
├─────────────────────────────────────────────────────────┤
│                                                           │
│  public 메서드    → 프록시 인터셉트 ✅ (보장)             │
│  protected 메서드 → CGLIB는 가능하나 공식 비권장 ⚠️       │
│  private 메서드   → 프록시 인터셉트 불가 ❌                │
│                                                           │
│  self.reserveStockInternal() 호출 패턴 사용 중            │
│  → self-injection으로 AOP 프록시를 통과하려는 의도        │
│  → 하지만 protected에서는 불확실                          │
│                                                           │
│  수정: protected → public                                │
│                                                           │
└─────────────────────────────────────────────────────────┘
```

### 4.4 M-11: REST 호출 시 타임아웃 미설정

**파일**: `orchestrator-temporal/.../OrderActivitiesImpl.java`

```
┌─────────────────────────────────────────────────────────┐
│              타임아웃 계층 구조                             │
├─────────────────────────────────────────────────────────┤
│                                                           │
│  Temporal Activity 타임아웃: 30초                         │
│  └── REST 호출 타임아웃: ??? (미설정 = 무한)              │
│                                                           │
│  시나리오:                                               │
│  1. Activity가 service-order에 REST 호출                  │
│  2. service-order 응답 없음 (행 상태)                     │
│  3. REST 클라이언트는 무한 대기                           │
│  4. 30초 후 Temporal이 Activity 타임아웃 처리             │
│  5. 하지만 스레드는 여전히 REST 응답 대기 중!             │
│                                                           │
│  수정: REST 클라이언트에 타임아웃 설정                    │
│  - Connection Timeout: 3초                               │
│  - Read Timeout: 10초                                    │
│  - Activity 타임아웃(30초)보다 짧아야 함                  │
│                                                           │
└─────────────────────────────────────────────────────────┘
```

---

## 5. 멱등성 인프라 (IdempotencyAspect)

> -> 관련 문서: [11-activity-design.md](./11-activity-design.md) 2절 "멱등성 설계"

### 5.1 C-4: "PROCESSING" 마커 미정리

**파일**: `common/.../IdempotencyAspect.java`

```
┌─────────────────────────────────────────────────────────┐
│           PROCESSING 키 오염 문제                          │
├─────────────────────────────────────────────────────────┤
│                                                           │
│  정상 흐름:                                              │
│  1. Redis: SET key "PROCESSING" (TTL 60s)                │
│  2. 비즈니스 로직 실행                                   │
│  3. Redis: SET key 결과값                                │
│  → 다음 요청: 캐시 히트 → 결과 반환 ✅                   │
│                                                           │
│  예외 발생 시:                                           │
│  1. Redis: SET key "PROCESSING" (TTL 60s)                │
│  2. 비즈니스 로직 실행 → 예외 발생!                      │
│  3. 예외 rethrow (key 삭제 없음)                         │
│  → key = "PROCESSING" 상태로 TTL까지 남아있음            │
│  → 60초간 동일 요청 처리 불가 (키 오염)                  │
│                                                           │
│  수정: finally 블록에서 예외 시 key 삭제                 │
│                                                           │
└─────────────────────────────────────────────────────────┘
```

### 5.2 H-1: 동시 요청이 멱등성을 관통

```
┌─────────────────────────────────────────────────────────┐
│           동시 요청 관통 시나리오                           │
├─────────────────────────────────────────────────────────┤
│                                                           │
│  Thread A: setIfAbsent("PROCESSING") → true (획득)       │
│  Thread B: setIfAbsent("PROCESSING") → false (실패)      │
│                                                           │
│  Thread B: 100ms sleep → 재조회 → "PROCESSING"           │
│  Thread B: 100ms sleep → 재조회 → "PROCESSING"           │
│  Thread B: 100ms sleep → 재조회 → "PROCESSING"           │
│  Thread B: 3회 초과 → 그냥 실행! ← 멱등성 깨짐          │
│                                                           │
│  수정 방향:                                              │
│  - 대기 초과 시 409 Conflict 예외 반환                    │
│  - 또는 분산 락(Redisson) 기반으로 전환                  │
│  → 11-activity-design.md 2.4절의 IdempotencyService 참조 │
│                                                           │
└─────────────────────────────────────────────────────────┘
```

### 5.3 H-2: 직렬화 타입 불일치

```
저장: ResponseEntity<ApiResponse> 전체를 JSON 직렬화
조회: ApiResponse 타입으로 역직렬화 시도
→ 타입 불일치 → 역직렬화 실패 가능

수정: 저장/조회 시 동일한 타입(ApiResponse body만) 사용
```

**학습 포인트**: 멱등성 인프라 자체가 견고하지 않으면, 그 위에 쌓은 모든 멱등성 보장이 무너진다.
11-activity-design.md에서 설계한 `IdempotencyService`(분산 락 + Double-Check)가 더 견고한 접근이다.

---

## 6. Spring 연동 이슈

> -> 관련 문서: [08-spring-integration.md](./08-spring-integration.md)

### 6.1 C-3: getOrderStatus()가 새 Workflow를 시작

**파일**: `orchestrator-temporal/.../OrderController.java`

```java
@GetMapping("/{workflowId}/status")
public OrderResult getOrderStatus(@PathVariable String workflowId) {
    return orderService.processOrder(null);  // ← null로 새 Workflow 시작!
}
```

```
┌─────────────────────────────────────────────────────────┐
│       의도 vs 실제 동작                                    │
├─────────────────────────────────────────────────────────┤
│                                                           │
│  의도: 기존 Workflow의 실행 결과를 조회                    │
│  실제: processOrder(null) → 새 Workflow 시작 시도         │
│        → NullPointerException 또는 의도하지 않은 주문     │
│                                                           │
│  올바른 구현: Temporal Client로 기존 결과 조회             │
│                                                           │
│  WorkflowStub stub = workflowClient                      │
│      .newUntypedWorkflowStub(workflowId);                │
│  OrderResult result = stub.getResult(OrderResult.class); │
│                                                           │
│  또는 Query 사용 (06-signal-query-update.md 참조):       │
│  OrderWorkflow wf = workflowClient                       │
│      .newWorkflowStub(OrderWorkflow.class, workflowId);  │
│  return wf.getStatus();  // @QueryMethod                 │
│                                                           │
└─────────────────────────────────────────────────────────┘
```

### 6.2 M-7: TemporalConfig - Temporal 미기동 시 앱 전체 실패

**파일**: `orchestrator-temporal/.../TemporalConfig.java`

```java
@Bean
public WorkerFactory workerFactory(...) {
    WorkerFactory factory = WorkerFactory.newInstance(client);
    // ... Worker 등록 ...
    factory.start();  // ← @Bean 생성 시점에 호출
    return factory;
}
```

```
문제: Temporal Server가 꺼져 있으면 → factory.start() 실패
     → Bean 생성 실패 → Spring Context 전체 실패
     → 앱 시작 불가

해결 방향:
  - SmartLifecycle 인터페이스 구현 (start/stop 분리)
  - 또는 @EventListener(ApplicationReadyEvent) 사용
  - 재시도 로직 추가
```

> 08-spring-integration.md에서 설명한 Spring 시작 순서(6단계)의 5번째 단계에 해당한다.

### 6.3 M-10: RestClient 타임아웃 미설정 (orchestrator-pure)

**파일**: `orchestrator-pure/.../RestClientConfig.java`

```java
@Bean
public RestClient restClient() {
    return RestClient.builder()
            .requestInterceptor(...)  // traceId 전파만 설정
            .build();                 // 타임아웃 없음!
}
```

하류 서비스가 응답하지 않으면 스레드가 무한 대기한다.
Resilience4j `@CircuitBreaker`는 타임아웃을 강제하지 않는다 (실패율만 추적).

---

## 7. 서비스 도메인 로직

### 7.1 M-1: RedisStreamConfig - Consumer Group 미생성

**파일**: `service-notification/.../RedisStreamConfig.java`

```java
@PostConstruct
public void createConsumerGroup() {
    try {
        redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), consumerGroup);
    } catch (RedisSystemException e) {
        if (/* "no such key" */) {
            log.info("Stream이 아직 존재하지 않음. 첫 메시지 발행 시 생성됩니다.");
            // ← 틀림! XADD는 Stream만 생성, Consumer Group은 별도 생성 필요
        }
    }
}
```

**수정**: `MKSTREAM` 옵션을 사용하여 Stream이 없어도 Consumer Group을 함께 생성.

### 7.2 M-2: OrderItem.getSubtotal() - NPE

**파일**: `service-order/.../entity/OrderItem.java`

```java
public BigDecimal getSubtotal() {
    return unitPrice.multiply(BigDecimal.valueOf(quantity));
    //     ↑ unitPrice가 null이면 NPE
}
```

Builder에서 `quantity`는 기본값(1)이 있지만, `unitPrice`는 기본값이 없다.

### 7.3 M-4: Inventory.cancelReservation() - 음수 무시

**파일**: `service-inventory/.../entity/Inventory.java`

```java
public void cancelReservation(int amount) {
    this.reservedQuantity -= amount;
    if (this.reservedQuantity < 0) {
        this.reservedQuantity = 0;  // 조용히 0으로 클램프
    }
}
```

`confirmReservation()`은 음수 시 예외를 던지는데, `cancelReservation()`은 조용히 무시한다.
이는 11-activity-design.md 4.3절의 Semantic Lock 엔티티 구현과 불일치하는 패턴이다.
취소 금액이 예약 금액과 다르면 데이터 정합성 문제가 숨겨진다.

### 7.4 M-5, M-6: Outbox 스케줄러 / Saga 소유권 검증

| 이슈 | 파일 | 설명 |
|------|------|------|
| M-5 | `OutboxSchedulers` | Detached 엔티티로 retry 판단 → stale 데이터 |
| M-6 | `Inventory` | `sagaId == null`이면 소유권 검증 통과 |
| M-12 | orchestrator-pure 클라이언트 | `getData()` null 체크 없이 사용 → NPE |

---

## 8. 개선 권장 사항 (LOW)

| # | 파일 | 이슈 | 수정 방향 |
|---|------|------|-----------|
| L-1 | `FakePaymentGateway` | `Random` → `ThreadLocalRandom` | 동시성 안전 |
| L-2 | `OutboxService` | `moveExhaustedEventsToDlq` 카운트 부풀림 | 실제 이동 수만 카운트 |
| L-3 | `Order.updateStatus()` | 상태 전이 검증 없음 | FSM 패턴 적용 |
| L-4 | `PaymentService` | `initSemaphore()` 설정 변경 무시 | 현재값과 비교 후 업데이트 |
| L-5 | `OrderService` | `createOrder()` 이벤트 totalAmount=0 | 실제 금액 계산 후 설정 |
| L-6 | orchestrator-pure | `@Retry`+`@CircuitBreaker` 순서 | 의도에 맞게 순서 조정 |
| L-7 | `RequestIdFilter` | `MDC.clear()` 전체 삭제 | `MDC.remove()` 개별 삭제 |
| L-8 | `RequestIdFilter` | 8자 UUID → 충돌 가능 | 12자 이상 또는 전체 UUID |
| L-9 | `RedisStreamConfig` | `getCause().getMessage()` NPE | null 안전 처리 |

---

## 9. 전체 요약

### 9.1 심각도별 분포

```
┌─────────────────────────────────────────────────────────┐
│                   이슈 분류 매트릭스                        │
├───────────────┬─────────┬──────┬────────┬───────────────┤
│ 카테고리       │ CRITICAL│ HIGH │ MEDIUM │ LOW           │
├───────────────┼─────────┼──────┼────────┼───────────────┤
│ Saga 보상      │ C-2     │      │ M-8,9  │ M-13          │
│ Activity 안전  │ C-1     │ H-3  │ M-3,11 │               │
│ 멱등성 인프라  │ C-4     │ H-1,2│        │               │
│ Spring 연동    │ C-3     │      │ M-7,10 │               │
│ 도메인 로직    │         │      │M-1~6,12│ L-1~9         │
├───────────────┼─────────┼──────┼────────┼───────────────┤
│ 합계           │ 4       │ 3    │ 13     │ 9             │
└───────────────┴─────────┴──────┴────────┴───────────────┘
```

### 9.2 핵심 교훈 5가지

```
┌─────────────────────────────────────────────────────────┐
│              코드 리뷰 핵심 교훈 5가지                     │
├─────────────────────────────────────────────────────────┤
│                                                           │
│  1. 보상은 어떤 상태에서든 실패하면 안 된다               │
│     → refund()가 PENDING에서 예외 던짐 = Saga 깨짐       │
│                                                           │
│  2. Temporal이 해주는 것 / 개발자가 할 것 구분 필수       │
│     → 재시도는 Temporal, 트랜잭션 범위는 개발자           │
│                                                           │
│  3. 멱등성 인프라 자체가 견고해야 한다                    │
│     → IdempotencyAspect 3개 버그 = 멱등성 전체 무효      │
│                                                           │
│  4. 타임아웃은 모든 계층에 설정해야 한다                  │
│     → Activity 30초 + REST 무한 = 스레드 누수             │
│                                                           │
│  5. API 응답 스키마를 가정하지 말고 검증하라              │
│     → Map 키 불일치 = 런타임 NPE                         │
│                                                           │
└─────────────────────────────────────────────────────────┘
```

### 9.3 수정 우선순위

```
┌─ 즉시 수정 (Temporal 학습과 직결) ───────────────────────┐
│ C-2  Payment.refund() 멱등성    → Saga 보상 원칙          │
│ C-3  getOrderStatus() 구현      → Temporal Query/Result   │
│ C-4  IdempotencyAspect 정리     → 멱등성 인프라 견고성    │
│ H-3  Map 키 불일치              → Activity 구현 정확성    │
└──────────────────────────────────────────────────────────┘

┌─ 학습 완료 후 수정 ─────────────────────────────────────┐
│ C-1  트랜잭션 분리   │ M-7  TemporalConfig 시작 처리    │
│ H-1  동시 요청 관통  │ M-8  CanceledFailure 처리        │
│ H-2  직렬화 불일치   │ M-9  보상 재시도 설정             │
│ M-3  접근 제어자     │ M-13 confirm 보상 등록            │
└──────────────────────────────────────────────────────────┘
```

---

> **다음 단계**: 이 문서의 이슈들을 실제로 수정하면서 Temporal 패턴을 체득합니다.
> 특히 C-2(보상 멱등성), C-3(Workflow 조회), M-8(예외 체계)은
> Phase 3 학습 내용을 코드로 적용하는 좋은 실습 과제입니다.
