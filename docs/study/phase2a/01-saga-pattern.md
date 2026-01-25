# Saga 패턴 - 분산 트랜잭션 관리

## 이 문서에서 배우는 것

- 분산 트랜잭션의 문제점 이해
- Saga 패턴의 개념과 동작 원리
- Orchestration vs Choreography 비교
- 보상 트랜잭션 설계 방법
- 실제 구현 예시

---

## 1. 분산 트랜잭션의 문제

### 모놀리식에서의 트랜잭션

하나의 DB를 사용하는 모놀리식에서는 ACID 트랜잭션이 간단합니다:

```java
@Transactional
public void createOrder(OrderRequest request) {
    // 모두 같은 DB, 하나의 트랜잭션
    Order order = orderRepository.save(new Order(request));
    inventoryRepository.decreaseStock(request.getProductId(), request.getQuantity());
    paymentRepository.processPayment(request.getPaymentInfo());

    // 어디서든 예외 발생 → 전체 ROLLBACK
}
```

```
┌─────────────────────────────────────────────────┐
│                 단일 트랜잭션                    │
│  BEGIN TRANSACTION                              │
│    1. INSERT INTO orders ...      ✓            │
│    2. UPDATE inventory SET ...    ✓            │
│    3. INSERT INTO payments ...    ✗ (실패!)    │
│  ROLLBACK  ← 1, 2도 모두 취소됨                │
└─────────────────────────────────────────────────┘
```

### MSA에서의 문제

각 서비스가 독립적인 DB를 가지면 **하나의 트랜잭션으로 묶을 수 없습니다**:

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Order     │     │  Inventory  │     │   Payment   │
│   Service   │     │   Service   │     │   Service   │
└──────┬──────┘     └──────┬──────┘     └──────┬──────┘
       │                   │                   │
   [Order DB]          [Inv DB]           [Payment DB]

서로 다른 DB → @Transactional이 적용되지 않음!
```

**시나리오: 결제 실패**

```
1. Order Service: 주문 생성 (COMMIT) ✓
2. Inventory Service: 재고 차감 (COMMIT) ✓
3. Payment Service: 결제 실패! ✗

→ 1, 2는 이미 커밋됨... 어떻게 롤백하지? 😱
```

---

## 2. Saga 패턴이란?

### 정의

**Saga**는 여러 로컬 트랜잭션의 시퀀스로, 각 트랜잭션은 자체 데이터를 업데이트하고 다음 트랜잭션을 트리거합니다.

```
Saga = 로컬 트랜잭션들의 연속 + 보상 트랜잭션

┌────────────────────────────────────────────────────────────┐
│                        Saga                                 │
│                                                             │
│  T1 ────▶ T2 ────▶ T3 ────▶ T4                            │
│  (주문)    (재고)    (결제)    (확정)                        │
│                                                             │
│  실패 시:                                                   │
│  C3 ◀──── C2 ◀──── C1                                     │
│  (환불)    (복구)    (취소)    ← 보상 트랜잭션              │
└────────────────────────────────────────────────────────────┘
```

### 핵심 개념

| 용어 | 설명 |
|------|------|
| **로컬 트랜잭션 (T)** | 각 서비스 내에서 수행되는 개별 트랜잭션 |
| **보상 트랜잭션 (C)** | 로컬 트랜잭션을 취소하는 역방향 트랜잭션 |
| **Saga 조율자** | Saga의 진행을 관리하는 컴포넌트 |

### 보상 트랜잭션 예시

| 로컬 트랜잭션 | 보상 트랜잭션 |
|--------------|--------------|
| 주문 생성 | 주문 취소 |
| 재고 차감 | 재고 복구 |
| 결제 처리 | 결제 환불 |
| 포인트 적립 | 포인트 차감 |

---

## 3. Saga 구현 방식

### 3.1 Choreography (분산 방식)

각 서비스가 이벤트를 발행하고, 다른 서비스가 구독하여 처리:

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Order     │     │  Inventory  │     │   Payment   │
│   Service   │     │   Service   │     │   Service   │
└──────┬──────┘     └──────┬──────┘     └──────┬──────┘
       │                   │                   │
       │  OrderCreated     │                   │
       │──────────────────▶│                   │
       │                   │  StockReserved    │
       │                   │──────────────────▶│
       │                   │                   │  PaymentCompleted
       │◀──────────────────│◀──────────────────│
       │  (이벤트 구독)
```

**특징**:
- 중앙 조율자 없음
- 서비스 간 느슨한 결합
- 흐름 파악이 어려움
- 디버깅이 복잡

### 3.2 Orchestration (중앙 집중 방식)

**오케스트레이터**가 Saga의 전체 흐름을 제어:

```
                    ┌─────────────────────┐
                    │    Orchestrator     │
                    │   (Saga 조율자)      │
                    └──────────┬──────────┘
                               │
         ┌─────────────────────┼─────────────────────┐
         │                     │                     │
         ▼                     ▼                     ▼
┌─────────────┐       ┌─────────────┐       ┌─────────────┐
│   Order     │       │  Inventory  │       │   Payment   │
│   Service   │       │   Service   │       │   Service   │
└─────────────┘       └─────────────┘       └─────────────┘
```

**특징**:
- 중앙에서 흐름 제어
- 흐름 파악이 쉬움
- 디버깅이 용이
- 오케스트레이터가 단일 장애점(SPOF)이 될 수 있음

### 비교 표

| 항목 | Choreography | Orchestration |
|------|--------------|---------------|
| 결합도 | 느슨함 | 오케스트레이터에 의존 |
| 복잡도 | 서비스 증가 시 복잡 | 상대적으로 단순 |
| 디버깅 | 어려움 | 쉬움 |
| 흐름 파악 | 분산되어 어려움 | 한 곳에서 파악 |
| 단일 장애점 | 없음 | 오케스트레이터 |
| Temporal 전환 | 어려움 | 자연스러움 |

**우리 프로젝트 선택: Orchestration**
- Temporal이 Orchestration 방식
- 학습 목적에 적합 (흐름 이해 쉬움)

### 3.3 실무에서는 어떤 방식이 더 대중적인가?

**결론: Orchestration이 압도적으로 많이 사용됨 (약 70-80%)**

```
┌─────────────────────────────────────────────────────────────────┐
│              업계 사용 비율 (추정)                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Orchestration  ████████████████████████████████████  70-80%    │
│  Choreography   ██████████████                        20-30%    │
│                                                                  │
│  주 사용처:                                                      │
│  - Orchestration: 대부분의 엔터프라이즈 시스템                   │
│  - Choreography: 단순한 이벤트 기반 시스템                       │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**Orchestration이 선호되는 이유:**

| 항목 | Orchestration 장점 | Choreography 단점 |
|------|-------------------|-------------------|
| 가독성 | 흐름이 한 곳에서 명확하게 보임 | 서비스가 많아지면 이벤트 추적 어려움 |
| 디버깅 | 모니터링이 쉬움 | 전체 흐름 파악 어려움 (스파게티) |
| 보상 처리 | 보상 트랜잭션 구현이 직관적 | 순환 의존성 발생 가능 |
| 확장성 | 새로운 서비스 추가가 간단 | 실패 시 보상 트랜잭션 복잡 |
| 도구 지원 | Temporal, Camunda 등 강력한 지원 | 별도 도구 부족 |

**각 패턴의 적합한 상황:**

```
Orchestration 추천:
├── 복잡한 비즈니스 로직 (5개 이상 서비스 참여)
├── 명확한 트랜잭션 흐름 필요
├── 엄격한 보상 처리 필요 (금융, 결제)
├── 운영/모니터링이 중요한 경우
└── 예: 주문 → 결제 → 재고 → 배송

Choreography 추천:
├── 단순한 이벤트 전파 (2-3개 서비스)
├── 서비스 간 느슨한 결합이 최우선
├── 단방향 이벤트 흐름
└── 예: 회원가입 → 환영 이메일 발송
```

**실제 기업들의 선택:**

| 기업 | 선택 | 사용 도구 |
|------|------|----------|
| Netflix | Orchestration | Conductor |
| Uber | Orchestration | Cadence → Temporal |
| Airbnb | Orchestration | 자체 개발 |
| Amazon | 혼합 | Step Functions + EventBridge |

**현실적인 권장사항:**

```
📌 일반적인 권장:
   └── 처음 시작 → Orchestration (Temporal 추천)

📌 예외적으로 Choreography:
   └── 정말 단순한 이벤트 전파만 필요할 때

📌 하이브리드 접근:
   └── 핵심 트랜잭션: Orchestration
   └── 부가 기능(알림, 로깅): Choreography
```

---

## 4. Orchestration 상세 설계

### 4.1 주문 Saga 흐름

```
[정상 흐름]

Orchestrator
    │
    ├──[1]─▶ Order Service: createOrder()
    │        └─▶ 주문 생성 (PENDING)
    │
    ├──[2]─▶ Inventory Service: reserveStock()
    │        └─▶ 재고 예약
    │
    ├──[3]─▶ Payment Service: processPayment()
    │        └─▶ 결제 처리
    │
    └──[4]─▶ Order Service: confirmOrder()
             └─▶ 주문 확정 (CONFIRMED)
```

```
[실패 흐름 - 결제 실패 시]

Orchestrator
    │
    ├──[1]─▶ Order Service: createOrder() ✓
    │
    ├──[2]─▶ Inventory Service: reserveStock() ✓
    │
    ├──[3]─▶ Payment Service: processPayment() ✗ (실패!)
    │
    │  ◀── 보상 트랜잭션 시작 ──▶
    │
    ├──[C2]─▶ Inventory Service: cancelReservation()
    │         └─▶ 재고 복구
    │
    └──[C1]─▶ Order Service: cancelOrder()
              └─▶ 주문 취소 (CANCELLED)
```

### 4.2 Saga 상태 머신

```
                    ┌───────────────┐
                    │   STARTED     │
                    └───────┬───────┘
                            │ createOrder()
                            ▼
                    ┌───────────────┐
                    │ ORDER_CREATED │
                    └───────┬───────┘
                            │ reserveStock()
              ┌─────────────┴─────────────┐
              │ (성공)                     │ (실패)
              ▼                            ▼
      ┌───────────────┐           ┌───────────────┐
      │ STOCK_RESERVED│           │ORDER_CANCELLED│
      └───────┬───────┘           └───────────────┘
              │ processPayment()
              │
    ┌─────────┴─────────┐
    │ (성공)            │ (실패)
    ▼                   ▼
┌──────────────┐   ┌──────────────────┐
│PAYMENT_DONE  │   │ COMPENSATING...  │
└──────┬───────┘   └────────┬─────────┘
       │                    │ cancelReservation()
       │                    ▼
       │            ┌──────────────────┐
       │            │ STOCK_RELEASED   │
       │            └────────┬─────────┘
       │                     │ cancelOrder()
       │                     ▼
       │            ┌──────────────────┐
       │            │ ORDER_CANCELLED  │
       │            └──────────────────┘
       │ confirmOrder()
       ▼
┌───────────────┐
│   COMPLETED   │
└───────────────┘
```

---

## 5. 코드 구현 예시

### 5.1 Saga 오케스트레이터 인터페이스

```java
// common/src/main/java/com/example/common/saga/OrderSagaOrchestrator.java
package com.example.common.saga;

public interface OrderSagaOrchestrator {

    /**
     * 주문 Saga 실행
     * @param request 주문 요청
     * @return Saga 실행 결과
     */
    OrderSagaResult execute(OrderSagaRequest request);
}
```

### 5.2 순수 구현 오케스트레이터

```java
// orchestrator-pure/src/main/java/com/example/orchestrator/PureSagaOrchestrator.java
package com.example.orchestrator;

import com.example.common.saga.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PureSagaOrchestrator implements OrderSagaOrchestrator {

    private final OrderServiceClient orderClient;
    private final InventoryServiceClient inventoryClient;
    private final PaymentServiceClient paymentClient;

    @Override
    public OrderSagaResult execute(OrderSagaRequest request) {
        log.info("Saga 시작: {}", request);

        String orderId = null;
        String reservationId = null;
        String paymentId = null;

        try {
            // Step 1: 주문 생성
            log.info("Step 1: 주문 생성");
            OrderResponse order = orderClient.createOrder(request.toOrderRequest());
            orderId = order.orderId();
            log.info("주문 생성 완료: {}", orderId);

            // Step 2: 재고 예약
            log.info("Step 2: 재고 예약");
            ReservationResponse reservation = inventoryClient.reserveStock(
                new ReservationRequest(orderId, request.productId(), request.quantity())
            );
            reservationId = reservation.reservationId();
            log.info("재고 예약 완료: {}", reservationId);

            // Step 3: 결제 처리
            log.info("Step 3: 결제 처리");
            PaymentResponse payment = paymentClient.processPayment(
                new PaymentRequest(orderId, request.amount(), request.customerId())
            );
            paymentId = payment.paymentId();
            log.info("결제 완료: {}", paymentId);

            // Step 4: 주문 확정
            log.info("Step 4: 주문 확정");
            orderClient.confirmOrder(orderId);
            log.info("주문 확정 완료");

            return OrderSagaResult.success(orderId, paymentId);

        } catch (Exception e) {
            log.error("Saga 실패, 보상 트랜잭션 시작: {}", e.getMessage());
            compensate(orderId, reservationId, paymentId);
            return OrderSagaResult.failure(e.getMessage());
        }
    }

    /**
     * 보상 트랜잭션 실행
     * 역순으로 롤백 수행
     */
    private void compensate(String orderId, String reservationId, String paymentId) {
        // 결제 환불 (결제가 완료된 경우)
        if (paymentId != null) {
            try {
                log.info("보상: 결제 환불 - {}", paymentId);
                paymentClient.refundPayment(paymentId);
            } catch (Exception e) {
                log.error("결제 환불 실패: {}", e.getMessage());
                // 실패해도 계속 진행 (최대한 보상 시도)
            }
        }

        // 재고 복구 (예약이 완료된 경우)
        if (reservationId != null) {
            try {
                log.info("보상: 재고 복구 - {}", reservationId);
                inventoryClient.cancelReservation(reservationId);
            } catch (Exception e) {
                log.error("재고 복구 실패: {}", e.getMessage());
            }
        }

        // 주문 취소 (주문이 생성된 경우)
        if (orderId != null) {
            try {
                log.info("보상: 주문 취소 - {}", orderId);
                orderClient.cancelOrder(orderId);
            } catch (Exception e) {
                log.error("주문 취소 실패: {}", e.getMessage());
            }
        }
    }
}
```

### 5.3 서비스 클라이언트 (REST)

```java
// orchestrator-pure/src/main/java/com/example/orchestrator/client/OrderServiceClient.java
package com.example.orchestrator.client;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
public class OrderServiceClient {

    private final RestTemplate restTemplate;
    private static final String BASE_URL = "http://localhost:8081";

    public OrderResponse createOrder(OrderRequest request) {
        return restTemplate.postForObject(
            BASE_URL + "/orders",
            request,
            OrderResponse.class
        );
    }

    public void confirmOrder(String orderId) {
        restTemplate.put(BASE_URL + "/orders/" + orderId + "/confirm", null);
    }

    public void cancelOrder(String orderId) {
        restTemplate.put(BASE_URL + "/orders/" + orderId + "/cancel", null);
    }
}
```

### 5.4 주문 서비스 API

```java
// service-order/src/main/java/com/example/order/controller/OrderController.java
package com.example.order.controller;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody OrderRequest request) {
        Order order = orderService.createOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(OrderResponse.from(order));
    }

    @PutMapping("/{orderId}/confirm")
    public ResponseEntity<Void> confirmOrder(@PathVariable String orderId) {
        orderService.confirmOrder(orderId);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{orderId}/cancel")
    public ResponseEntity<Void> cancelOrder(@PathVariable String orderId) {
        orderService.cancelOrder(orderId);
        return ResponseEntity.ok().build();
    }
}
```

---

## 6. 보상 트랜잭션 설계 원칙

### 6.1 멱등성 (Idempotency)

보상 트랜잭션은 **여러 번 호출해도 같은 결과**를 보장해야 합니다:

```java
// ❌ 잘못된 예: 멱등성 없음
public void cancelReservation(String reservationId) {
    Reservation r = findById(reservationId);
    inventoryRepository.increaseStock(r.getProductId(), r.getQuantity());
    // 두 번 호출하면 재고가 두 배로 복구됨!
}

// ✓ 올바른 예: 멱등성 있음
public void cancelReservation(String reservationId) {
    Reservation r = findById(reservationId);
    if (r.getStatus() == CANCELLED) {
        return;  // 이미 취소됨, 아무것도 안 함
    }
    inventoryRepository.increaseStock(r.getProductId(), r.getQuantity());
    r.setStatus(CANCELLED);
    reservationRepository.save(r);
}
```

### 6.2 역순 실행

보상 트랜잭션은 **정상 흐름의 역순**으로 실행:

```
정상: T1 → T2 → T3 → T4
보상: C4 → C3 → C2 → C1 (역순!)

왜? 나중에 실행된 것이 먼저 실행된 것에 의존할 수 있음
```

### 6.3 실패 허용

보상 트랜잭션 중 일부가 실패해도 **나머지는 계속 진행**:

```java
private void compensate(...) {
    // 실패해도 다음 보상 계속 진행
    try { refundPayment(); } catch (Exception e) { log.error(e); }
    try { cancelReservation(); } catch (Exception e) { log.error(e); }
    try { cancelOrder(); } catch (Exception e) { log.error(e); }
}
```

### 6.4 시맨틱 롤백

물리적 롤백이 아닌 **비즈니스 의미의 롤백**:

```
[결제 취소의 경우]

❌ 물리적 롤백: DELETE FROM payments WHERE id = ?
  → 데이터 유실, 감사 추적 불가

✓ 시맨틱 롤백: UPDATE payments SET status = 'REFUNDED' WHERE id = ?
  → 취소 이력 보존, 감사 추적 가능
```

---

## 7. 에러 처리 전략

### 7.1 재시도 가능한 에러

```java
// 네트워크 일시 장애, 타임아웃 등
try {
    inventoryClient.reserveStock(request);
} catch (ResourceAccessException e) {
    // 재시도 로직 (Resilience4j 활용)
    return retry(() -> inventoryClient.reserveStock(request));
}
```

### 7.2 재시도 불가능한 에러

```java
// 비즈니스 에러: 재고 부족, 잔액 부족 등
try {
    inventoryClient.reserveStock(request);
} catch (InsufficientStockException e) {
    // 재시도 무의미, 바로 보상 트랜잭션
    compensate(orderId, null, null);
    throw e;
}
```

### 7.3 Saga 상태 저장

장애 복구를 위해 Saga 상태를 DB에 저장:

```java
@Entity
public class SagaState {
    @Id
    private String sagaId;

    @Enumerated(EnumType.STRING)
    private SagaStatus status;  // STARTED, COMPENSATING, COMPLETED, FAILED

    private String orderId;
    private String reservationId;
    private String paymentId;

    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

---

## 8. 실습 과제

1. `OrderSagaOrchestrator` 인터페이스 정의
2. `PureSagaOrchestrator` 구현 (정상 흐름)
3. 보상 트랜잭션 로직 추가
4. 각 서비스에 취소/환불 API 구현
5. 결제 실패 시나리오 테스트

---

## 9. MyBatis로 Saga 상태 관리

### 학습 목표

JPA는 Entity 상태 변경을 자동으로 추적합니다. MyBatis로 직접 SQL을 작성하면:
- Saga 상태 전이 쿼리의 원리 이해
- 조건부 UPDATE로 동시성 제어 학습
- 복구를 위한 조회 쿼리 작성

### 9.1 Saga 상태 테이블 설계

```sql
CREATE TABLE saga_state (
    saga_id         VARCHAR(36) PRIMARY KEY,
    saga_type       VARCHAR(100) NOT NULL,        -- ORDER_SAGA, PAYMENT_SAGA 등
    status          VARCHAR(50) NOT NULL,         -- STARTED, COMPENSATING, COMPLETED, FAILED
    current_step    INT NOT NULL DEFAULT 0,       -- 현재 진행 단계

    -- 각 단계별 결과 저장
    order_id        VARCHAR(36),
    reservation_id  VARCHAR(36),
    payment_id      VARCHAR(36),

    -- 메타 정보
    request_payload JSON,                          -- 원본 요청 데이터
    failure_reason  TEXT,
    version         INT NOT NULL DEFAULT 0,        -- 낙관적 락

    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_status (status),
    INDEX idx_created (created_at)
);
```

### 9.2 MyBatis Mapper 인터페이스

```java
@Mapper
public interface SagaStateMapper {

    // Saga 생성
    void insert(SagaState sagaState);

    // 상태 업데이트 (낙관적 락)
    int updateStatus(@Param("sagaId") String sagaId,
                     @Param("newStatus") String newStatus,
                     @Param("expectedVersion") int expectedVersion);

    // 단계별 결과 저장
    int updateStepResult(@Param("sagaId") String sagaId,
                         @Param("step") int step,
                         @Param("resultColumn") String resultColumn,
                         @Param("resultValue") String resultValue,
                         @Param("expectedVersion") int expectedVersion);

    // 실패 정보 기록
    int markAsFailed(@Param("sagaId") String sagaId,
                     @Param("failureReason") String failureReason,
                     @Param("expectedVersion") int expectedVersion);

    // 복구 대상 조회 (STARTED 상태로 오래 남은 것)
    List<SagaState> findStuckSagas(@Param("status") String status,
                                    @Param("olderThan") LocalDateTime olderThan,
                                    @Param("limit") int limit);

    // ID로 조회
    SagaState findById(@Param("sagaId") String sagaId);

    // 상태별 통계
    List<SagaStatistics> getStatistics();
}
```

### 9.3 MyBatis XML Mapper

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">

<mapper namespace="com.example.saga.mapper.SagaStateMapper">

    <resultMap id="SagaStateResultMap" type="SagaState">
        <id property="sagaId" column="saga_id"/>
        <result property="sagaType" column="saga_type"/>
        <result property="status" column="status"/>
        <result property="currentStep" column="current_step"/>
        <result property="orderId" column="order_id"/>
        <result property="reservationId" column="reservation_id"/>
        <result property="paymentId" column="payment_id"/>
        <result property="requestPayload" column="request_payload"/>
        <result property="failureReason" column="failure_reason"/>
        <result property="version" column="version"/>
        <result property="createdAt" column="created_at"/>
        <result property="updatedAt" column="updated_at"/>
    </resultMap>

    <!-- ===== Saga 생성 ===== -->
    <insert id="insert">
        INSERT INTO saga_state (
            saga_id,
            saga_type,
            status,
            current_step,
            request_payload,
            version,
            created_at
        ) VALUES (
            #{sagaId},
            #{sagaType},
            'STARTED',
            0,
            #{requestPayload},
            0,
            NOW()
        )
    </insert>

    <!-- ===== 상태 업데이트 (낙관적 락) ===== -->
    <!--
        핵심 패턴: WHERE version = ?
        - 동시에 여러 요청이 상태를 변경하려 할 때
        - 먼저 변경한 요청만 성공 (affected rows = 1)
        - 나중 요청은 실패 (affected rows = 0)
    -->
    <update id="updateStatus">
        UPDATE saga_state
        SET status = #{newStatus},
            version = version + 1,
            updated_at = NOW()
        WHERE saga_id = #{sagaId}
          AND version = #{expectedVersion}
    </update>

    <!-- ===== 단계별 결과 저장 ===== -->
    <!--
        동적 컬럼 업데이트:
        - 각 단계(step)에서 결과값을 저장
        - 보상 트랜잭션 시 이 값들을 사용
    -->
    <update id="updateStepResult">
        UPDATE saga_state
        SET current_step = #{step},
            <!-- 동적 컬럼 설정 -->
            <choose>
                <when test="resultColumn == 'order_id'">
                    order_id = #{resultValue},
                </when>
                <when test="resultColumn == 'reservation_id'">
                    reservation_id = #{resultValue},
                </when>
                <when test="resultColumn == 'payment_id'">
                    payment_id = #{resultValue},
                </when>
            </choose>
            version = version + 1,
            updated_at = NOW()
        WHERE saga_id = #{sagaId}
          AND version = #{expectedVersion}
    </update>

    <!-- ===== 실패 처리 ===== -->
    <update id="markAsFailed">
        UPDATE saga_state
        SET status = 'FAILED',
            failure_reason = #{failureReason},
            version = version + 1,
            updated_at = NOW()
        WHERE saga_id = #{sagaId}
          AND version = #{expectedVersion}
    </update>

    <!-- ===== 보상 시작 ===== -->
    <update id="startCompensation">
        UPDATE saga_state
        SET status = 'COMPENSATING',
            version = version + 1,
            updated_at = NOW()
        WHERE saga_id = #{sagaId}
          AND status = 'STARTED'
          AND version = #{expectedVersion}
    </update>

    <!-- ===== 복구 대상 조회 ===== -->
    <!--
        장애 복구 시나리오:
        - STARTED 상태로 10분 이상 남은 Saga
        - 서버 재시작 시 이 쿼리로 복구 대상 찾음
    -->
    <select id="findStuckSagas" resultMap="SagaStateResultMap">
        SELECT saga_id, saga_type, status, current_step,
               order_id, reservation_id, payment_id,
               request_payload, failure_reason, version,
               created_at, updated_at
        FROM saga_state
        WHERE status = #{status}
          AND updated_at &lt; #{olderThan}
        ORDER BY created_at ASC
        LIMIT #{limit}
        FOR UPDATE SKIP LOCKED
    </select>

    <!-- ===== ID로 조회 ===== -->
    <select id="findById" resultMap="SagaStateResultMap">
        SELECT saga_id, saga_type, status, current_step,
               order_id, reservation_id, payment_id,
               request_payload, failure_reason, version,
               created_at, updated_at
        FROM saga_state
        WHERE saga_id = #{sagaId}
    </select>

    <!-- ===== 상태별 통계 ===== -->
    <select id="getStatistics" resultType="map">
        SELECT status,
               COUNT(*) as count,
               MIN(created_at) as oldest,
               MAX(created_at) as newest
        FROM saga_state
        GROUP BY status
    </select>

</mapper>
```

### 9.4 MyBatis 기반 Saga 오케스트레이터

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class MyBatisSagaOrchestrator implements OrderSagaOrchestrator {

    private final SagaStateMapper sagaStateMapper;
    private final OrderServiceClient orderClient;
    private final InventoryServiceClient inventoryClient;
    private final PaymentServiceClient paymentClient;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public OrderSagaResult execute(OrderSagaRequest request) {
        String sagaId = UUID.randomUUID().toString();
        log.info("Saga 시작: sagaId={}", sagaId);

        // 1. Saga 상태 생성
        SagaState saga = createSaga(sagaId, request);

        try {
            // 2. Step 1: 주문 생성
            String orderId = executeStep1(saga, request);

            // 3. Step 2: 재고 예약
            String reservationId = executeStep2(saga, orderId, request);

            // 4. Step 3: 결제 처리
            String paymentId = executeStep3(saga, orderId, request);

            // 5. Step 4: 주문 확정
            executeStep4(saga, orderId);

            // 6. 완료 처리
            completeWithOptimisticLock(saga);

            return OrderSagaResult.success(orderId, paymentId);

        } catch (Exception e) {
            log.error("Saga 실패: sagaId={}, error={}", sagaId, e.getMessage());
            compensate(saga);
            return OrderSagaResult.failure(e.getMessage());
        }
    }

    private SagaState createSaga(String sagaId, OrderSagaRequest request) {
        try {
            SagaState saga = SagaState.builder()
                    .sagaId(sagaId)
                    .sagaType("ORDER_SAGA")
                    .requestPayload(objectMapper.writeValueAsString(request))
                    .build();
            sagaStateMapper.insert(saga);
            return saga;
        } catch (JsonProcessingException e) {
            throw new SagaException("Failed to serialize request", e);
        }
    }

    /**
     * 낙관적 락을 사용한 단계 결과 저장
     * 동시 요청 시 먼저 처리한 요청만 성공
     */
    private void saveStepResult(SagaState saga, int step,
                                 String column, String value) {
        int affected = sagaStateMapper.updateStepResult(
                saga.getSagaId(),
                step,
                column,
                value,
                saga.getVersion()
        );

        if (affected == 0) {
            throw new OptimisticLockException(
                    "Saga state was modified by another process");
        }

        // 버전 증가 반영
        saga.incrementVersion();
    }

    private String executeStep1(SagaState saga, OrderSagaRequest request) {
        log.info("Step 1: 주문 생성");
        OrderResponse order = orderClient.createOrder(request.toOrderRequest());
        saveStepResult(saga, 1, "order_id", order.orderId());
        saga.setOrderId(order.orderId());
        return order.orderId();
    }

    private String executeStep2(SagaState saga, String orderId,
                                 OrderSagaRequest request) {
        log.info("Step 2: 재고 예약");
        ReservationResponse reservation = inventoryClient.reserveStock(
                new ReservationRequest(orderId, request.productId(), request.quantity())
        );
        saveStepResult(saga, 2, "reservation_id", reservation.reservationId());
        saga.setReservationId(reservation.reservationId());
        return reservation.reservationId();
    }

    private String executeStep3(SagaState saga, String orderId,
                                 OrderSagaRequest request) {
        log.info("Step 3: 결제 처리");
        PaymentResponse payment = paymentClient.processPayment(
                new PaymentRequest(orderId, request.amount(), request.customerId())
        );
        saveStepResult(saga, 3, "payment_id", payment.paymentId());
        saga.setPaymentId(payment.paymentId());
        return payment.paymentId();
    }

    private void executeStep4(SagaState saga, String orderId) {
        log.info("Step 4: 주문 확정");
        orderClient.confirmOrder(orderId);
        saveStepResult(saga, 4, "order_id", orderId); // 완료 단계 기록
    }

    private void completeWithOptimisticLock(SagaState saga) {
        int affected = sagaStateMapper.updateStatus(
                saga.getSagaId(),
                "COMPLETED",
                saga.getVersion()
        );

        if (affected == 0) {
            log.warn("Failed to mark saga as completed (concurrent update)");
        }
    }

    /**
     * 보상 트랜잭션 실행
     * 저장된 단계별 결과를 사용하여 역순으로 롤백
     */
    private void compensate(SagaState saga) {
        log.info("보상 트랜잭션 시작: sagaId={}", saga.getSagaId());

        // 보상 시작 상태로 변경
        sagaStateMapper.updateStatus(
                saga.getSagaId(),
                "COMPENSATING",
                saga.getVersion()
        );
        saga.incrementVersion();

        // 역순 보상 (저장된 ID 사용)
        if (saga.getPaymentId() != null) {
            try {
                log.info("보상: 결제 환불 - {}", saga.getPaymentId());
                paymentClient.refundPayment(saga.getPaymentId());
            } catch (Exception e) {
                log.error("결제 환불 실패: {}", e.getMessage());
            }
        }

        if (saga.getReservationId() != null) {
            try {
                log.info("보상: 재고 복구 - {}", saga.getReservationId());
                inventoryClient.cancelReservation(saga.getReservationId());
            } catch (Exception e) {
                log.error("재고 복구 실패: {}", e.getMessage());
            }
        }

        if (saga.getOrderId() != null) {
            try {
                log.info("보상: 주문 취소 - {}", saga.getOrderId());
                orderClient.cancelOrder(saga.getOrderId());
            } catch (Exception e) {
                log.error("주문 취소 실패: {}", e.getMessage());
            }
        }

        // 최종 상태 업데이트
        sagaStateMapper.markAsFailed(
                saga.getSagaId(),
                "Saga compensation completed",
                saga.getVersion()
        );
    }
}
```

### 9.5 Saga 복구 스케줄러

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class SagaRecoveryScheduler {

    private final SagaStateMapper sagaStateMapper;
    private final MyBatisSagaOrchestrator orchestrator;

    private static final int STUCK_THRESHOLD_MINUTES = 10;
    private static final int BATCH_SIZE = 50;

    /**
     * 멈춰있는 Saga 복구
     * - STARTED 상태로 10분 이상 남은 Saga
     * - 서버 장애 후 재시작 시 복구 처리
     */
    @Scheduled(fixedRate = 60000)  // 1분마다
    @Transactional
    public void recoverStuckSagas() {
        LocalDateTime threshold = LocalDateTime.now()
                .minusMinutes(STUCK_THRESHOLD_MINUTES);

        List<SagaState> stuckSagas = sagaStateMapper.findStuckSagas(
                "STARTED",
                threshold,
                BATCH_SIZE
        );

        for (SagaState saga : stuckSagas) {
            try {
                log.info("복구 시도: sagaId={}, step={}",
                        saga.getSagaId(), saga.getCurrentStep());

                // 현재 단계부터 재시도 또는 보상 처리
                handleStuckSaga(saga);

            } catch (Exception e) {
                log.error("복구 실패: sagaId={}", saga.getSagaId(), e);
            }
        }
    }

    private void handleStuckSaga(SagaState saga) {
        // 단계에 따른 복구 전략
        switch (saga.getCurrentStep()) {
            case 0, 1 -> {
                // 초기 단계 - 그냥 취소
                sagaStateMapper.markAsFailed(saga.getSagaId(),
                        "Stuck at initial step", saga.getVersion());
            }
            case 2, 3 -> {
                // 중간 단계 - 보상 트랜잭션 실행
                orchestrator.compensate(saga);
            }
            case 4 -> {
                // 최종 단계 - 완료로 처리
                sagaStateMapper.updateStatus(saga.getSagaId(),
                        "COMPLETED", saga.getVersion());
            }
        }
    }

    /**
     * COMPENSATING 상태로 멈춘 Saga 정리
     */
    @Scheduled(fixedRate = 300000)  // 5분마다
    @Transactional
    public void cleanupCompensatingSagas() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(30);

        List<SagaState> compensatingSagas = sagaStateMapper.findStuckSagas(
                "COMPENSATING",
                threshold,
                BATCH_SIZE
        );

        for (SagaState saga : compensatingSagas) {
            log.warn("보상 처리 중 멈춘 Saga 발견: {}", saga.getSagaId());
            sagaStateMapper.markAsFailed(saga.getSagaId(),
                    "Compensation timed out", saga.getVersion());
        }
    }
}
```

### 9.6 JPA vs MyBatis 비교

| 기능 | JPA | MyBatis |
|------|-----|---------|
| **상태 변경** | `entity.setStatus()` 자동 감지 | `UPDATE ... SET status = ?` 직접 작성 |
| **낙관적 락** | `@Version` 자동 처리 | `WHERE version = ?` 직접 작성 |
| **단계별 저장** | Entity 필드 설정 | 동적 컬럼 UPDATE |
| **복구 조회** | JPQL + `@Lock` | `FOR UPDATE SKIP LOCKED` 직접 |
| **학습 효과** | 추상화된 동작 | SQL 레벨 동시성 제어 이해 |

### 9.7 핵심 SQL 패턴 정리

```sql
-- 1. 낙관적 락으로 상태 변경
UPDATE saga_state
SET status = ?, version = version + 1
WHERE saga_id = ? AND version = ?;

-- 2. 복구 대상 조회 (동시 처리 방지)
SELECT * FROM saga_state
WHERE status = 'STARTED'
  AND updated_at < NOW() - INTERVAL 10 MINUTE
FOR UPDATE SKIP LOCKED;

-- 3. 단계별 결과 저장 (동적 컬럼)
UPDATE saga_state
SET current_step = ?,
    order_id = ?,
    version = version + 1
WHERE saga_id = ? AND version = ?;

-- 4. 상태별 통계
SELECT status, COUNT(*), MIN(created_at), MAX(created_at)
FROM saga_state
GROUP BY status;
```

### 9.8 실습 과제 (MyBatis)

#### 과제 1: 기본 Saga 상태 관리
```
[ ] saga_state 테이블 생성
[ ] SagaStateMapper 인터페이스 작성
[ ] saga-mapper.xml 작성
```

#### 과제 2: 낙관적 락 테스트
```
[ ] 동시에 2개 요청으로 같은 Saga 상태 변경 시도
[ ] 한 쪽만 성공하는지 확인
[ ] 실패한 쪽의 affected rows = 0 확인
```

#### 과제 3: 복구 시나리오 테스트
```
[ ] Saga를 중간 단계에서 강제 중단
[ ] 복구 스케줄러가 해당 Saga 찾는지 확인
[ ] 보상 트랜잭션 정상 실행 확인
```

---

## 참고 자료

- [Microservices.io - Saga Pattern](https://microservices.io/patterns/data/saga.html)
- [Saga Pattern: Orchestration vs Choreography](https://blog.bytebytego.com/p/saga-pattern-demystified-orchestration)
- [Temporal Blog - Saga Pattern](https://temporal.io/blog/to-choreograph-or-orchestrate-your-saga-that-is-the-question)

---

## 다음 단계

[02-idempotency.md](./02-idempotency.md) - 멱등성 처리로 이동 (재시도 전 필수)
