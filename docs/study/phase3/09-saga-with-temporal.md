# Saga 패턴과 Temporal

> **목적**: Phase 2-A에서 학습한 Saga 패턴이 Temporal 도입 후 어떻게 변화하는지 이해
> **대상**: Phase 2-A의 REST Saga 구현 경험이 있는 개발자
> **전제**: `00-temporal-deep-dive.md`, `06-temporal-activity-design-guide.md` 학습 완료

---

## 목차

1. [Saga 패턴 복습](#1-saga-패턴-복습)
2. [Temporal의 내장 Saga 지원](#2-temporal의-내장-saga-지원)
3. [보상 트랜잭션 구현](#3-보상-트랜잭션-구현)
4. [실전 예제: 주문 처리 Workflow](#4-실전-예제-주문-처리-workflow)
5. [Phase 2-A에서 Temporal로 전환 시 변화](#5-phase-2-a에서-temporal로-전환-시-변화)
6. [삭제 가능한 코드와 인프라](#6-삭제-가능한-코드와-인프라)

---

## 1. Saga 패턴 복습

### 1.1 Saga 패턴이란?

> **"분산 시스템에서 All-or-Nothing을 구현하는 방법"**

단일 DB의 ACID 트랜잭션은 여러 마이크로서비스에 걸쳐 사용할 수 없다.
Saga는 각 서비스의 로컬 트랜잭션을 순서대로 실행하고,
중간에 실패하면 이미 완료된 트랜잭션을 **보상(Compensation)**으로 되돌린다.

```
[성공]  T1: 주문 생성 --> T2: 재고 예약 --> T3: 결제 --> 완료!

[실패]  T1: 주문 생성 --> T2: 재고 예약 --> T3: 결제 FAIL!
                                                |
                                      보상 트랜잭션 시작!
                          C2: 예약 취소 <-------+
        C1: 주문 취소 <-------+
                          모두 취소됨!
```

### 1.2 Phase 2-A에서 겪은 어려움

| 문제 | 설명 |
|------|------|
| **Saga 상태 관리** | saga_states 테이블을 만들어 진행 단계를 직접 기록 |
| **보상 순서 결정** | 실패 시 어디까지 진행됐는지 조회 후 역순 보상 로직 직접 작성 |
| **크래시 복구 불가** | 서버가 죽으면 진행 중인 Saga 복구가 불가능 |
| **멱등성 키 관리** | UUID로 Saga ID를 생성하고 각 서비스에 전달 |
| **재시도 직접 구현** | Resilience4j로 재시도/서킷브레이커 설정 필요 |

> 이 모든 "인프라 코드"가 비즈니스 로직보다 더 많은 상황이었다.

---

## 2. Temporal의 내장 Saga 지원

### 2.1 io.temporal.workflow.Saga 클래스

Temporal SDK는 `io.temporal.workflow.Saga` 클래스를 내장 제공한다.
직접 Saga 프레임워크를 만들 필요 없이, **try-catch 패턴**으로 구현할 수 있다.

```java
import io.temporal.workflow.Saga;

Saga saga = new Saga(new Saga.Options.Builder()
    .setParallelCompensation(false)  // 보상을 순차 실행 (역순)
    .setContinueWithError(true)      // 보상 중 오류 발생해도 나머지 보상 계속
    .build());
```

### 2.2 핵심 API

| 메서드 | 역할 |
|--------|------|
| `saga.addCompensation(() -> ...)` | 정방향 작업 성공 후 보상 함수 등록 (내부 스택 LIFO 저장) |
| `saga.compensate()` | 등록된 보상을 역순으로 실행 (catch 블록에서 호출) |

### 2.3 Saga.Options 설정

| 옵션 | 기본값 | 설명 |
|------|--------|------|
| `parallelCompensation` | false | true: 병렬 실행 / false: 역순 순차 실행 |
| `continueWithError` | false | true: 보상 실패해도 계속 / false: 즉시 중단 |

> **권장**: `parallelCompensation=false`, `continueWithError=true`
> - 순차 보상: 보상 간 의존성이 있을 수 있으므로 역순 실행이 안전
> - 에러 무시: 하나의 보상이 실패해도 나머지는 실행해야 데이터 정합성 유지

### 2.4 try-catch 패턴

핵심 구조: try 블록에서 정방향 작업 + 보상 등록, catch 블록에서 `saga.compensate()` 호출.
DB 상태 저장 코드 없음, 보상 순서 결정 로직 없음, 한 줄이면 역순 실행 완료.
구체적인 코드는 [4.3 Workflow 구현체](#43-workflow-구현체)에서 확인.

---

## 3. 보상 트랜잭션 구현

### 3.1 보상 순서

```
정방향 실행:
T1(주문 생성) --> T2(재고 예약) --> T3(결제) --> T4(확정)

Saga 내부 보상 스택 (LIFO):
+---------------------------+
|  C3: refundPayment        |  <-- 가장 나중에 등록, 가장 먼저 실행
+---------------------------+
|  C2: cancelReservation    |
+---------------------------+
|  C1: cancelOrder          |  <-- 가장 먼저 등록, 가장 나중에 실행
+---------------------------+

saga.compensate() --> C3(환불) --> C2(예약 취소) --> C1(주문 취소)
```

### 3.2 부분 실패 시나리오

```
[시나리오 1: T3 결제 실패]
  T1 OK --> T2 OK --> T3 FAIL!
  saga.compensate() --> C2(예약 취소) --> C1(주문 취소)

[시나리오 2: T2 재고 예약 실패]
  T1 OK --> T2 FAIL!
  saga.compensate() --> C1(주문 취소)
```

### 3.3 확정 단계의 특수성

확정 단계(T4)에는 보상을 등록하지 않는다.

```
T1 --> T2 --> T3 --> T4(확정) FAIL!
                      |
               보상 등록 없음!
               --> Temporal이 재시도 (RetryOptions에 따라)
               --> 계속 실패 시 수동 개입 필요
               --> 이미 결제 완료되었으므로 단순 보상 불가
```

> **설계 원칙**: 확정 단계는 "되돌릴 수 없는 작업"이므로
> Temporal 재시도로 최대한 성공시키고, 그래도 실패하면 운영자가 개입한다.

### 3.4 멱등성의 중요성

모든 Activity(정방향 + 보상)는 멱등성을 보장해야 한다.
Temporal은 Activity를 재시도할 수 있으므로 같은 작업이 여러 번 실행되어도 결과가 동일해야 한다.

```java
public void refundPayment(Long paymentId) {
    ActivityInfo info = Activity.getExecutionContext().getInfo();
    String idempotencyKey = String.format("refund-%s-%s",
        info.getWorkflowId(), info.getActivityId());

    if (isAlreadyProcessed(idempotencyKey)) {
        return;  // 중복 요청 무시
    }

    paymentGateway.refund(paymentId);
    markAsProcessed(idempotencyKey);
}
```

---

## 4. 실전 예제: 주문 처리 Workflow

### 4.1 전체 흐름

```
Client --> POST /api/orders --> Controller
                                    |
                    workflowClient.newWorkflowStub(...)
                    workflow.processOrder(request)
                                    |
                                    v
              +--------------------------------------+
              |         Temporal Server               |
              |  1. Workflow Execution 생성           |
              |  2. Event History에 기록              |
              |  3. Task Queue에 Task 추가            |
              +----------------+---------------------+
                               | Long Polling
                               v
              +--------------------------------------+
              |      Worker (Spring Boot)             |
              |                                       |
              |  T1: createOrder()     --> Order      |
              |      addCompensation(cancelOrder)     |
              |                                       |
              |  T2: reserveStock()    --> Inventory  |
              |      addCompensation(cancelReserv.)   |
              |                                       |
              |  T3: processPayment()  --> Payment    |
              |      addCompensation(refundPayment)   |
              |                                       |
              |  T4: confirmReservation() (보상 없음) |
              |      confirmOrder()                   |
              |                                       |
              |  return OrderResult.success(orderId)  |
              +--------------------------------------+
```

### 4.2 인터페이스 정의

```java
@WorkflowInterface
public interface OrderWorkflow {
    @WorkflowMethod
    OrderResult processOrder(OrderRequest request);
}

@ActivityInterface
public interface OrderActivities {
    // 정방향
    Long createOrder(OrderRequest request);
    String reserveStock(Long productId, int quantity);
    Long createPayment(Long orderId, BigDecimal amount);
    // 확정
    void confirmReservation(String reservationId);
    void confirmOrder(Long orderId);
    // 보상
    void cancelOrder(Long orderId);
    void cancelReservation(String reservationId);
    void refundPayment(Long paymentId);
}
```

### 4.3 Workflow 구현체

```java
public class OrderWorkflowImpl implements OrderWorkflow {

    private final OrderActivities activities = Workflow.newActivityStub(
        OrderActivities.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setBackoffCoefficient(2.0)
                .setMaximumAttempts(3)
                .build())
            .build()
    );

    @Override
    public OrderResult processOrder(OrderRequest request) {
        Saga saga = new Saga(new Saga.Options.Builder()
            .setParallelCompensation(false)
            .setContinueWithError(true)
            .build());

        try {
            // T1: 주문 생성
            Long orderId = activities.createOrder(request);
            final Long fOrderId = orderId;
            saga.addCompensation(() -> activities.cancelOrder(fOrderId));

            // T2: 재고 예약 (Semantic Lock)
            String reservationId = activities.reserveStock(
                request.productId(), request.quantity());
            final String fReservationId = reservationId;
            saga.addCompensation(
                () -> activities.cancelReservation(fReservationId));

            // T3: 결제 처리
            Long paymentId = activities.createPayment(
                orderId, request.amount());
            final Long fPaymentId = paymentId;
            saga.addCompensation(
                () -> activities.refundPayment(fPaymentId));

            // T4: 확정 단계 (보상 없음)
            activities.confirmReservation(reservationId);
            activities.confirmOrder(orderId);

            return OrderResult.success(orderId, paymentId);

        } catch (ActivityFailure e) {
            saga.compensate();
            return OrderResult.failure(e.getMessage());
        }
    }
}
```

### 4.4 프로젝트 코드 구조

```
orchestrator-temporal/
  src/main/java/.../temporal/
  +-- config/TemporalConfig.java
  +-- workflow/OrderWorkflow.java
  +-- workflow/impl/OrderWorkflowImpl.java
  +-- activity/OrderActivities.java
  +-- activity/impl/OrderActivitiesImpl.java   # @Component
  +-- controller/OrderController.java
  +-- dto/OrderRequest.java, OrderResult.java
```

### 4.5 orchestrator-pure vs orchestrator-temporal

| 항목 | orchestrator-pure | orchestrator-temporal |
|------|-------------------|----------------------|
| 보상 관리 | 수동 (역순 직접 구현) | 자동 (`saga.addCompensation`) |
| 재시도 | Resilience4j | Temporal RetryOptions |
| 크래시 복구 | 불가능 | 자동 |
| 상태 추적 | 로그를 뒤져야 함 | Temporal UI에서 실시간 확인 |

> 코드량은 비슷하지만, Temporal 버전은 **인프라 코드가 없다**.
> 순수 구현은 saga_states 테이블, SagaStateRepository 등이 추가로 필요하다.

---

## 5. Phase 2-A에서 Temporal로 전환 시 변화

### 5.1 Saga ID의 역할 변화

Phase 2-A에서 Saga ID가 담당했던 4가지 역할:

| # | Phase 2-A | Temporal |
|---|-----------|----------|
| 1 | **인스턴스 식별**: UUID.randomUUID()로 생성 | Workflow ID가 대체 |
| 2 | **상태 추적**: saga_states 테이블에 DB 저장 | Event History 자동 관리 |
| 3 | **보상 순서**: 상태 조회 후 역순 보상 실행 | Saga.addCompensation() 자동 |
| 4 | **멱등성 키**: saga_id + step 형태 | Workflow ID + Activity ID |

### 5.2 코드 비교: Before vs After

**Phase 2-A (순수 구현)**:

```java
public OrderResult processOrder(OrderRequest request) {
    String sagaId = UUID.randomUUID().toString();           // 직접 생성
    sagaStateRepository.save(new SagaState(sagaId, "STARTED")); // DB 저장

    try {
        orderService.create(request, sagaId);
        sagaStateRepository.updateStep(sagaId, "ORDER_CREATED");  // 상태 갱신

        inventoryService.reserve(request, sagaId);
        sagaStateRepository.updateStep(sagaId, "STOCK_RESERVED");

        paymentService.process(request, sagaId);
        sagaStateRepository.updateStep(sagaId, "PAYMENT_COMPLETED");

        sagaStateRepository.updateStatus(sagaId, "COMPLETED");
        return OrderResult.success();
    } catch (Exception e) {
        SagaState state = sagaStateRepository.findById(sagaId);  // 상태 조회
        compensate(state);                                        // 수동 보상
        sagaStateRepository.updateStatus(sagaId, "COMPENSATED");
        return OrderResult.failure(e.getMessage());
    }
}
// 필요: saga_states 테이블, SagaStateRepository, 상태 업데이트/보상 로직
```

**Temporal 도입 후**:

```java
public OrderResult processOrder(OrderRequest request) {
    Saga saga = new Saga(new Saga.Options.Builder().build());

    try {
        String orderId = activities.createOrder(request);
        saga.addCompensation(() -> activities.cancelOrder(orderId));

        String reservationId = activities.reserveStock(request);
        saga.addCompensation(() -> activities.cancelReservation(reservationId));

        String paymentId = activities.processPayment(request);
        saga.addCompensation(() -> activities.refundPayment(paymentId));

        activities.confirmAll(orderId, reservationId);
        return OrderResult.success(orderId);
    } catch (Exception e) {
        saga.compensate();  // 자동 역순 보상
        return OrderResult.failure(e.getMessage());
    }
}
// 불필요: saga_states, SagaStateRepository, UUID 생성, 상태 관리 전부
```

### 5.3 멱등성 키 생성 방식 변화

**Phase 2-A**: Saga ID를 직접 생성하여 각 서비스에 전달해야 했다.

```java
String sagaId = UUID.randomUUID().toString();
String idempotencyKey = sagaId + "-payment-" + orderId;
// 예: "550e8400-e29b-41d4-...-payment-O123"
// 문제: sagaId 직접 생성, 각 서비스에 전달, 분실 시 추적 어려움
```

**Temporal**: Activity 내에서 Temporal이 제공하는 ID를 바로 사용한다.

```java
@Override
public PaymentResult processPayment(String orderId, BigDecimal amount) {
    ActivityInfo info = Activity.getExecutionContext().getInfo();
    String idempotencyKey = String.format("payment-%s-%s",
        info.getWorkflowId(),   // 예: "order-O123"
        info.getActivityId()    // 예: "3"
    );
    // 결과: "payment-order-O123-3"
    // 장점: 자동 관리, 재시도해도 같은 ID, 별도 전달 불필요

    return idempotencyService.executeIdempotent(idempotencyKey,
        () -> paymentClient.charge(orderId, amount));
}
```

### 5.4 전환 시 변화 요약

| 항목 | Phase 2-A | Temporal | 필요 여부 |
|------|-----------|----------|-----------|
| **Saga ID 생성** | UUID.randomUUID() | Workflow ID로 대체 | 불필요 |
| **상태 저장 (DB)** | saga_states 테이블 | Event History 자동 | 불필요 |
| **진행 단계 추적** | 직접 UPDATE | Event History 자동 | 불필요 |
| **보상 순서 결정** | 상태 조회 후 결정 | Saga 클래스 자동 | 불필요 |
| **크래시 복구** | 직접 구현 | Temporal 자동 | 불필요 |
| **멱등성 키** | saga-id 기반 | Workflow ID + Activity ID | **방식만 변경** |

---

## 6. 삭제 가능한 코드와 인프라

### 6.1 삭제 대상

```
+-----------------------------------------------------------------------+
|                Temporal 도입 시 삭제 가능한 것들                         |
+-----------------------------------------------------------------------+
|                                                                         |
|  데이터베이스:                                                         |
|  [삭제] saga_states 테이블                                             |
|  [삭제] saga_steps 테이블 (있다면)                                     |
|  [삭제] saga_compensations 테이블 (있다면)                             |
|                                                                         |
|  코드:                                                                  |
|  [삭제] SagaState 엔티티                                               |
|  [삭제] SagaStateRepository                                            |
|  [삭제] SagaStateService                                               |
|  [삭제] UUID.randomUUID() saga-id 생성 로직                            |
|  [삭제] 단계별 상태 업데이트 로직                                      |
|  [삭제] 보상 순서 결정 로직                                            |
|  [삭제] Saga 복구 로직 (서버 재시작 시)                                |
|                                                                         |
|  인프라:                                                                |
|  [삭제] Resilience4j 재시도 설정 (Temporal RetryOptions으로 대체)      |
|  [삭제] 서킷브레이커 설정 (Temporal이 관리)                            |
|                                                                         |
+-----------------------------------------------------------------------+
```

### 6.2 유지해야 하는 것

| 항목 | 이유 |
|------|------|
| **멱등성 서비스** (IdempotencyService) | 키 생성 방식만 Temporal ID 사용으로 변경 |
| **분산 락** (동시성 제어) | 여러 Workflow가 동시에 같은 리소스에 접근 가능 |
| **비즈니스 로직 검증** | 재고 확인, 결제 유효성 등은 Activity 내부에서 필요 |
| **각 서비스의 도메인 로직** | Order, Inventory, Payment 핵심 로직은 그대로 |

### 6.3 핵심 결론

```
+-----------------------------------------------------------------------+
|                         책임 분리 정리                                   |
+-----------------------------------------------------------------------+
|                                                                         |
|  Temporal이 기억하는 것:                                               |
|    - 어떤 Workflow인지 (Workflow ID)                                   |
|    - 어느 Activity까지 실행했는지 (Event History)                      |
|    - 각 Activity의 결과 (Event History)                                |
|    - 실패 시 어떤 보상을 실행해야 하는지 (Saga 클래스)                 |
|                                                                         |
|  개발자가 할 일:                                                       |
|    - Workflow ID를 비즈니스 키로 설정 (예: "order-" + orderId)         |
|    - Activity 내에서 멱등성 보장                                       |
|    - 동시성 제어 (분산 락)                                             |
|    - 비즈니스 로직 구현 (Activity 내부)                                |
|                                                                         |
|  한 줄 요약:                                                           |
|    Temporal은 "Workflow 실행"을 책임지고,                              |
|    개발자는 "Activity 내부 안전성"을 책임진다.                          |
|                                                                         |
+-----------------------------------------------------------------------+
```

---

## 다음 학습

- [10-msa-architecture.md](./10-msa-architecture.md) - MSA 아키텍처와 Temporal 통합
