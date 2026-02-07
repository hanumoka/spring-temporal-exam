# Temporal 핵심 개념: 완전 정복 가이드

> **관련 문서**: [05-temporal-faq.md](./05-temporal-faq.md) - 자주 묻는 질문
> **선행 문서**: [00-temporal-deep-dive.md](./00-temporal-deep-dive.md) - Temporal 심층 이해

---

## 목차

1. [왜 Temporal이 필요한가?](#1-왜-temporal이-필요한가)
2. [Temporal의 정체: 내구성 있는 실행](#2-temporal의-정체-내구성-있는-실행)
3. [핵심 개념 1: Workflow - 비즈니스 흐름의 설계도](#3-핵심-개념-1-workflow---비즈니스-흐름의-설계도)
4. [핵심 개념 2: Activity - 실제 일을 하는 실행자](#4-핵심-개념-2-activity---실제-일을-하는-실행자)
5. [핵심 개념 3: Worker - 묵묵한 일꾼](#5-핵심-개념-3-worker---묵묵한-일꾼)
6. [핵심 개념 4: Task Queue - 작업 전달 파이프라인](#6-핵심-개념-4-task-queue---작업-전달-파이프라인)
7. [Signal과 Query: 외부와의 소통](#7-signal과-query-외부와의-소통)
8. [전체 흐름 완전 정복](#8-전체-흐름-완전-정복)
9. [Phase 2-A 문제 해결 비교](#9-phase-2-a-문제-해결-비교)
10. [REST 없는 분산 Worker 아키텍처](#10-rest-없는-분산-worker-아키텍처)
11. [Orchestration vs Choreography](#11-orchestration-vs-choreography)
12. [실습 과제](#12-실습-과제)

---

## 1. 왜 Temporal이 필요한가?

### 1.1 Phase 2-A에서 겪은 고통들

Phase 2-A에서 REST 기반 Saga 패턴을 직접 구현하면서 많은 어려움을 겪었습니다. 이 경험은 **왜 Temporal 같은 도구가 필요한지** 이해하는 데 중요한 배경이 됩니다.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Phase 2-A에서 겪은 5가지 고통                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  고통 1: 상태 관리의 지옥                                                    │
│  ─────────────────────────                                                   │
│  - Saga 상태를 직접 DB에 저장하고 관리해야 했음                              │
│  - "지금 어떤 단계까지 성공했지?" 항상 추적 필요                             │
│  - 서버 재시작하면 진행 중인 Saga는 어떻게 복구하지?                         │
│  - 동시에 여러 Saga가 실행되면 상태 관리 더 복잡해짐                         │
│                                                                              │
│  고통 2: 보상 트랜잭션의 복잡성                                              │
│  ───────────────────────────                                                 │
│  - 각 단계별 롤백 로직을 일일이 구현해야 함                                  │
│  - 결제 취소 중에 또 실패하면? 무한 복잡도                                   │
│  - 부분 실패 시나리오가 너무 많음                                            │
│                                                                              │
│  고통 3: 재시도 로직의 난해함                                                │
│  ───────────────────────────                                                 │
│  - Resilience4j 설정 파일이 복잡해짐                                         │
│  - 어떤 예외는 재시도하고, 어떤 건 즉시 실패 처리?                           │
│  - 재시도 횟수, 간격, 지수 백오프... 설정할 게 너무 많음                     │
│                                                                              │
│  고통 4: 타임아웃 처리                                                       │
│  ───────────────────                                                         │
│  - 서비스별로 다른 타임아웃 설정 관리                                        │
│  - 외부 결제 승인이 1시간 걸리면 어떻게 대기?                                │
│  - 장시간 실행 작업을 일반 HTTP로 처리하기 어려움                            │
│                                                                              │
│  고통 5: 가시성 부족                                                         │
│  ───────────────────                                                         │
│  - "지금 이 주문이 어느 단계인가?" 파악 어려움                               │
│  - 실패 원인을 찾으려면 여러 서비스 로그를 뒤져야 함                         │
│  - 디버깅에 너무 많은 시간 소요                                              │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 직접 구현한 Saga 코드의 문제점

Phase 2-A에서 작성한 코드를 다시 살펴보며 문제점을 짚어봅시다:

```java
/**
 * Phase 2-A에서 구현한 오케스트레이터
 *
 * 이 코드는 동작하지만, 많은 문제점을 내포하고 있습니다.
 * 각 줄의 주석을 통해 어떤 상황에서 문제가 발생하는지 확인하세요.
 */
public class PureSagaOrchestrator {

    public OrderSagaResult execute(OrderSagaRequest request) {
        String orderId = null;
        String reservationId = null;
        String paymentId = null;

        try {
            // ───────────────────────────────────────────────────────
            // Step 1: 주문 생성
            // ───────────────────────────────────────────────────────
            // 문제점: 이 호출 중에 서버가 죽으면?
            //   → 주문은 생성됐는지 안 됐는지 모름
            //   → 다시 시작하면 중복 주문 가능성
            orderId = orderClient.createOrder(request);

            // ───────────────────────────────────────────────────────
            // Step 2: 재고 예약
            // ───────────────────────────────────────────────────────
            // 문제점: 네트워크 타임아웃이 발생하면?
            //   → 재고가 예약됐는지 안 됐는지 모름
            //   → 응답을 못 받았다고 실패 처리? 예약됐을 수도 있는데?
            reservationId = inventoryClient.reserveStock(orderId);

            // ───────────────────────────────────────────────────────
            // Step 3: 결제 처리
            // ───────────────────────────────────────────────────────
            // 문제점: 결제 API 호출 후 연결이 끊기면?
            //   → 결제 성공? 실패? 모호한 상태
            //   → 이 상태에서 재시도하면 이중 결제 위험!
            paymentId = paymentClient.processPayment(orderId);

            return OrderSagaResult.success(orderId);

        } catch (Exception e) {
            // ───────────────────────────────────────────────────────
            // 보상 트랜잭션
            // ───────────────────────────────────────────────────────
            // 문제점: 보상 중에 또 실패하면?
            //   → compensateStep2()가 실패하면 재고는 어떻게 되지?
            //   → 보상 트랜잭션도 재시도해야 하나?
            //   → 무한 복잡도의 시작...
            compensate(orderId, reservationId, paymentId);
            return OrderSagaResult.failure(e.getMessage());
        }
    }

    private void compensate(String orderId, String reservationId, String paymentId) {
        // 각 보상을 try-catch로 감싸야 하나?
        // 순서는 어떻게? 역순?
        // 부분 실패 시 다시 시도?
        // ... 복잡도 폭발!
    }
}
```

**이 코드에서 발생할 수 있는 문제들:**

| 상황 | 발생하는 문제 | 해결책은? |
|------|--------------|----------|
| **Step 2 실행 중 서버 다운** | 주문은 생성됐는데 재고는? 처음부터 다시? | 수동 복구 필요 |
| **네트워크 타임아웃** | 결제 API 응답 없음. 성공? 실패? | 알 수 없음 |
| **3번 재시도 후에도 실패** | 더 이상 할 수 있는 게 없음 | 수동 처리 |
| **현재 진행 상태 확인** | 로그 뒤지거나 DB 직접 조회 | 시간 낭비 |
| **외부 승인 1시간 대기** | HTTP 연결 유지? 폴링? | 구현 복잡 |

> **핵심 질문**: "이 모든 문제를 자동으로 처리해주는 도구가 있다면 어떨까?"
>
> 그것이 바로 **Temporal**입니다.

---

## 2. Temporal의 정체: 내구성 있는 실행

### 2.1 Temporal이란 무엇인가?

**Temporal**은 분산 시스템에서 **내구성 있는 실행(Durable Execution)**을 제공하는 워크플로우 오케스트레이션 플랫폼입니다.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Temporal                                        │
│                                                                              │
│        "코드를 작성하면, Temporal이 알아서 실행을 보장해줍니다"                │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │                                                                         │ │
│  │  당신이 할 일:                     Temporal이 해주는 일:                 │ │
│  │  ───────────────                   ─────────────────────                │ │
│  │  비즈니스 로직 작성                 • 실패하면 자동 재시도               │ │
│  │  (순수한 코드)                      • 서버가 죽어도 이어서 실행          │ │
│  │                                     • 상태를 자동으로 저장               │ │
│  │                                     • 실행 이력을 완벽하게 기록          │ │
│  │                                     • 타임아웃 자동 관리                 │ │
│  │                                     • 가시성(UI) 제공                   │ │
│  │                                                                         │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 비유로 이해하기: 자동차 vs 비행기

**일반 코드 = 일반 자동차 운전**
- 직접 핸들을 잡고 운전
- 사고 나면 직접 수습
- 길을 잃으면 직접 찾아야 함
- 피곤하면 쉬어야 함

**Temporal Workflow = 자율주행 + 비행기 블랙박스**
- 목적지만 알려주면 알아서 도착
- 사고가 나도 자동 복구
- 모든 경로와 상태를 자동 기록
- 24시간 쉬지 않고 실행 가능

### 2.3 핵심 가치 비교

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                        일반 코드 vs Temporal Workflow                          │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│  ┌─────────────────────────────┐      ┌─────────────────────────────┐       │
│  │      일반 코드               │      │    Temporal Workflow         │       │
│  ├─────────────────────────────┤      ├─────────────────────────────┤       │
│  │                             │      │                              │       │
│  │  try {                      │      │  // 그냥 순서대로 작성       │       │
│  │    step1();                 │      │  step1();                    │       │
│  │    step2();  // 여기서 죽음  │      │  step2();  // 여기서 죽어도  │       │
│  │    step3();  // 실행 안 됨  │      │  step3();  // 재시작 후 계속 │       │
│  │  } catch (Exception e) {   │      │                              │       │
│  │    // 롤백? 재시도? 복잡... │      │  // 자동 재시도, 자동 복구   │       │
│  │  }                         │      │                              │       │
│  │                             │      │                              │       │
│  └─────────────────────────────┘      └─────────────────────────────┘       │
│                                                                               │
│  서버가 죽으면?                       서버가 죽으면?                         │
│  → 처음부터 다시 시작                 → 마지막 완료 지점에서 이어서 실행      │
│  → 상태 복구 필요                     → 상태 자동 복구                       │
│  → 중복 실행 위험                     → 중복 실행 방지                       │
│                                                                               │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 2.4 Temporal이 해결해주는 문제들

| Phase 2-A 문제 | Temporal 해결책 | 세부 설명 |
|---------------|----------------|----------|
| Saga 상태 관리 | **Event Sourcing** | 모든 이벤트를 기록하여 언제든 상태 복원 가능 |
| 보상 트랜잭션 | **Saga 패턴 내장** | 실패 시 자동으로 보상 로직 실행 |
| 재시도 로직 | **Retry Policy** | 선언적으로 설정, 자동 적용 |
| 타임아웃 처리 | **Activity Timeout** | 세밀한 타임아웃 옵션 제공 |
| 가시성 | **Temporal Web UI** | 실시간 상태 확인, 이력 조회 |
| 장시간 실행 | **Durable Execution** | 며칠, 몇 달도 실행 가능 |

---

## 3. 핵심 개념 1: Workflow - 비즈니스 흐름의 설계도

### 3.1 Workflow란 무엇인가?

**Workflow**는 비즈니스 로직의 전체 흐름을 정의하는 함수입니다. 요리에 비유하면 **레시피**와 같습니다.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Workflow = 레시피                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  요리 레시피                              Temporal Workflow                   │
│  ───────────                              ─────────────────                   │
│                                                                              │
│  1. 계란을 깬다                           1. 주문을 생성한다                  │
│  2. 소금을 넣고 섞는다                    2. 재고를 예약한다                  │
│  3. 팬에 기름을 두른다                    3. 결제를 처리한다                  │
│  4. 계란물을 붓고 굽는다                  4. 배송을 시작한다                  │
│  5. 접시에 담는다                         5. 알림을 보낸다                    │
│                                                                              │
│  레시피 = 무엇을 어떤 순서로              Workflow = 무엇을 어떤 순서로       │
│  각 동작(Activity)은 별도               각 동작(Activity)은 별도            │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Workflow의 핵심 특성

Workflow에는 세 가지 핵심 특성이 있습니다. 이를 이해하면 Temporal을 올바르게 사용할 수 있습니다.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Workflow의 3가지 핵심 특성                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │  1. 결정적(Deterministic)                                              │ │
│  │  ═══════════════════════                                               │ │
│  │                                                                         │ │
│  │  "같은 입력 → 항상 같은 실행 경로"                                      │ │
│  │                                                                         │ │
│  │  왜 중요한가?                                                           │ │
│  │  Temporal은 Workflow를 "재실행(Replay)"하여 상태를 복원합니다.          │ │
│  │  재실행할 때마다 다른 결과가 나오면 상태 복원이 불가능합니다.            │ │
│  │                                                                         │ │
│  │  비유: 영화 다시보기                                                    │ │
│  │  영화를 다시 틀면 항상 같은 장면이 나와야 합니다.                       │ │
│  │  매번 다른 결말이 나오면 "이전에 어디까지 봤지?" 알 수 없습니다.        │ │
│  │                                                                         │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │  2. 내구성(Durable)                                                    │ │
│  │  ══════════════════                                                    │ │
│  │                                                                         │ │
│  │  "서버가 죽어도 상태 유지, 재시작 시 이어서 실행"                        │ │
│  │                                                                         │ │
│  │  어떻게 가능한가?                                                       │ │
│  │  Temporal이 모든 진행 상태를 Event History로 저장하기 때문입니다.       │ │
│  │  서버가 재시작되면 History를 읽어서 "어디까지 했는지" 파악 후 계속 진행. │ │
│  │                                                                         │ │
│  │  비유: 자동 저장되는 게임                                               │ │
│  │  게임 중 컴퓨터가 꺼져도, 다시 켜면 마지막 체크포인트에서 이어서 플레이  │ │
│  │                                                                         │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │  3. 장시간 실행 가능(Long-Running)                                     │ │
│  │  ═════════════════════════════════                                     │ │
│  │                                                                         │ │
│  │  "며칠, 몇 달, 심지어 몇 년도 실행 가능"                                 │ │
│  │                                                                         │ │
│  │  HTTP 요청은 보통 몇 초 내에 응답해야 합니다.                            │ │
│  │  하지만 비즈니스 프로세스는 훨씬 오래 걸릴 수 있습니다:                  │ │
│  │  - 결제 승인 대기: 몇 시간                                              │ │
│  │  - 문서 결재 프로세스: 며칠                                             │ │
│  │  - 구독 갱신 알림: 1년 후                                               │ │
│  │                                                                         │ │
│  │  Temporal Workflow는 이 모든 것을 자연스럽게 처리합니다.                 │ │
│  │                                                                         │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.3 Workflow 코드 작성하기

Temporal Workflow는 **인터페이스**와 **구현체**로 구성됩니다.

```java
// ═══════════════════════════════════════════════════════════════════════════
// 1. Workflow 인터페이스 정의
// ═══════════════════════════════════════════════════════════════════════════

@WorkflowInterface  // Temporal에게 "이것은 Workflow 인터페이스입니다" 알림
public interface OrderWorkflow {

    /**
     * @WorkflowMethod: Workflow의 진입점 (main 메서드와 비슷)
     * - 하나의 인터페이스에 반드시 하나만 있어야 함
     * - Workflow 시작 시 이 메서드가 호출됨
     */
    @WorkflowMethod
    OrderResult processOrder(OrderRequest request);

    /**
     * @SignalMethod: 외부에서 Workflow로 이벤트를 보낼 때 사용
     * - 실행 중인 Workflow에 "취소해줘!" 같은 신호를 보낼 수 있음
     * - 비동기적으로 호출됨 (응답을 기다리지 않음)
     */
    @SignalMethod
    void cancelOrder(String reason);

    /**
     * @QueryMethod: Workflow의 현재 상태를 조회할 때 사용
     * - 실행 중인 Workflow의 상태를 읽기만 함 (변경 불가)
     * - 동기적으로 호출됨 (즉시 응답)
     */
    @QueryMethod
    OrderStatus getStatus();
}

// ═══════════════════════════════════════════════════════════════════════════
// 2. Workflow 구현체 작성
// ═══════════════════════════════════════════════════════════════════════════

public class OrderWorkflowImpl implements OrderWorkflow {

    // ─────────────────────────────────────────────────────────────────────
    // Activity Stub 생성
    // ─────────────────────────────────────────────────────────────────────
    // Activity를 직접 호출하지 않고, "Stub(대리인)"을 통해 호출합니다.
    // 이 Stub이 Temporal Server와 통신하여 Activity 실행을 요청합니다.

    private final OrderActivities activities = Workflow.newActivityStub(
        OrderActivities.class,
        ActivityOptions.newBuilder()
            // 타임아웃: Activity 실행이 5분을 넘으면 실패 처리
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            // 재시도: 최대 3번까지 시도
            .setRetryOptions(RetryOptions.newBuilder()
                .setMaximumAttempts(3)
                .build())
            .build()
    );

    // ─────────────────────────────────────────────────────────────────────
    // Workflow 상태 변수
    // ─────────────────────────────────────────────────────────────────────
    // Workflow 내부 상태는 자동으로 저장/복원됩니다.
    // 서버가 죽어도 이 상태가 유지됩니다!

    private OrderStatus status = OrderStatus.PENDING;
    private boolean cancelled = false;

    // ─────────────────────────────────────────────────────────────────────
    // 메인 Workflow 로직
    // ─────────────────────────────────────────────────────────────────────
    @Override
    public OrderResult processOrder(OrderRequest request) {

        // Step 1: 주문 생성
        status = OrderStatus.CREATING_ORDER;
        String orderId = activities.createOrder(request);
        // ↑ 여기서 서버가 죽어도, 재시작 후 이 Activity 결과부터 이어서 진행!

        // 취소 확인 (Signal로 취소 요청이 왔을 수 있음)
        if (cancelled) {
            return compensateAndReturn(orderId, null, null);
        }

        // Step 2: 재고 예약
        status = OrderStatus.RESERVING_STOCK;
        String reservationId = activities.reserveStock(orderId);
        // ↑ 여기서 실패하면 자동으로 최대 3번 재시도!

        // Step 3: 결제 처리
        status = OrderStatus.PROCESSING_PAYMENT;
        String paymentId = activities.processPayment(orderId);

        // Step 4: 주문 확정
        status = OrderStatus.CONFIRMING;
        activities.confirmOrder(orderId);

        status = OrderStatus.COMPLETED;
        return OrderResult.success(orderId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Signal 핸들러
    // ─────────────────────────────────────────────────────────────────────
    @Override
    public void cancelOrder(String reason) {
        // 외부에서 "취소해줘!" 신호를 보내면 이 메서드가 호출됨
        // cancelled = true로 설정하면, 다음 체크 포인트에서 취소 처리됨
        this.cancelled = true;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Query 핸들러
    // ─────────────────────────────────────────────────────────────────────
    @Override
    public OrderStatus getStatus() {
        // 외부에서 "지금 어떤 상태야?" 질문하면 현재 상태 반환
        return this.status;
    }

    // 보상 로직 (생략)
    private OrderResult compensateAndReturn(...) { ... }
}
```

### 3.4 Workflow에서 절대 하면 안 되는 것들

Workflow는 **결정적(Deterministic)**이어야 하므로, 비결정적인 코드를 사용하면 안 됩니다.

```java
// ═══════════════════════════════════════════════════════════════════════════
// ❌ 잘못된 Workflow 코드 (절대 하면 안 됨!)
// ═══════════════════════════════════════════════════════════════════════════

public class BadWorkflowImpl implements OrderWorkflow {

    @Override
    public OrderResult processOrder(OrderRequest request) {

        // ❌ 1. Random 사용 금지
        // ─────────────────────
        // 왜 안 되는가?
        // Replay 시 다른 값이 나오면 실행 경로가 달라져 상태 복원 실패
        if (Math.random() > 0.5) {  // ❌ 매번 다른 결과!
            doSomething();
        }

        // ❌ 2. 현재 시간 직접 사용 금지
        // ───────────────────────────
        // 왜 안 되는가?
        // Replay 시 시간이 달라지면 조건문 결과가 달라짐
        LocalDateTime now = LocalDateTime.now();  // ❌ Replay 시 다른 값!
        if (now.getHour() > 18) {
            sendNightNotification();
        }

        // ❌ 3. 네트워크 호출 금지
        // ───────────────────────
        // 왜 안 되는가?
        // 외부 호출은 Activity에서 해야 함. Workflow는 순수 로직만!
        restTemplate.postForObject(...);  // ❌ Activity로 분리해야!

        // ❌ 4. 파일 I/O 금지
        // ──────────────────
        // 왜 안 되는가?
        // 파일 내용이 변경되면 Replay 시 다른 결과
        Files.readString(path);  // ❌ Activity로 분리해야!

        // ❌ 5. Thread.sleep 금지
        // ───────────────────────
        // 왜 안 되는가?
        // Temporal은 자체적인 타이머 메커니즘이 있음
        Thread.sleep(1000);  // ❌ Workflow.sleep 사용해야!

        // ❌ 6. UUID.randomUUID() 금지
        // ────────────────────────────
        // 왜 안 되는가?
        // 매번 다른 UUID가 생성됨
        String id = UUID.randomUUID().toString();  // ❌
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// ✅ 올바른 Workflow 코드
// ═══════════════════════════════════════════════════════════════════════════

public class GoodWorkflowImpl implements OrderWorkflow {

    @Override
    public OrderResult processOrder(OrderRequest request) {

        // ✅ 1. Temporal이 제공하는 랜덤 사용
        // ──────────────────────────────────
        // Temporal은 Replay 시에도 동일한 값을 반환하도록 관리
        int random = Workflow.newRandom().nextInt(100);

        // ✅ 2. Temporal이 제공하는 시간 사용
        // ──────────────────────────────────
        // Replay 시에도 동일한 시간 값을 반환
        long now = Workflow.currentTimeMillis();

        // ✅ 3. Activity를 통한 외부 호출
        // ──────────────────────────────
        // 외부 호출은 Activity로 분리하여 처리
        String result = activities.callExternalService(request);

        // ✅ 4. Workflow.sleep 사용
        // ─────────────────────────
        // Temporal이 관리하는 타이머. 서버가 죽어도 정확하게 대기!
        Workflow.sleep(Duration.ofSeconds(1));

        // ✅ 5. Workflow.randomUUID() 사용
        // ─────────────────────────────────
        // Replay 시에도 동일한 UUID 반환
        String id = Workflow.randomUUID().toString();
    }
}
```

> **기억하세요**: Workflow에서는 "결정적"이지 않은 것은 모두 금지입니다!
> - 랜덤, 시간, UUID → Workflow.xxx() 사용
> - 외부 호출, 파일 I/O → Activity로 분리
> - Thread.sleep → Workflow.sleep() 사용

---

## 4. 핵심 개념 2: Activity - 실제 일을 하는 실행자

### 4.1 Activity란 무엇인가?

**Activity**는 외부 세계와 상호작용하는 작업 단위입니다. 요리 비유로는 **실제 요리 동작**에 해당합니다.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Activity = 실제 요리 동작                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Workflow(레시피)가                Activity(실제 동작)가                     │
│  "계란을 깨라"라고 하면             실제로 계란을 깹니다                       │
│                                                                              │
│  Activity가 하는 일:                                                         │
│  ───────────────────                                                         │
│  • REST API 호출 (다른 서비스와 통신)                                         │
│  • 데이터베이스 접근 (CRUD 작업)                                              │
│  • 파일 처리 (읽기/쓰기)                                                     │
│  • 외부 서비스 연동 (결제, 배송, 알림 등)                                     │
│  • 이메일/SMS 발송                                                           │
│                                                                              │
│  핵심 차이:                                                                  │
│  ──────────                                                                  │
│  Workflow = 순수한 로직 (비결정적 코드 금지)                                  │
│  Activity = 외부와 통신 (비결정적 코드 허용!)                                 │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Workflow vs Activity 비교

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Workflow vs Activity 비교                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  관점          │ Workflow                  │ Activity                       │
│  ──────────────┼───────────────────────────┼───────────────────────────────│
│  역할          │ 비즈니스 흐름 정의         │ 실제 작업 수행                  │
│  비유          │ 오케스트라 지휘자          │ 연주자들                       │
│  결정성        │ 결정적(Deterministic)      │ 비결정적 허용                  │
│  외부 호출     │ ❌ 금지                    │ ✅ 가능                        │
│  상태 저장     │ 자동 (Event History)       │ 결과만 저장                    │
│  재시도        │ 전체 Replay               │ Activity 단위 재시도           │
│  Spring DI    │ ❌ 불가                    │ ✅ 가능 (@Component)           │
│  실행 위치     │ Worker 프로세스 내         │ Worker 프로세스 내             │
│                                                                              │
│  비유로 정리:                                                                │
│  ──────────────                                                              │
│  Workflow = 요리 레시피 (순서와 조건만 정의)                                  │
│  Activity = 실제 요리 동작 (계란 깨기, 섞기, 굽기)                            │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 4.3 Activity 코드 작성하기

Activity도 **인터페이스**와 **구현체**로 구성됩니다. 중요한 점은 **구현체에서 Spring DI를 사용할 수 있다**는 것입니다.

```java
// ═══════════════════════════════════════════════════════════════════════════
// 1. Activity 인터페이스 정의
// ═══════════════════════════════════════════════════════════════════════════

@ActivityInterface  // Temporal에게 "이것은 Activity 인터페이스입니다" 알림
public interface OrderActivities {

    /**
     * 각 메서드가 하나의 Activity입니다.
     * 각 Activity는 독립적으로 실행/재시도됩니다.
     */

    @ActivityMethod
    String createOrder(OrderRequest request);

    @ActivityMethod
    String reserveStock(String orderId);

    @ActivityMethod
    String processPayment(String orderId);

    @ActivityMethod
    void confirmOrder(String orderId);

    // ─────────────────────────────────────────────────────────────────────
    // 보상 트랜잭션용 Activity
    // ─────────────────────────────────────────────────────────────────────
    // 실패 시 롤백을 위한 Activity들

    @ActivityMethod
    void cancelOrder(String orderId);

    @ActivityMethod
    void releaseStock(String reservationId);

    @ActivityMethod
    void refundPayment(String paymentId);
}

// ═══════════════════════════════════════════════════════════════════════════
// 2. Activity 구현체 작성
// ═══════════════════════════════════════════════════════════════════════════

@Component  // ✅ Spring Bean으로 등록! DI 사용 가능!
@RequiredArgsConstructor
public class OrderActivitiesImpl implements OrderActivities {

    // ─────────────────────────────────────────────────────────────────────
    // Spring DI로 주입받은 클라이언트들
    // ─────────────────────────────────────────────────────────────────────
    // Activity는 Spring Bean이므로 다른 Bean들을 주입받을 수 있습니다!

    private final OrderServiceClient orderClient;      // REST 클라이언트
    private final InventoryServiceClient inventoryClient;
    private final PaymentServiceClient paymentClient;
    private final OrderRepository orderRepository;    // JPA Repository도 OK!

    @Override
    public String createOrder(OrderRequest request) {
        // ─────────────────────────────────────────────────────────────────
        // Activity에서는 자유롭게 외부 호출 가능!
        // ─────────────────────────────────────────────────────────────────

        // REST API 호출
        OrderResponse response = orderClient.createOrder(request);

        // 로깅도 자유롭게 가능
        log.info("주문 생성됨: {}", response.getOrderId());

        return response.getOrderId();
    }

    @Override
    public String reserveStock(String orderId) {
        // 외부 서비스 호출
        ReservationResponse response = inventoryClient.reserveStock(orderId);
        return response.getReservationId();
    }

    @Override
    public String processPayment(String orderId) {
        // 결제 처리 - 여기서 실패하면 Temporal이 자동 재시도!
        PaymentResponse response = paymentClient.processPayment(orderId);
        return response.getPaymentId();
    }

    @Override
    public void confirmOrder(String orderId) {
        orderClient.confirmOrder(orderId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 보상 트랜잭션 구현
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void cancelOrder(String orderId) {
        // 주문 취소 로직
        orderClient.cancelOrder(orderId);
    }

    @Override
    public void releaseStock(String reservationId) {
        // 재고 예약 해제
        inventoryClient.cancelReservation(reservationId);
    }

    @Override
    public void refundPayment(String paymentId) {
        // 결제 취소/환불
        paymentClient.refundPayment(paymentId);
    }
}
```

### 4.4 Activity 옵션 상세 설명

Activity는 다양한 옵션을 통해 세밀하게 제어할 수 있습니다.

```java
// ═══════════════════════════════════════════════════════════════════════════
// Activity 옵션 완전 가이드
// ═══════════════════════════════════════════════════════════════════════════

ActivityOptions options = ActivityOptions.newBuilder()

    // ─────────────────────────────────────────────────────────────────────
    // 1. 타임아웃 설정 (Timeout Configuration)
    // ─────────────────────────────────────────────────────────────────────

    // StartToCloseTimeout: Activity가 시작된 후 완료까지 허용되는 최대 시간
    // - 가장 많이 사용되는 타임아웃
    // - 이 시간을 초과하면 Activity가 실패 처리됨
    .setStartToCloseTimeout(Duration.ofMinutes(5))

    // ScheduleToStartTimeout: 스케줄된 후 시작까지 대기 시간
    // - Worker가 부족하면 오래 대기할 수 있음
    // - 너무 오래 대기하면 실패 처리
    .setScheduleToStartTimeout(Duration.ofMinutes(1))

    // ScheduleToCloseTimeout: 스케줄 ~ 완료까지 전체 시간
    // - StartToCloseTimeout + ScheduleToStartTimeout의 합보다 커야 함
    .setScheduleToCloseTimeout(Duration.ofMinutes(10))

    // HeartbeatTimeout: 하트비트 주기
    // - 장시간 Activity에서 "나 아직 살아있어요" 신호를 보내야 함
    // - 하트비트가 이 시간 동안 없으면 Activity가 멈춘 것으로 간주
    .setHeartbeatTimeout(Duration.ofSeconds(30))

    // ─────────────────────────────────────────────────────────────────────
    // 2. 재시도 설정 (Retry Configuration)
    // ─────────────────────────────────────────────────────────────────────

    .setRetryOptions(RetryOptions.newBuilder()

        // 첫 재시도 대기 시간
        // - 첫 번째 실패 후 1초 대기
        .setInitialInterval(Duration.ofSeconds(1))

        // 지수 백오프 계수
        // - 1초 → 2초 → 4초 → 8초... (2배씩 증가)
        .setBackoffCoefficient(2.0)

        // 최대 대기 시간
        // - 아무리 지수 증가해도 최대 1분을 넘지 않음
        .setMaximumInterval(Duration.ofMinutes(1))

        // 최대 시도 횟수
        // - 5번까지 시도 후 포기
        .setMaximumAttempts(5)

        // 재시도하지 않을 예외 목록
        // - 이 예외들은 재시도해도 의미 없음 (비즈니스 오류)
        .setDoNotRetry(
            InvalidInputException.class,    // 잘못된 입력
            NotFoundException.class,        // 존재하지 않음
            AuthorizationException.class    // 권한 없음
        )

        .build())

    .build();
```

**타임아웃 다이어그램:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Activity 타임아웃 이해하기                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  시간 흐름 ──────────────────────────────────────────────────────────────▶   │
│                                                                              │
│  │ Schedule     │ Start        │ Heartbeat │ Heartbeat │ Complete           │
│  │ (스케줄)      │ (시작)       │ (1)       │ (2)       │ (완료)             │
│  ▼              ▼              ▼           ▼           ▼                    │
│  ┌──────────────┬──────────────────────────────────────────────────┐        │
│  │   대기 중    │              실행 중                              │        │
│  └──────────────┴──────────────────────────────────────────────────┘        │
│  │              │                                                   │        │
│  │◄────────────►│                                                   │        │
│  │ Schedule     │◄─────────────────────────────────────────────────►│        │
│  │ ToStart      │            StartToClose                           │        │
│  │              │                                                   │        │
│  │◄────────────────────────────────────────────────────────────────►│        │
│  │                       ScheduleToClose                            │        │
│  │              │        │◄──────────►│                             │        │
│  │              │        │ Heartbeat  │                             │        │
│                                                                              │
│  HeartbeatTimeout: 하트비트 간격이 이 시간을 초과하면 Activity 실패 처리      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. 핵심 개념 3: Worker - 묵묵한 일꾼

### 5.1 Worker란 무엇인가?

**Worker**는 Workflow와 Activity 코드를 실제로 실행하는 프로세스입니다. 비유하면 **공장의 기계**와 같습니다.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                             Worker = 공장의 기계                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Temporal Server는 "작업 지시서"를 발행합니다.                                │
│  Worker는 그 지시서를 받아서 실제로 작업을 수행합니다.                        │
│                                                                              │
│  공장 비유:                                                                  │
│  ───────────                                                                 │
│  Temporal Server = 본사 (작업 지시서 발행)                                   │
│  Task Queue = 공장 입구 (지시서가 쌓이는 곳)                                 │
│  Worker = 공장 기계 (지시서를 가져가서 실행)                                 │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                          Worker 구조                                 │    │
│  │                                                                      │    │
│  │  ┌─────────────────────────────────────────────────────────────┐    │    │
│  │  │                      Worker Process                          │    │    │
│  │  │                                                              │    │    │
│  │  │  Task Queue 이름: "order-queue"                              │    │    │
│  │  │                                                              │    │    │
│  │  │  등록된 Workflow 구현체:                                      │    │    │
│  │  │  └── OrderWorkflowImpl.class                                 │    │    │
│  │  │                                                              │    │    │
│  │  │  등록된 Activity 구현체:                                      │    │    │
│  │  │  └── OrderActivitiesImpl (인스턴스, Spring Bean)             │    │    │
│  │  │                                                              │    │    │
│  │  └──────────────────────────────────────────────────────────────┘    │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.2 Worker의 동작 원리

Worker는 **Long Polling** 방식으로 Temporal Server에서 작업을 가져옵니다.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Worker 동작 원리                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │                                                                       │   │
│  │  1. Worker 시작                                                       │   │
│  │     │                                                                 │   │
│  │     │  "Temporal Server에 연결할게요!"                                │   │
│  │     │  "order-queue를 담당합니다!"                                    │   │
│  │     ▼                                                                 │   │
│  │                                                                       │   │
│  │  2. Long Polling 시작 (반복)                                          │   │
│  │     │                                                                 │   │
│  │     │  "order-queue에 작업 있나요?" ──────▶ Temporal Server           │   │
│  │     │                             ◀────── "잠깐만요, 기다려요..."     │   │
│  │     │                             ◀────── (작업이 생기면) "이거요!"   │   │
│  │     ▼                                                                 │   │
│  │                                                                       │   │
│  │  3. Task 수신                                                         │   │
│  │     │                                                                 │   │
│  │     │  "OrderWorkflow의 processOrder 실행해주세요"                    │   │
│  │     │  또는                                                           │   │
│  │     │  "createOrder Activity 실행해주세요"                            │   │
│  │     ▼                                                                 │   │
│  │                                                                       │   │
│  │  4. 코드 실행                                                         │   │
│  │     │                                                                 │   │
│  │     │  등록된 Workflow/Activity 구현체에서 해당 메서드 실행            │   │
│  │     ▼                                                                 │   │
│  │                                                                       │   │
│  │  5. 결과 보고                                                         │   │
│  │     │                                                                 │   │
│  │     │  "실행 완료! 결과는 이거예요" ──────▶ Temporal Server           │   │
│  │     ▼                                                                 │   │
│  │                                                                       │   │
│  │  6. 다시 2번으로 (무한 반복)                                          │   │
│  │                                                                       │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.3 Worker 설정 코드

Spring Boot에서 Worker를 설정하는 방법입니다.

```java
// ═══════════════════════════════════════════════════════════════════════════
// Worker 설정 (Spring Boot)
// ═══════════════════════════════════════════════════════════════════════════

@Configuration
@RequiredArgsConstructor
public class TemporalWorkerConfig {

    private final WorkflowClient workflowClient;  // Temporal Server 연결 클라이언트
    private final OrderActivities orderActivities;  // Spring Bean으로 주입받음!

    // ─────────────────────────────────────────────────────────────────────
    // WorkerFactory 생성
    // ─────────────────────────────────────────────────────────────────────
    // Worker들을 생성하고 관리하는 팩토리

    @Bean
    public WorkerFactory workerFactory() {
        return WorkerFactory.newInstance(workflowClient);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Worker 생성 및 설정
    // ─────────────────────────────────────────────────────────────────────

    @Bean
    public Worker orderWorker(WorkerFactory workerFactory) {
        // Task Queue 이름으로 Worker 생성
        // "order-queue"라는 이름의 Task Queue를 폴링할 Worker
        Worker worker = workerFactory.newWorker("order-queue");

        // ─────────────────────────────────────────────────────────────────
        // Workflow 등록
        // ─────────────────────────────────────────────────────────────────
        // ⚠️ 주의: 클래스(타입)를 등록! 인스턴스 아님!
        // Temporal이 직접 인스턴스를 생성하고 관리합니다.
        // 따라서 Workflow에서는 Spring DI 사용 불가!

        worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);

        // ─────────────────────────────────────────────────────────────────
        // Activity 등록
        // ─────────────────────────────────────────────────────────────────
        // ✅ 인스턴스를 등록! Spring Bean을 주입받아서 등록!
        // 따라서 Activity에서는 Spring DI 사용 가능!

        worker.registerActivitiesImplementations(orderActivities);

        return worker;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Worker 시작
    // ─────────────────────────────────────────────────────────────────────

    @PostConstruct
    public void startWorkers() {
        // 앱 시작 시 모든 Worker 시작
        // Worker가 시작되면 Temporal Server에 연결하고 Task Queue 폴링 시작
        workerFactory().start();
    }
}
```

### 5.4 다중 Worker와 스케일링

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           다중 Worker 구성                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  같은 Task Queue를 여러 Worker가 폴링할 수 있습니다.                          │
│  → 자동 로드 밸런싱! 하나가 죽어도 다른 Worker가 처리!                        │
│                                                                              │
│                        Temporal Server                                       │
│                            │                                                 │
│             ┌──────────────┼──────────────┐                                 │
│             │              │              │                                  │
│             ▼              ▼              ▼                                  │
│      ┌──────────┐   ┌──────────┐   ┌──────────┐                            │
│      │ Worker 1 │   │ Worker 2 │   │ Worker 3 │                            │
│      │(Server A)│   │(Server B)│   │(Server C)│                            │
│      └──────────┘   └──────────┘   └──────────┘                            │
│                                                                              │
│      모두 "order-queue"를 폴링하고 있음                                       │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  스케일링 전략:                                                              │
│                                                                              │
│  1. 수평 확장: Worker 인스턴스 수 증가                                        │
│     → 더 많은 작업을 병렬 처리                                               │
│                                                                              │
│  2. Task Queue 분리: 작업 유형별 별도 Queue                                   │
│     → 주문은 order-queue, 결제는 payment-queue                               │
│     → 특정 작업에 더 많은 Worker 할당 가능                                    │
│                                                                              │
│  3. Worker 옵션 조정: 동시 실행 수 제한                                       │
│     → 리소스 사용량 제어                                                     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.5 Worker Rate Limiting (Phase 2-A RSemaphore 대체)

Phase 2-A에서 Redis RSemaphore로 처리량을 제한했던 것을 Temporal Worker 옵션으로 쉽게 대체할 수 있습니다.

```java
// ═══════════════════════════════════════════════════════════════════════════
// Worker Rate Limiting
// ═══════════════════════════════════════════════════════════════════════════

// Phase 2-A 방식 (복잡하고 에러 발생 가능)
// ─────────────────────────────────────────
RSemaphore semaphore = redisson.getSemaphore("payment:limit");
semaphore.trySetPermits(10);

try {
    if (semaphore.tryAcquire(5, TimeUnit.SECONDS)) {
        paymentService.process(request);
    }
} finally {
    semaphore.release();  // 예외 발생 시 누수 가능!
}

// Temporal 방식 (간단하고 안전!)
// ─────────────────────────────────
WorkerOptions options = WorkerOptions.newBuilder()
    // Activity 동시 실행 수 제한 (RSemaphore 역할!)
    // 최대 10개의 Activity만 동시에 실행
    .setMaxConcurrentActivityExecutionSize(10)

    // Workflow Task 동시 실행 수 제한
    .setMaxConcurrentWorkflowTaskExecutionSize(100)

    // Task Queue 레벨에서 초당 Activity 실행 제한
    // 초당 50개 이하의 Activity만 실행
    .setMaxTaskQueueActivitiesPerSecond(50.0)

    .build();

Worker worker = workerFactory.newWorker("payment-queue", options);
```

**비교표:**

| 항목 | Phase 2-A (RSemaphore) | Temporal Worker |
|------|------------------------|-----------------|
| 설정 방식 | 코드에서 직접 관리 | 선언적 설정 |
| 리소스 누수 | 가능 (finally 누락 시) | 불가능 (자동 관리) |
| Redis 의존 | 필요 | 불필요 |
| 모니터링 | 직접 구현 | Temporal UI 제공 |
| 분산 환경 | 복잡 | 자동 |

---

## 6. 핵심 개념 4: Task Queue - 작업 전달 파이프라인

### 6.1 Task Queue란 무엇인가?

**Task Queue**는 Workflow/Activity Task를 Worker에게 전달하는 대기열입니다.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Task Queue = 택배 허브                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  비유:                                                                       │
│  ───────                                                                     │
│  온라인 쇼핑몰에서 주문하면:                                                  │
│  1. 주문이 물류 허브(Task Queue)로 모임                                      │
│  2. 배송 기사(Worker)가 허브에서 물건을 가져감                               │
│  3. 고객에게 배달                                                            │
│                                                                              │
│  Temporal에서:                                                               │
│  ─────────────                                                               │
│  1. Workflow/Activity Task가 Task Queue에 추가됨                             │
│  2. Worker가 Task Queue를 폴링하여 Task를 가져감                             │
│  3. Worker가 실행하고 결과 반환                                              │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                                                                      │    │
│  │  Client ──▶ [Task] ──▶ ┌──────────────────┐ ──▶ Worker              │    │
│  │                        │    Task Queue    │                         │    │
│  │                        │  "order-queue"   │                         │    │
│  │                        │  [Task][Task]... │                         │    │
│  │                        └──────────────────┘                         │    │
│  │                                                                      │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 6.2 Task Queue의 물리적 위치

**중요한 점**: Task Queue는 여러분의 애플리케이션에 있지 않습니다. **Temporal Server 내부**에 있습니다!

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       Task Queue는 어디에 있는가?                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│   Your Application (Spring Boot)                                             │
│   ┌───────────────────────────────────────┐                                 │
│   │  • Controller (API 엔드포인트)         │                                 │
│   │  • Worker (Task Queue 리스닝)          │ ─── Task Queue는 여기 없음!    │
│   │  • Workflow, Activity 코드             │                                 │
│   └────────────────────┬──────────────────┘                                 │
│                        │                                                     │
│                        │ gRPC 연결                                           │
│                        ▼                                                     │
│   ┌───────────────────────────────────────┐                                 │
│   │       Temporal Server (Docker)        │                                 │
│   │  ┌─────────────────────────────────┐  │                                 │
│   │  │    ★ Task Queue는 여기! ★       │  │                                 │
│   │  │   "order-task-queue"            │  │                                 │
│   │  │   [Task1] [Task2] [Task3]       │  │                                 │
│   │  └─────────────────────────────────┘  │                                 │
│   └────────────────────┬──────────────────┘                                 │
│                        │                                                     │
│                        │ 영구 저장                                           │
│                        ▼                                                     │
│   ┌───────────────────────────────────────┐                                 │
│   │      PostgreSQL (Docker)              │                                 │
│   │   • Event History 저장                │                                 │
│   │   • Task Queue 상태 저장              │                                 │
│   │   • Workflow 메타데이터               │                                 │
│   └───────────────────────────────────────┘                                 │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

**왜 Temporal Server에 있는가?**

| 이유 | 설명 |
|------|------|
| **내구성** | 앱이 죽어도 Task Queue는 살아있음. 작업이 유실되지 않음 |
| **분산** | 여러 Worker가 같은 Queue를 공유할 수 있음 |
| **복구** | 서버 재시작 후 미완료 작업이 자동으로 Worker에게 재분배 |
| **모니터링** | Temporal UI에서 Queue 상태를 실시간 확인 가능 |

### 6.3 서비스별 Task Queue 분리

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       서비스별 Task Queue 분리                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│                         Temporal Server                                      │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                                                                        │  │
│  │  ┌──────────────┐   ┌────────────────┐   ┌──────────────┐            │  │
│  │  │ order-queue  │   │ inventory-queue│   │ payment-queue│            │  │
│  │  │  [Task]      │   │  [Task][Task]  │   │  [Task]      │            │  │
│  │  └──────┬───────┘   └───────┬────────┘   └──────┬───────┘            │  │
│  │         │                   │                   │                     │  │
│  └─────────┼───────────────────┼───────────────────┼─────────────────────┘  │
│            │                   │                   │                        │
│            ▼                   ▼                   ▼                        │
│     ┌──────────────┐   ┌──────────────┐   ┌──────────────┐                 │
│     │ Order Worker │   │  Inventory   │   │   Payment    │                 │
│     │              │   │   Worker     │   │   Worker     │                 │
│     │ • 주문 로직   │   │ • 재고 로직   │   │ • 결제 로직   │                 │
│     │ • 주문 DB    │   │ • 재고 DB    │   │ • 결제 DB    │                 │
│     └──────────────┘   └──────────────┘   └──────────────┘                 │
│                                                                              │
│  장점:                                                                       │
│  ─────                                                                       │
│  • 각 서비스별로 독립적인 스케일링 가능                                        │
│  • 결제 Worker만 10개로 늘려서 결제 처리량 증가                               │
│  • 한 서비스 장애가 다른 서비스에 영향 최소화                                  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 7. Signal과 Query: 외부와의 소통

### 7.1 Signal: 외부에서 Workflow로 이벤트 보내기

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Signal = 편지 보내기                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  실행 중인 Workflow에 외부에서 이벤트를 보낼 수 있습니다.                      │
│                                                                              │
│  비유: 공장에서 일하는 중에 본사에서 "급히 변경 요청!" 전달                     │
│                                                                              │
│  사용 예시:                                                                  │
│  ───────────                                                                 │
│  • 주문 취소 요청                                                            │
│  • 배송 주소 변경                                                            │
│  • 우선순위 변경                                                             │
│  • 추가 정보 제공                                                            │
│                                                                              │
│  특징:                                                                       │
│  ──────                                                                      │
│  • 비동기 (Fire-and-Forget): 응답을 기다리지 않음                             │
│  • Workflow 상태 변경 가능                                                   │
│  • 언제든 보낼 수 있음 (Workflow 실행 중)                                     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

```java
// ═══════════════════════════════════════════════════════════════════════════
// Signal 사용 예시
// ═══════════════════════════════════════════════════════════════════════════

// 1. Workflow 인터페이스에 Signal 정의
@WorkflowInterface
public interface OrderWorkflow {
    @WorkflowMethod
    OrderResult processOrder(OrderRequest request);

    @SignalMethod
    void cancelOrder(String reason);  // Signal 메서드

    @SignalMethod
    void updateShippingAddress(String newAddress);  // 또 다른 Signal
}

// 2. Workflow 구현에서 Signal 처리
public class OrderWorkflowImpl implements OrderWorkflow {
    private boolean cancelled = false;
    private String shippingAddress;

    @Override
    public OrderResult processOrder(OrderRequest request) {
        // 주기적으로 취소 여부 확인
        if (cancelled) {
            return compensate();
        }

        // ... 나머지 로직
    }

    @Override
    public void cancelOrder(String reason) {
        this.cancelled = true;  // Signal이 오면 상태 변경
        log.info("주문 취소 요청: {}", reason);
    }

    @Override
    public void updateShippingAddress(String newAddress) {
        this.shippingAddress = newAddress;
    }
}

// 3. 외부에서 Signal 보내기
@RestController
public class OrderController {

    @PostMapping("/orders/{orderId}/cancel")
    public void cancelOrder(@PathVariable String orderId, @RequestBody CancelRequest request) {
        // 실행 중인 Workflow에 Signal 보내기
        OrderWorkflow workflow = workflowClient.newWorkflowStub(
            OrderWorkflow.class,
            "order-" + orderId  // Workflow ID
        );

        workflow.cancelOrder(request.getReason());  // Signal 전송!
    }
}
```

### 7.2 Query: Workflow 상태 조회하기

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Query = 상태 확인 전화                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  실행 중인 Workflow의 현재 상태를 조회할 수 있습니다.                          │
│                                                                              │
│  비유: 택배 배송 조회 "지금 어디쯤 왔나요?"                                   │
│                                                                              │
│  사용 예시:                                                                  │
│  ───────────                                                                 │
│  • 주문 진행 상태 확인                                                       │
│  • 현재 처리 중인 단계 확인                                                  │
│  • 예상 완료 시간 조회                                                       │
│                                                                              │
│  특징:                                                                       │
│  ──────                                                                      │
│  • 동기 (Synchronous): 즉시 결과 반환                                        │
│  • 읽기 전용: Workflow 상태를 변경하면 안 됨!                                 │
│  • 빠름: Event History 참조 없이 메모리에서 직접 조회                         │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

```java
// ═══════════════════════════════════════════════════════════════════════════
// Query 사용 예시
// ═══════════════════════════════════════════════════════════════════════════

// 1. Workflow 인터페이스에 Query 정의
@WorkflowInterface
public interface OrderWorkflow {
    @WorkflowMethod
    OrderResult processOrder(OrderRequest request);

    @QueryMethod
    OrderStatus getStatus();  // Query 메서드

    @QueryMethod
    String getCurrentStep();  // 또 다른 Query

    @QueryMethod
    OrderDetails getDetails();  // 복잡한 객체도 반환 가능
}

// 2. Workflow 구현에서 Query 처리
public class OrderWorkflowImpl implements OrderWorkflow {
    private OrderStatus status = OrderStatus.PENDING;
    private String currentStep = "초기화";

    @Override
    public OrderResult processOrder(OrderRequest request) {
        currentStep = "주문 생성";
        status = OrderStatus.CREATING;
        activities.createOrder(request);

        currentStep = "재고 예약";
        status = OrderStatus.RESERVING;
        activities.reserveStock(orderId);

        // ... 나머지 로직
    }

    @Override
    public OrderStatus getStatus() {
        return this.status;  // 현재 상태 그대로 반환 (읽기만!)
    }

    @Override
    public String getCurrentStep() {
        return this.currentStep;
    }
}

// 3. 외부에서 Query 호출
@RestController
public class OrderController {

    @GetMapping("/orders/{orderId}/status")
    public OrderStatus getOrderStatus(@PathVariable String orderId) {
        OrderWorkflow workflow = workflowClient.newWorkflowStub(
            OrderWorkflow.class,
            "order-" + orderId
        );

        return workflow.getStatus();  // Query 호출 - 즉시 반환!
    }
}
```

### 7.3 Signal vs Query 비교

| 항목 | Signal | Query |
|------|--------|-------|
| 목적 | Workflow에 이벤트 전달 | Workflow 상태 조회 |
| 동기/비동기 | 비동기 (응답 안 기다림) | 동기 (즉시 응답) |
| 상태 변경 | ✅ 가능 | ❌ 불가 (읽기만) |
| 사용 예 | 취소 요청, 데이터 업데이트 | 진행 상태 확인 |
| 비유 | 편지 보내기 | 전화 걸어 물어보기 |

---

## 8. 전체 흐름 완전 정복

### 8.1 주문 처리 전체 흐름 상세 분석

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     주문 처리 전체 흐름 (Step by Step)                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │                                                                         │ │
│  │  [Step 1] 클라이언트가 주문 요청                                        │ │
│  │  ═══════════════════════════════════                                    │ │
│  │                                                                         │ │
│  │    Client ──POST /orders──▶ API Controller                             │ │
│  │                                   │                                     │ │
│  │                                   │ WorkflowClient.start()              │ │
│  │                                   ▼                                     │ │
│  │                            Temporal Server                              │ │
│  │                                   │                                     │ │
│  │                                   │ "order-queue"에 Workflow Task 추가  │ │
│  │                                   ▼                                     │ │
│  │                            Event History 생성                           │ │
│  │                            [WorkflowExecutionStarted]                   │ │
│  │                                                                         │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │                                                                         │ │
│  │  [Step 2] Worker가 Workflow Task 수신 및 실행                           │ │
│  │  ═══════════════════════════════════════════                            │ │
│  │                                                                         │ │
│  │    Worker ──Long Polling──▶ Temporal Server                            │ │
│  │            ◀── Workflow Task ──                                         │ │
│  │                                                                         │ │
│  │    Worker가 OrderWorkflowImpl.processOrder() 실행 시작                   │ │
│  │                                                                         │ │
│  │    processOrder() 코드:                                                 │ │
│  │    ┌─────────────────────────────────────────────────────────┐         │ │
│  │    │ String orderId = activities.createOrder(request);       │         │ │
│  │    │ // ↑ 여기서 Activity 호출 → Temporal Server에 요청     │         │ │
│  │    └─────────────────────────────────────────────────────────┘         │ │
│  │                                                                         │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │                                                                         │ │
│  │  [Step 3] Activity Task 스케줄 → 실행 → 완료                            │ │
│  │  ═══════════════════════════════════════════                            │ │
│  │                                                                         │ │
│  │    Temporal Server                                                      │ │
│  │       │                                                                 │ │
│  │       │ Event: [ActivityTaskScheduled: createOrder]                     │ │
│  │       │                                                                 │ │
│  │       │ "order-queue"에 Activity Task 추가                              │ │
│  │       ▼                                                                 │ │
│  │    Worker ──가져감──▶ createOrder Activity 실행                          │ │
│  │       │                                                                 │ │
│  │       │ OrderActivitiesImpl.createOrder() 실행                          │ │
│  │       │ → REST API로 주문 서비스 호출                                   │ │
│  │       │ → 결과: orderId = "ORDER-123"                                   │ │
│  │       │                                                                 │ │
│  │       │ 결과 반환                                                       │ │
│  │       ▼                                                                 │ │
│  │    Temporal Server                                                      │ │
│  │       │                                                                 │ │
│  │       │ Event: [ActivityTaskCompleted: result="ORDER-123"]              │ │
│  │                                                                         │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │                                                                         │ │
│  │  [Step 4-6] 나머지 Activity들도 동일한 방식으로 실행                     │ │
│  │  ═══════════════════════════════════════════════                        │ │
│  │                                                                         │ │
│  │    reserveStock(orderId) → ActivityTaskScheduled → 실행 → Completed    │ │
│  │    processPayment(orderId) → ActivityTaskScheduled → 실행 → Completed  │ │
│  │    confirmOrder(orderId) → ActivityTaskScheduled → 실행 → Completed    │ │
│  │                                                                         │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │                                                                         │ │
│  │  [Step 7] Workflow 완료                                                 │ │
│  │  ════════════════════════                                               │ │
│  │                                                                         │ │
│  │    processOrder() 메서드 정상 종료                                       │ │
│  │       │                                                                 │ │
│  │       │ return OrderResult.success("ORDER-123")                         │ │
│  │       ▼                                                                 │ │
│  │    Temporal Server                                                      │ │
│  │       │                                                                 │ │
│  │       │ Event: [WorkflowExecutionCompleted]                             │ │
│  │       │ Result: { success: true, orderId: "ORDER-123" }                 │ │
│  │                                                                         │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 8.2 Event History 상세 분석

Temporal이 기록하는 Event History를 살펴봅시다. 이것이 Durable Execution의 핵심입니다.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Event History 예시                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Event #1:  WorkflowExecutionStarted                                        │
│             └─ input: { productId: "P001", quantity: 2 }                    │
│             └─ workflowId: "order-12345"                                    │
│             └─ taskQueue: "order-queue"                                     │
│                                                                              │
│  Event #2:  WorkflowTaskScheduled                                           │
│             └─ Workflow 코드 실행을 위한 Task 스케줄                         │
│                                                                              │
│  Event #3:  WorkflowTaskStarted                                             │
│             └─ Worker가 Task를 가져감                                        │
│                                                                              │
│  Event #4:  WorkflowTaskCompleted                                           │
│             └─ Workflow 코드가 Activity 호출까지 실행됨                       │
│                                                                              │
│  Event #5:  ActivityTaskScheduled                                           │
│             └─ activityType: "createOrder"                                  │
│             └─ input: { productId: "P001", quantity: 2 }                    │
│                                                                              │
│  Event #6:  ActivityTaskStarted                                             │
│             └─ Worker가 Activity Task를 가져감                               │
│                                                                              │
│  Event #7:  ActivityTaskCompleted                                           │
│             └─ result: "ORDER-123"                                          │
│                                                                              │
│  Event #8:  ActivityTaskScheduled                                           │
│             └─ activityType: "reserveStock"                                 │
│                                                                              │
│  ... (계속)                                                                  │
│                                                                              │
│  Event #N:  WorkflowExecutionCompleted                                      │
│             └─ result: { success: true, orderId: "ORDER-123" }              │
│                                                                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  이 Event History 덕분에:                                                    │
│  ──────────────────────                                                      │
│  • 서버가 죽어도 마지막 이벤트부터 이어서 실행 가능                            │
│  • Event #7까지 완료됐다면, 재시작 시 Event #8부터 진행                       │
│  • 모든 실행 기록이 남아서 디버깅, 감사 가능                                  │
│  • Temporal UI에서 실시간 확인 가능                                          │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 9. Phase 2-A 문제 해결 비교

### 9.1 문제별 해결 비교

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      Phase 2-A vs Temporal 비교                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  [문제 1: 상태 관리]                                                         │
│  ─────────────────────                                                       │
│                                                                              │
│  Phase 2-A:                          Temporal:                               │
│  ┌─────────────────────────────┐     ┌─────────────────────────────┐        │
│  │ sagaState.setStatus(STEP_2);│     │ // 그냥 코드만 작성!         │        │
│  │ sagaRepository.save(state); │     │ step1();                    │        │
│  │ try {                       │     │ step2(); // 여기까지 완료    │        │
│  │   step2();                  │     │ step3(); // 자동 기록!       │        │
│  │   sagaState.setStatus(...)  │     │                             │        │
│  │ } catch ...                 │     └─────────────────────────────┘        │
│  └─────────────────────────────┘                                            │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  [문제 2: 재시도 로직]                                                       │
│  ─────────────────────                                                       │
│                                                                              │
│  Phase 2-A:                          Temporal:                               │
│  ┌─────────────────────────────┐     ┌─────────────────────────────┐        │
│  │ @Retry(name = "paymentSvc") │     │ RetryOptions.newBuilder()   │        │
│  │ @CircuitBreaker(...)        │     │   .setMaximumAttempts(3)    │        │
│  │ public void pay() {         │     │   .setBackoffCoefficient(2) │        │
│  │   // + application.yml 설정  │     │   .build()                  │        │
│  │ }                           │     │ // 끝! 자동 재시도!          │        │
│  └─────────────────────────────┘     └─────────────────────────────┘        │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  [문제 3: 보상 트랜잭션]                                                     │
│  ───────────────────────                                                     │
│                                                                              │
│  Phase 2-A:                          Temporal:                               │
│  ┌─────────────────────────────┐     ┌─────────────────────────────┐        │
│  │ try {                       │     │ Saga saga = new Saga.Builder│        │
│  │   step1();                  │     │   .setParallelCompensation  │        │
│  │   step2();                  │     │   (true).build();           │        │
│  │   step3();                  │     │                             │        │
│  │ } catch (Exception e) {     │     │ saga.addCompensation(       │        │
│  │   compensateStep2();        │     │   () -> cancelPayment());   │        │
│  │   compensateStep1();        │     │                             │        │
│  │   // 보상 실패하면? 또 재시도? │     │ // 실패 시 자동 보상 실행!  │        │
│  │ }                           │     └─────────────────────────────┘        │
│  └─────────────────────────────┘                                            │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  [문제 4: 가시성]                                                            │
│  ─────────────────                                                           │
│                                                                              │
│  Phase 2-A:                          Temporal:                               │
│  ┌─────────────────────────────┐     ┌─────────────────────────────┐        │
│  │ grep "orderId" app.log      │     │  ┌───────────────────────┐  │        │
│  │ // Zipkin 확인               │     │  │ Temporal Web UI       │  │        │
│  │ // DB 상태 조회              │     │  │                       │  │        │
│  │                             │     │  │ Workflow: order-123   │  │        │
│  │ "지금 어디까지 진행됐지?"     │     │  │ Status: Running       │  │        │
│  │                             │     │  │ Current: Step 2       │  │        │
│  │ → 시간 많이 걸림, 찾기 어려움 │     │  │ History: [상세 이력]   │  │        │
│  │                             │     │  └───────────────────────┘  │        │
│  └─────────────────────────────┘     │ // 실시간 확인!             │        │
│                                      └─────────────────────────────┘        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 10. REST 없는 분산 Worker 아키텍처

### 10.1 현재 구조 vs 분산 Worker 구조

현재 프로젝트는 Activity에서 REST API로 각 서비스를 호출합니다. 하지만 Temporal을 사용하면 **REST 없이도** 서비스 간 통신이 가능합니다.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     현재 구조: REST API 사용                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│   orchestrator-temporal                                                      │
│       │                                                                      │
│       │ Activity에서 REST 호출                                               │
│       ▼                                                                      │
│   ┌─────────┐   ┌─────────┐   ┌─────────┐                                   │
│   │ order   │   │inventory│   │ payment │   ← 각각 REST API 서버             │
│   │ :21082  │   │ :21083  │   │ :21084  │                                   │
│   └─────────┘   └─────────┘   └─────────┘                                   │
│                                                                              │
│   문제점:                                                                    │
│   Activity가 REST로 다른 서비스 호출 → 네트워크 오버헤드                      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                REST 없는 구조: 각 서비스가 Worker를 내장                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│   Client                                                                     │
│      │ HTTP (최초 요청만)                                                    │
│      ▼                                                                       │
│   ┌──────────────────┐                                                      │
│   │   API Gateway    │  ← Workflow 시작만 담당                               │
│   └────────┬─────────┘                                                      │
│            │ gRPC (Workflow 시작)                                            │
│            ▼                                                                 │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │                      Temporal Server                                 │   │
│   │  ┌─────────────────────────────────────────────────────────────┐    │   │
│   │  │                     Task Queues                              │    │   │
│   │  │  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐         │    │   │
│   │  │  │order-queue   │ │inventory-que │ │payment-queue │         │    │   │
│   │  │  │ [작업][작업] │ │ [작업][작업] │ │ [작업][작업] │         │    │   │
│   │  │  └──────────────┘ └──────────────┘ └──────────────┘         │    │   │
│   │  └─────────────────────────────────────────────────────────────┘    │   │
│   └───────────┬─────────────────┬─────────────────┬─────────────────────┘   │
│               │ gRPC            │ gRPC            │ gRPC                    │
│               ▼                 ▼                 ▼                         │
│   ┌───────────────────┐ ┌───────────────┐ ┌───────────────┐                │
│   │  Order Service    │ │Inventory Svc  │ │Payment Service│                │
│   │  ┌─────────────┐  │ │ ┌───────────┐ │ │ ┌───────────┐ │                │
│   │  │   Worker    │  │ │ │  Worker   │ │ │ │  Worker   │ │                │
│   │  └─────────────┘  │ │ └───────────┘ │ │ └───────────┘ │                │
│   │  ┌─────────────┐  │ │ ┌───────────┐ │ │ ┌───────────┐ │                │
│   │  │  Activity   │  │ │ │ Activity  │ │ │ │ Activity  │ │                │
│   │  │ (직접 DB)   │  │ │ │ (직접 DB) │ │ │ │ (직접 DB) │ │                │
│   │  └──────┬──────┘  │ │ └─────┬─────┘ │ │ └─────┬─────┘ │                │
│   │         ↓         │ │       ↓       │ │       ↓       │                │
│   │    [order_db]     │ │ [inventory_db]│ │ [payment_db]  │                │
│   └───────────────────┘ └───────────────┘ └───────────────┘                │
│                                                                              │
│   ★ 서비스 간 REST 호출 없음! 모든 통신은 Temporal Server를 통해 ★          │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 10.2 두 방식 비교

| 방식 | 서비스 분리 | 성능 | 복잡도 | 적합한 상황 |
|------|------------|------|--------|-------------|
| **REST API** (현재) | ✅ 분리 | 보통 | 보통 | MSA 학습, 기존 API 재사용 |
| **직접 DB** | ❌ 통합 | 빠름 | 낮음 | 소규모, 빠른 개발 |
| **분산 Worker** | ✅ 완전 분리 | 빠름 | 높음 | 대규모 MSA, 팀별 서비스 |

### 10.3 현재 프로젝트에서 REST를 사용하는 이유

```
┌─────────────────────────────────────────────────────────────────┐
│                    학습 목적상 REST 유지                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. 비교 학습                                                   │
│     orchestrator-pure  (REST) ─┐                               │
│                                ├─→ 동일한 하위 서비스 호출       │
│     orchestrator-temporal (REST) ─┘                             │
│                                                                 │
│     → "같은 서비스를 호출하는데 Temporal이 뭐가 다른지" 비교     │
│                                                                 │
│  2. 기존 서비스 재사용                                          │
│     service-order, inventory, payment는 이미 REST API 구현됨    │
│     → 새로 만들 필요 없이 Activity에서 그대로 호출               │
│                                                                 │
│  3. 실무 현실 반영                                              │
│     대부분의 기존 시스템이 REST API                              │
│     → Temporal 도입 시 기존 API 그대로 활용하는 경우 많음        │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 11. Orchestration vs Choreography

### 11.1 두 가지 분산 시스템 패턴

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                  Orchestration vs Choreography                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  [Orchestration - 오케스트레이션]                                           │
│  ─────────────────────────────────                                          │
│                                                                              │
│       ┌─────────────────┐                                                   │
│       │   Orchestrator  │  ← 중앙 지휘자 (Temporal Workflow)                │
│       └───────┬─────────┘                                                   │
│               │                                                              │
│       ┌───────┼───────┬───────────┐                                         │
│       ▼       ▼       ▼           ▼                                         │
│    ┌─────┐ ┌─────┐ ┌─────┐   ┌─────┐                                       │
│    │Order│ │Stock│ │Pay  │   │Notify│                                       │
│    └─────┘ └─────┘ └─────┘   └─────┘                                       │
│                                                                              │
│  → 중앙에서 순서와 흐름 제어                                                │
│  → 전체 흐름이 코드에 명시적으로 표현됨                                      │
│  → 실패 시 중앙에서 일관된 처리                                             │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  [Choreography - 코레오그래피]                                               │
│  ─────────────────────────────                                               │
│                                                                              │
│    ┌─────┐  이벤트   ┌─────┐  이벤트   ┌─────┐  이벤트   ┌─────┐           │
│    │Order│ ───────▶ │Stock│ ───────▶ │Pay  │ ───────▶ │Notify│           │
│    └─────┘          └─────┘          └─────┘          └─────┘           │
│                                                                              │
│              ▲                 ▲                ▲                           │
│              └─────────────────┴────────────────┘                           │
│                     Message Broker (Kafka/Redis)                             │
│                                                                              │
│  → 각 서비스가 이벤트에 반응                                                 │
│  → 중앙 지휘자 없음, 느슨한 결합                                             │
│  → 전체 흐름 파악이 어려움                                                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 11.2 언제 무엇을 사용하는가?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          선택 가이드                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ✅ Temporal (Orchestration)을 선택하는 경우:                                │
│  ───────────────────────────────────────────                                 │
│  • 복잡한 비즈니스 흐름 (순서가 중요)                                         │
│  • 상태 추적이 중요한 경우                                                   │
│  • 장시간 실행 프로세스                                                      │
│  • 한 팀이 전체 흐름을 책임                                                  │
│  • Saga 패턴 (보상 트랜잭션)                                                 │
│                                                                              │
│  ✅ EDA (Choreography)를 선택하는 경우:                                      │
│  ─────────────────────────────────────                                       │
│  • 느슨한 결합이 중요한 경우                                                  │
│  • 다대다 이벤트 전파 (Pub/Sub)                                               │
│  • 팀 경계를 넘는 통신                                                       │
│  • 실시간 이벤트 처리                                                        │
│  • 동적 구독자 추가                                                          │
│                                                                              │
│  ✅ 함께 사용: 핵심 흐름은 Temporal, 부가 기능은 EDA                         │
│  ───────────────────────────────────────────────                             │
│  예: 주문 처리(Temporal) 완료 후 Kafka로 이벤트 발행                         │
│      → 알림, 분석, 마케팅 서비스가 이벤트 구독                               │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 12. 실습 과제

### 과제 1: Temporal 로컬 실행

> **주의**: `temporalio/auto-setup` 이미지는 **Deprecated** 되었습니다.

**방법 1: Temporal CLI (권장)**

```bash
# Docker로 개발 서버 실행 (가장 간단!)
docker run --rm -p 7233:7233 -p 8233:8233 \
  temporalio/temporal:latest \
  server start-dev --ip 0.0.0.0
```

- 포트 7233: gRPC (Worker 연결)
- 포트 8233: Web UI
- SQLite 내장 (별도 DB 불필요)

**방법 2: Docker Compose (외부 DB 필요 시)**

```yaml
# docker-compose-temporal.yml
services:
  temporal:
    image: temporalio/server:latest
    ports:
      - "7233:7233"
    environment:
      - DB=postgres
      - DB_PORT=5432
      - POSTGRES_USER=temporal
      - POSTGRES_PWD=temporal
      - POSTGRES_SEEDS=postgresql
    depends_on:
      - postgresql

  postgresql:
    image: postgres:15
    environment:
      POSTGRES_USER: temporal
      POSTGRES_PASSWORD: temporal

  temporal-ui:
    image: temporalio/ui:latest
    ports:
      - "8080:8080"
    environment:
      - TEMPORAL_ADDRESS=temporal:7233
```

### 과제 2: Temporal Web UI 탐색

브라우저에서 `http://localhost:8233` (또는 8080)에 접속하여:

1. Namespaces 확인
2. Workflow 목록 확인
3. 실행 중인 Workflow의 Event History 확인

### 과제 3: 개념 정리

다음 질문에 답해보세요:

1. Workflow와 Activity의 차이점은?
2. Worker의 역할은?
3. Task Queue는 왜 Temporal Server에 있는가?
4. Temporal이 Phase 2-A의 어떤 문제를 해결하는가?
5. Workflow에서 Math.random()을 사용하면 안 되는 이유는?

---

## 참고 자료

- [Temporal 공식 문서](https://docs.temporal.io/)
- [Temporal Java SDK](https://github.com/temporalio/sdk-java)
- [Temporal 핵심 개념](https://docs.temporal.io/concepts)
- [Temporal vs 기존 방식 비교](https://temporal.io/how-it-works)
- [Temporal 101 튜토리얼](https://learn.temporal.io/courses/temporal_101/)

---

## 다음 단계

[02-temporal-spring.md](./02-temporal-spring.md) - Temporal + Spring Boot 연동으로 이동
