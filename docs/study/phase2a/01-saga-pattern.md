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

## 참고 자료

- [Microservices.io - Saga Pattern](https://microservices.io/patterns/data/saga.html)
- [Saga Pattern: Orchestration vs Choreography](https://blog.bytebytego.com/p/saga-pattern-demystified-orchestration)
- [Temporal Blog - Saga Pattern](https://temporal.io/blog/to-choreograph-or-orchestrate-your-saga-that-is-the-question)

---

## 다음 단계

[02-resilience4j.md](./02-resilience4j.md) - 장애 대응으로 이동
