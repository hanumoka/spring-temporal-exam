# Temporal 핵심 개념 5가지

> **대상**: Temporal을 처음 접하는 개발자
> **목표**: 5가지 핵심 컴포넌트를 이해하고, 전체 흐름이 머릿속에 그려지는 것
> **선행 문서**: [01-temporal-concepts.md](./01-temporal-concepts.md)

---

## 목차

1. [전체 아키텍처 한눈에 보기](#1-전체-아키텍처-한눈에-보기)
2. [Workflow - 비즈니스 흐름의 설계도](#2-workflow---비즈니스-흐름의-설계도)
3. [Activity - 실제 일을 하는 실행자](#3-activity---실제-일을-하는-실행자)
4. [Worker - 묵묵한 일꾼](#4-worker---묵묵한-일꾼)
5. [Task Queue - 작업 전달 파이프라인](#5-task-queue---작업-전달-파이프라인)
6. [Temporal Server - 두뇌](#6-temporal-server---두뇌)
7. [전체 흐름 완전 정복](#7-전체-흐름-완전-정복)
8. [REST 없는 분산 Worker 아키텍처](#8-rest-없는-분산-worker-아키텍처)

---

## 1. 전체 아키텍처 한눈에 보기

5가지 개념이 어떻게 맞물리는지 먼저 전체 그림을 확인합니다.

```
+-----------------------------------------------------------------------+
|                      Temporal 전체 아키텍처                             |
+-----------------------------------------------------------------------+
|                                                                        |
|  +------------------------------------------------------------------+ |
|  |  Temporal Server (두뇌)                                           | |
|  |  역할: 상태 저장, 스케줄링, 재시도 관리 / 포트: 7233 (gRPC)        | |
|  +-------------------------------+----------------------------------+ |
|                                  | gRPC (Long Polling)                |
|  +-------------------------------v----------------------------------+ |
|  |  Worker (Spring Boot)                                             | |
|  |                                                                    | |
|  |  +---------------------------+  +-----------------------------+   | |
|  |  | Workflow (설계도)          |  | Activity (실행자)            |   | |
|  |  | 비즈니스 흐름 정의         |->| 실제 작업 수행               |   | |
|  |  | 주문->재고->결제           |  | HTTP 호출, DB 저장           |   | |
|  |  +---------------------------+  +-------------+---------------+   | |
|  +-----------------------------------------------|-------------------+ |
|                                                   | HTTP/gRPC          |
|  +------------------------------------------------v-----------------+ |
|  |              다른 서비스들 (Order, Inventory, Payment)             | |
|  +------------------------------------------------------------------+ |
+-----------------------------------------------------------------------+
```

---

## 2. Workflow - 비즈니스 흐름의 설계도

### 2.1 Workflow란 무엇인가?

> **"비즈니스 프로세스의 흐름을 정의하는 코드"**

**비유**: 오케스트라의 **지휘자** / 요리의 **레시피**

지휘자는 직접 악기를 연주하지 않습니다.
대신 "바이올린 먼저, 그 다음 첼로, 마지막에 팀파니" 이런 식으로 순서를 정합니다.
마찬가지로 Workflow는 "주문 생성 -> 재고 예약 -> 결제 처리" 같은 **순서**를 정의합니다.

```
  요리 레시피                    Temporal Workflow
  -----------                    -----------------
  1. 계란을 깬다                 1. 주문을 생성한다
  2. 소금을 넣고 섞는다          2. 재고를 예약한다
  3. 팬에 기름을 두른다          3. 결제를 처리한다
  4. 계란물을 붓고 굽는다        4. 배송을 시작한다
  5. 접시에 담는다               5. 알림을 보낸다

  레시피 = 무엇을 어떤 순서로    Workflow = 무엇을 어떤 순서로
  각 동작(Activity)은 별도      각 동작(Activity)은 별도
```

### 2.2 Workflow의 3가지 핵심 특성

| 특성 | 설명 | 비유 |
|------|------|------|
| **결정적(Deterministic)** | 같은 입력이면 항상 같은 실행 경로 | 영화를 다시 틀면 항상 같은 장면 |
| **내구성(Durable)** | 서버가 죽어도 상태가 보존됨 | 자동 저장되는 게임 |
| **장기 실행 가능(Long-Running)** | 몇 초부터 몇 년까지 실행 가능 | HTTP 타임아웃에 구애 없음 |

**왜 결정적이어야 하는가?**
Temporal은 Workflow를 **재실행(Replay)**하여 상태를 복원합니다.
재실행할 때마다 다른 결과가 나오면 상태 복원이 불가능합니다.

### 2.3 Workflow 코드 예시

Temporal Workflow는 **인터페이스**와 **구현체**로 구성됩니다.

```java
// === Workflow 인터페이스 정의 ===

@WorkflowInterface  // Temporal에게 "이것은 Workflow 인터페이스입니다" 알림
public interface OrderWorkflow {

    /**
     * @WorkflowMethod: Workflow의 진입점 (main 메서드와 비슷)
     * - 하나의 인터페이스에 반드시 하나만 있어야 함
     * - Workflow 시작 시 이 메서드가 호출됨
     */
    @WorkflowMethod
    OrderResult processOrder(OrderRequest request);
}

// === Workflow 구현체 작성 ===

public class OrderWorkflowImpl implements OrderWorkflow {

    // Activity Stub 생성 (대리인을 통해 호출)
    private final OrderActivities activities = Workflow.newActivityStub(
        OrderActivities.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .build()
    );

    @Override
    public OrderResult processOrder(OrderRequest request) {

        // Step 1: 주문 생성 (지휘: "바이올린!")
        String orderId = activities.createOrder(request);
        // 여기서 서버가 죽어도, 재시작 후 이 Activity 결과부터 이어서 진행!

        // Step 2: 재고 예약 (지휘: "첼로!")
        String reservationId = activities.reserveStock(orderId);

        // Step 3: 결제 처리 (지휘: "팀파니!")
        String paymentId = activities.processPayment(orderId);

        // Step 4: 주문 확정
        activities.confirmOrder(orderId);

        return OrderResult.success(orderId);
    }
}
```

### 2.4 Workflow에서 절대 하면 안 되는 것들

Workflow는 **결정적(Deterministic)**이어야 하므로 비결정적인 코드는 금지입니다.

```java
// X 잘못된 코드                          // O 올바른 코드
Math.random()                              Workflow.newRandom().nextInt(100)
LocalDateTime.now()                        Workflow.currentTimeMillis()
UUID.randomUUID()                          Workflow.randomUUID()
Thread.sleep(1000)                         Workflow.sleep(Duration.ofSeconds(1))
restTemplate.postForObject(...)            activities.callExternalService(...)
Files.readString(path)                     activities.readFile(path)
```

> **기억하세요**: 랜덤, 시간, UUID -> `Workflow.xxx()` / 외부 호출, 파일 I/O -> Activity로 분리 / `Thread.sleep` -> `Workflow.sleep()`

---

## 3. Activity - 실제 일을 하는 실행자

### 3.1 Activity란 무엇인가?

> **"외부 세계와 상호작용하는 작업 단위"**

**비유**: 오케스트라의 **연주자** / 요리의 **실제 동작**

Workflow(레시피)가 "계란을 깨라"라고 하면, Activity가 실제로 계란을 깹니다.

Activity가 하는 일: REST API 호출, DB 접근, 파일 처리, 외부 서비스 연동, 이메일/SMS 발송

### 3.2 Workflow vs Activity 비교

```
+----------------------------+--------------------------------------------+
|         Workflow            |               Activity                     |
+----------------------------+--------------------------------------------+
| 역할: 흐름 제어 (지휘자)    | 역할: 실제 작업 (연주자)                    |
| 코드: 결정적이어야 함       | 코드: 비결정적 허용                         |
| - new Date() X             | - new Date() O                             |
| - HTTP 호출 X              | - HTTP 호출 O                              |
| 재시도: 상태 복원 후 이어서 | 재시도: 처음부터 다시                       |
| Spring DI: X 불가능        | Spring DI: O 가능 (@Component)             |
| 생성 주체: Temporal         | 생성 주체: Spring                          |
+----------------------------+--------------------------------------------+
```

### 3.3 Activity 코드 예시

Activity도 **인터페이스**와 **구현체**로 구성됩니다. 핵심: **구현체에서 Spring DI를 사용할 수 있다.**

```java
// === Activity 인터페이스 정의 ===

@ActivityInterface
public interface OrderActivities {
    @ActivityMethod String createOrder(OrderRequest request);
    @ActivityMethod String reserveStock(String orderId);
    @ActivityMethod String processPayment(String orderId);
    @ActivityMethod void confirmOrder(String orderId);

    // 보상 트랜잭션용
    @ActivityMethod void cancelOrder(String orderId);
    @ActivityMethod void releaseStock(String reservationId);
    @ActivityMethod void refundPayment(String paymentId);
}

// === Activity 구현체 작성 ===

@Component  // Spring Bean으로 등록! DI 사용 가능!
@RequiredArgsConstructor
public class OrderActivitiesImpl implements OrderActivities {

    private final OrderServiceClient orderClient;       // Spring DI
    private final InventoryServiceClient inventoryClient;
    private final PaymentServiceClient paymentClient;

    @Override
    public String createOrder(OrderRequest request) {
        // Activity에서는 자유롭게 외부 호출 가능!
        OrderResponse response = orderClient.createOrder(request);
        return response.getOrderId();
    }

    @Override
    public String reserveStock(String orderId) {
        return inventoryClient.reserveStock(orderId).getReservationId();
    }

    @Override
    public String processPayment(String orderId) {
        // 여기서 실패하면 Temporal이 자동 재시도!
        return paymentClient.processPayment(orderId).getPaymentId();
    }

    @Override
    public void confirmOrder(String orderId) {
        orderClient.confirmOrder(orderId);
    }

    // 보상 트랜잭션 (cancelOrder, releaseStock, refundPayment 등)
    // 각각 해당 서비스 클라이언트를 통해 롤백 호출
    // ...
}
```

---

## 4. Worker - 묵묵한 일꾼

### 4.1 Worker란 무엇인가?

> **"Workflow와 Activity를 실제로 실행하는 프로세스"**

**비유**: 공장의 **기계** / 오케스트라의 **공연장**

```
  Temporal Server = 본사 (작업 지시서 발행)
  Task Queue      = 공장 입구 (지시서가 쌓이는 곳)
  Worker          = 공장 기계 (지시서를 가져가서 실행)

  +----------------------------------------------------------+
  |  Worker Process                                           |
  |                                                           |
  |  Task Queue: "order-queue"                                |
  |  등록된 Workflow: OrderWorkflowImpl.class                  |
  |  등록된 Activity: OrderActivitiesImpl (Spring Bean)        |
  +----------------------------------------------------------+
```

### 4.2 Worker 동작 원리 (Long Polling)

```
1. Worker 시작: "order-queue 담당합니다!"
2. Long Polling: "작업 있나요?" -----> Temporal Server <----- "이거요!"
3. Task 수신: "processOrder 실행해주세요"
4. 코드 실행: 등록된 구현체에서 메서드 실행
5. 결과 보고: "완료!" -----> Temporal Server
6. 다시 2번으로 (무한 반복)
```

### 4.3 Worker 설정 코드 (Spring Boot)

```java
@Configuration
@RequiredArgsConstructor
public class TemporalWorkerConfig {

    private final WorkflowClient workflowClient;
    private final OrderActivities orderActivities;  // Spring Bean 주입

    @Bean
    public WorkerFactory workerFactory() {
        return WorkerFactory.newInstance(workflowClient);
    }

    @Bean
    public Worker orderWorker(WorkerFactory workerFactory) {
        Worker worker = workerFactory.newWorker("order-queue");

        // Workflow: 클래스(타입) 등록 -> Temporal이 인스턴스 생성 -> Spring DI 불가
        worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);

        // Activity: 인스턴스 등록 -> Spring Bean 그대로 전달 -> Spring DI 가능
        worker.registerActivitiesImplementations(orderActivities);

        return worker;
    }
}
```

### 4.4 Worker의 핵심 특징

- **Temporal Server 외부**에서 실행 (Spring Boot 앱 안에서)
- **여러 대** 띄울 수 있음 (수평 확장)
- Worker가 죽어도 **다른 Worker가 이어받음**
- **Long Polling**으로 Task Queue 감시

### 4.5 Task Token과 Worker 작업 격리

> **"같은 Task를 두 Worker가 동시에 처리할 수 있나요?"** -- 절대 불가능합니다.

Temporal은 **Task Token**을 통해 작업 소유권을 보장합니다.

```
Worker A: "Task 1 주세요" -> Task 1 획득 (Token: AAA)
Worker B: "Task 1 주세요" -> 이미 할당됨, Task 2 획득 (Token: BBB)

결과: Worker A는 Task 1, Worker B는 Task 2 처리. 작업이 뒤섞이지 않음!
```

**Worker 장애 시**: Token이 만료되면 Temporal이 **새 Token으로 새 Task**를 생성하여 다른 Worker에게 할당합니다. 이것은 "탈취"가 아니라 동일 Activity의 "재시도(새 인스턴스)"입니다.

### 4.6 다중 Worker와 스케일링

```
                    Temporal Server
                        |
         +--------------+--------------+
         |              |              |
    +--------+     +--------+     +--------+
    |Worker 1|     |Worker 2|     |Worker 3|
    |Svr A   |     |Svr B   |     |Svr C   |
    +--------+     +--------+     +--------+
    모두 "order-queue" 폴링 -> 자동 로드 밸런싱!
```

- **수평 확장**: Worker 인스턴스 수 증가로 병렬 처리량 확대
- **Task Queue 분리**: 작업 유형별 별도 Queue (order-queue, payment-queue)
- **Worker 옵션**: `setMaxConcurrentActivityExecutionSize(10)` 등으로 동시 실행 수 제한

---

## 5. Task Queue - 작업 전달 파이프라인

### 5.1 Task Queue란 무엇인가?

> **"Worker가 작업을 가져가는 논리적 대기열"**

**비유**: 카페의 **주문 대기표** / 택배 **물류 허브**

```
  Client -> [Task Queue: "order-task-queue"]
            [Task 1][Task 2][Task 3]
                |        |        |
            Worker 1  Worker 2  Worker 3
            (서버 A)  (서버 B)  (서버 C)
            -> 자동 로드밸런싱!
```

### 5.2 Task Queue의 물리적 위치

**중요**: Task Queue는 여러분의 애플리케이션이 아니라 **Temporal Server 내부**에 있습니다!

```
  Spring Boot App                     Temporal Server (Docker)
  +---------------------+            +----------------------------+
  | Controller          |            | ** Task Queue는 여기! **   |
  | Worker              | --gRPC-->  | "order-task-queue"         |
  | Workflow, Activity  |            | [Task1] [Task2] [Task3]    |
  +---------------------+            +-------------+--------------+
                                                    | 영구 저장
                                     +--------------v--------------+
                                     | PostgreSQL                  |
                                     | Event History, Queue 상태   |
                                     +----------------------------+
```

**왜 Temporal Server에 있는가?**

| 이유 | 설명 |
|------|------|
| **내구성** | 앱이 죽어도 Task Queue는 살아있음. 작업 유실 없음 |
| **분산** | 여러 Worker가 같은 Queue를 공유할 수 있음 |
| **복구** | 서버 재시작 후 미완료 작업이 자동으로 Worker에게 재분배 |
| **모니터링** | Temporal UI에서 Queue 상태를 실시간 확인 가능 |

### 5.3 서비스별 Task Queue 분리

```
                     Temporal Server
  +-------------------------------------------------------------+
  | +-----------+   +---------------+   +-----------+            |
  | |order-queue|   |inventory-queue|   |payment-que|            |
  | +-----+-----+   +-------+-------+   +-----+-----+            |
  +-------|-----------------|-----------------|------------------+
          v                 v                 v
   +------------+   +--------------+   +--------------+
   |Order Worker|   |Inventory Wkr |   |Payment Worker|
   | 주문 로직   |   | 재고 로직     |   | 결제 로직     |
   +------------+   +--------------+   +--------------+

  장점: 독립적 스케일링, 장애 격리
```

---

## 6. Temporal Server - 두뇌

### 6.1 Temporal Server의 역할

Temporal Server는 전체 시스템의 **두뇌**입니다. 직접 코드를 실행하지 않지만, 모든 것을 관리합니다.

| 역할 | 설명 |
|------|------|
| **Event History 관리** | 모든 Workflow/Activity 실행 이력을 DB에 저장 |
| **Task Queue 관리** | Task를 적절한 Worker에게 분배 |
| **타이머 관리** | `Workflow.sleep()`, 타임아웃 등을 정확하게 관리 |
| **상태 복구** | Worker 장애 시 다른 Worker에게 작업 재할당 |
| **가시성 제공** | Temporal UI를 통해 실행 상태 실시간 모니터링 |

### 6.2 Temporal Server vs Worker 역할 분리

```
+-------------------------------+----------------------------------+
|       Temporal Server         |           Worker                 |
+-------------------------------+----------------------------------+
| 상태 저장/복구                 | 실제 코드 실행                    |
| 스케줄링 (언제, 누구에게)       | Workflow/Activity 메서드 호출     |
| 재시도 결정                    | 결과 반환                        |
| Event History 기록            | 외부 서비스 호출 (Activity에서)    |
| Task Queue에 Task 추가        | Task Queue에서 Task 가져감       |
| 타임아웃/타이머 관리            | Long Polling으로 대기            |
+-------------------------------+----------------------------------+

핵심: Server = "무엇을 언제 해야 하는지" 결정 / Worker = "실제로 실행"
```

---

## 7. 전체 흐름 완전 정복

5가지 개념이 실제로 어떻게 맞물려 동작하는지 주문 처리 흐름으로 살펴봅시다.

```
+-----------------------------------------------------------------------+
|                  주문 처리 전체 흐름 (Step by Step)                     |
+-----------------------------------------------------------------------+
|                                                                        |
| [Step 1] 클라이언트가 주문 요청                                        |
|                                                                        |
|   Client --POST /orders--> API Controller                             |
|                                  | WorkflowClient.start()             |
|                                  v                                     |
|                           Temporal Server                              |
|                           "order-queue"에 Workflow Task 추가           |
|                           Event: [WorkflowExecutionStarted]            |
|                                                                        |
+-----------------------------------------------------------------------+
|                                                                        |
| [Step 2] Worker가 Workflow Task 수신                                   |
|                                                                        |
|   Worker --Long Polling--> Temporal Server                            |
|           <-- Workflow Task --                                         |
|   Worker가 OrderWorkflowImpl.processOrder() 실행 시작                  |
|   -> activities.createOrder(request)를 호출하면 Temporal Server에 요청  |
|                                                                        |
+-----------------------------------------------------------------------+
|                                                                        |
| [Step 3] Activity Task 스케줄 -> 실행 -> 완료                          |
|                                                                        |
|   Temporal Server: [ActivityTaskScheduled: createOrder]                |
|   Worker --가져감--> OrderActivitiesImpl.createOrder() 실행            |
|          REST API로 주문 서비스 호출 -> 결과: "ORDER-123"              |
|   Temporal Server: [ActivityTaskCompleted: result="ORDER-123"]         |
|                                                                        |
+-----------------------------------------------------------------------+
|                                                                        |
| [Step 4-6] 나머지 Activity도 동일                                      |
|                                                                        |
|   reserveStock  -> Scheduled -> 실행 -> Completed                     |
|   processPayment -> Scheduled -> 실행 -> Completed                    |
|   confirmOrder  -> Scheduled -> 실행 -> Completed                     |
|                                                                        |
+-----------------------------------------------------------------------+
|                                                                        |
| [Step 7] Workflow 완료                                                 |
|                                                                        |
|   processOrder() 정상 종료: return OrderResult.success("ORDER-123")    |
|   Temporal Server: [WorkflowExecutionCompleted]                        |
|                                                                        |
+-----------------------------------------------------------------------+
```

### 7.1 Event History 예시

위 흐름에서 Temporal이 기록하는 Event History입니다.

```
Event #1:  WorkflowExecutionStarted
           +-- input: { productId: "P001", quantity: 2 }
           +-- workflowId: "order-12345"

Event #2:  WorkflowTaskScheduled
Event #3:  WorkflowTaskStarted
Event #4:  WorkflowTaskCompleted

Event #5:  ActivityTaskScheduled   (activityType: "createOrder")
Event #6:  ActivityTaskStarted
Event #7:  ActivityTaskCompleted   (result: "ORDER-123")

Event #8:  ActivityTaskScheduled   (activityType: "reserveStock")
... (계속)

Event #N:  WorkflowExecutionCompleted
           +-- result: { success: true, orderId: "ORDER-123" }
```

이 Event History 덕분에:
- 서버가 죽어도 마지막 이벤트부터 이어서 실행 가능
- Event #7까지 완료됐다면, 재시작 시 Event #8부터 진행
- 모든 실행 기록이 남아서 디버깅, 감사 가능
- Temporal UI에서 실시간 확인 가능

---

## 8. REST 없는 분산 Worker 아키텍처

### 8.1 핵심 인사이트

현재 프로젝트는 Activity에서 REST API로 각 서비스를 호출합니다. 하지만 Temporal을 사용하면 **REST 없이도** 서비스 간 통신이 가능합니다.

```
[현재 구조] orchestrator가 REST로 각 서비스 호출

  orchestrator-temporal --REST--> order(:21082)
                        --REST--> inventory(:21083)
                        --REST--> payment(:21084)

[분산 Worker 구조] 각 서비스가 Worker를 내장, REST 불필요

  +---------------------------------------------------------------+
  |                    Temporal Server                              |
  |  [order-queue]    [inventory-queue]    [payment-queue]          |
  +-------+------------------+--------------------+----------------+
          | gRPC             | gRPC               | gRPC
          v                  v                    v
  +-------------+   +--------------+   +----------------+
  |Order Service|   |Inventory Svc |   |Payment Service |
  | Worker      |   | Worker       |   | Worker         |
  | Activity    |   | Activity     |   | Activity       |
  | (직접 DB)   |   | (직접 DB)    |   | (직접 DB)      |
  +-------------+   +--------------+   +----------------+

  ** 서비스 간 REST 없음! 모든 통신은 Temporal Server를 통해 **
```

### 8.2 두 방식 비교

| 방식 | 서비스 분리 | 성능 | 적합한 상황 |
|------|------------|------|-------------|
| **REST API** (현재) | O | 보통 | MSA 학습, 기존 API 재사용 |
| **분산 Worker** | O 완전 분리 | 빠름 | 대규모 MSA, 팀별 서비스 |

### 8.3 현재 프로젝트에서 REST를 유지하는 이유

1. **비교 학습**: orchestrator-pure와 orchestrator-temporal이 동일한 하위 서비스를 호출하여 차이를 직접 비교
2. **기존 서비스 재사용**: service-order, inventory, payment는 이미 REST API 구현 완료
3. **실무 현실 반영**: Temporal 도입 시 기존 REST API를 그대로 활용하는 경우가 많음

---

## 정리: 5가지 개념 요약

| 개념 | 역할 | 비유 | 핵심 키워드 |
|------|------|------|-------------|
| **Workflow** | 비즈니스 흐름 정의 | 지휘자 / 레시피 | 결정적, 내구성, 장기 실행 |
| **Activity** | 실제 작업 수행 | 연주자 / 요리 동작 | 외부 호출, Spring DI, 재시도 |
| **Worker** | 코드 실행 프로세스 | 공장 기계 | Long Polling, 수평 확장 |
| **Task Queue** | 작업 전달 대기열 | 대기표 / 물류 허브 | Temporal Server 내부, 분리 가능 |
| **Temporal Server** | 전체 관리 두뇌 | 본사 | 상태 저장, 스케줄링, 복구 |

---

> **다음 학습**: [03-durable-execution.md](./03-durable-execution.md) - Durable Execution 완전 이해 (Event History, Replay, 결정적 코드 규칙)
