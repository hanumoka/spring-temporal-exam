# Worker와 Event Flow 완전 분석

> **목표**: Worker 동작 원리와 Event 흐름을 이해하고, 크래시 복구 메커니즘을 파악한다
> **사전 지식**: `02-core-concepts.md`에서 Worker 기본 개념을 이해한 상태

---

## 목차

1. [Worker의 역할](#1-worker의-역할)
2. [Workflow Task vs Activity Task](#2-workflow-task-vs-activity-task)
3. [Worker 내부 구조](#3-worker-내부-구조)
4. [전체 Event Flow](#4-전체-event-flow)
5. [실제 Event History 분석](#5-실제-event-history-분석)
6. [크래시 복구 Flow](#6-크래시-복구-flow)

---

## 1. Worker의 역할

Worker는 Temporal Server가 아닌 **당신의 애플리케이션(JAR) 안에서** 실행된다.
Temporal Server는 Task 분배와 상태 관리만 하고, 실제 코드 실행은 Worker가 담당한다.

```
[Your Application]          [Temporal Server]
┌─────────────────┐         ┌─────────────────┐
│  Spring Boot    │         │  Frontend       │
│  Application    │ <─────> │  History        │
│                 │  gRPC   │  Matching       │
│  ┌───────────┐  │         │                 │
│  │  Worker   │  │         │  (Task Queue    │
│  │ Workflow  │  │         │   관리만 함)    │
│  │ Activity  │  │         │                 │
│  │ 코드 실행 │  │         └─────────────────┘
│  └───────────┘  │
└─────────────────┘
```

Worker가 하는 일 3가지:

| 단계 | 동작 | 설명 |
|------|------|------|
| 1 | Task Queue 폴링 | Long Polling으로 최대 60초 대기하며 Task 수신 |
| 2 | Task 실행 | Workflow Task -> Command 생성 / Activity Task -> 실제 코드 실행 |
| 3 | 결과 보고 | 실행 결과를 Temporal Server에 전송, Event History에 기록 |

---

## 2. Workflow Task vs Activity Task

### 2.1 핵심 차이점

```
┌───────────────────────┬───────────────────────────────────────────────┐
│     Workflow Task     │              Activity Task                    │
├───────────────────────┼───────────────────────────────────────────────┤
│ "다음에 뭘 할지       │ "실제 작업을 실행해라"                        │
│  결정해라"            │                                               │
├───────────────────────┼───────────────────────────────────────────────┤
│ Workflow 코드 실행    │ Activity 코드 실행                            │
│ -> Command 생성       │ -> HTTP 호출 등 실제 작업                     │
├───────────────────────┼───────────────────────────────────────────────┤
│ 생성 시점:            │ 생성 시점:                                    │
│ - Workflow 시작       │ - Workflow Task가                             │
│ - Activity 완료       │   ScheduleActivityTask                        │
│ - Signal 수신         │   Command를 보낼 때                           │
│ - Timer 만료          │                                               │
└───────────────────────┴───────────────────────────────────────────────┘
```

### 2.2 Workflow Task: Command를 생성한다

Workflow Task는 Activity를 **직접 실행하지 않는다**. "이 Activity를 실행해달라"는 Command만 생성한다.

```java
// Workflow 코드
orderId = activities.createOrder(customerId);
//        ^
//        Activity가 바로 실행되지 않음!
//        SDK가 아래 Command를 생성하고 반환:

// Worker가 생성하는 Command:
{
  type: "ScheduleActivityTask",
  activityType: "createOrder",
  input: {customerId: 1}
}
```

### 2.3 Activity Task: 실제 작업을 수행한다

Activity Task를 받은 Worker는 실제로 HTTP 호출 등 외부 작업을 수행한다.

```java
public Long createOrder(Long customerId) {
    // 실제 HTTP 호출!
    Map<String, Object> response = orderClient.post()
            .uri("/api/orders")
            .body(Map.of("customerId", customerId))
            .retrieve()
            .body(Map.class);
    return ((Number) response.get("orderId")).longValue();  // 123 반환
}
```

### 2.4 두 Task의 교대 실행 흐름

```
시간 흐름 ->

[Workflow Task 1]
     │ Workflow 코드 실행, activities.createOrder() 호출
     ▼
Command: "ScheduleActivityTask(createOrder)"
     │ Temporal Server가 Activity Task 생성
     ▼
[Activity Task 1]
     │ HTTP 호출 -> orderId=123
     ▼
Activity 완료 -> Temporal Server가 Workflow Task 생성
     │
     ▼
[Workflow Task 2]
     │ Replay: orderId=123 복원, activities.reserveStock() 호출
     ▼
Command: "ScheduleActivityTask(reserveStock)"
     │
     ▼
[Activity Task 2]
     │ ... (반복)
```

---

## 3. Worker 내부 구조

### 3.1 구성 요소

```
Worker
┌─────────────────────────────────────────────────────────────┐
│                                                              │
│  ┌─────────────────┐         ┌─────────────────┐            │
│  │ Workflow Poller  │         │ Activity Poller  │            │
│  │ - Long Polling   │         │ - Long Polling   │            │
│  │ - Workflow Task  │         │ - Activity Task  │            │
│  │   수신           │         │   수신           │            │
│  └────────┬────────┘         └────────┬────────┘            │
│           │                           │                      │
│           ▼                           ▼                      │
│  ┌─────────────────┐         ┌─────────────────┐            │
│  │Workflow Executor │         │Activity Executor │            │
│  │ - Workflow 코드  │         │ - Activity 코드  │            │
│  │   실행           │         │   실행           │            │
│  │ - Command 생성   │         │ - 결과 반환      │            │
│  └────────┬────────┘         └────────┬────────┘            │
│           └──────────┬───────────────┘                       │
│                      ▼                                       │
│             ┌─────────────────┐                              │
│             │  gRPC Client    │ (결과 전송 / Event 수신)     │
│             └─────────────────┘                              │
│                                                              │
│  설정 (프로젝트 기준):                                       │
│  Workflow/Activity Executor Threads: 200                     │
│  Max Concurrent Workflow/Activity Tasks: 200                 │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 Long Polling 동작 방식

```
Short Polling (X - Temporal이 사용하지 않음):
  Worker: "Task 있어?"  -> "없어" -> (1초) -> "있어?" -> "없어" -> ...
  (반복적 네트워크 낭비)

Long Polling (O - Temporal이 사용):

  Worker                              Temporal Server
    |                                       |
    | ---- "Task 있어?" (Poll 요청) ------> |
    |                                       |
    | (대기... 최대 60초)                   | (Task 없으면 대기)
    |                                       |
    |                                       | <- Task 도착!
    |                                       |
    | <---- "Task 왔어!" (Task 전달) ------ |
    |                                       |
    | (Task 처리)                           |
    |                                       |
    | ---- "다음 Task 있어?" -------------> |
    ... (반복)
```

장점: 네트워크 트래픽 최소화, Task 도착 시 즉시 전달, 연결 유지로 오버헤드 감소

---

## 4. 전체 Event Flow

### 주문 Workflow 전체 흐름

```
Client         Orchestrator       Temporal        Worker      Services
  |                |              Server            |            |
  | --- POST ----> |                |               |            |
  |    /orders     |                |               |            |
  |                | - StartWorkflow -->            |            |
  |                |   (gRPC)       |               |            |
  |                |                |               |            |
  |                |                | Ev1: WorkflowExecutionStarted
  |                |                | Ev2: WorkflowTaskScheduled
  |                |                |               |            |
  |                |                | <-- Poll ---- |            |
  |                |                | -- Task ----> |            |
  |                |                |               |            |
  |                |                | Ev3: WorkflowTaskStarted
  |                |                |               | (Workflow  |
  |                |                |               |  코드 실행)|
  |                |                | <-- Command - |            |
  |                |                | (ScheduleActivityTask)     |
  |                |                |               |            |
  |                |                | Ev4: WorkflowTaskCompleted
  |                |                | Ev5: ActivityTaskScheduled
  |                |                |               |            |
  |                |                | <-- Poll ---- |            |
  |                |                | -- Task ----> |            |
  |                |                |               |            |
  |                |                | Ev6: ActivityTaskStarted
  |                |                |               | -- HTTP -> |
  |                |                |               | createOrder|
  |                |                |               | <- 결과 -- |
  |                |                |               | orderId=123|
  |                |                | <-- 결과 ---- |            |
  |                |                |               |            |
  |                |                | Ev7: ActivityTaskCompleted
  |                |                | Ev8: WorkflowTaskScheduled
  |                |                |               |            |
  |                |                | (반복: reserveStock, payment 등)
  |                |                |               |            |
  |                |                | EvN: WorkflowExecutionCompleted
  |                |                |               |            |
  |                | <-- 결과 ---- |               |            |
  | <-- 200 OK -- |                |               |            |
  |  {orderId:123} |                |               |            |
```

### Event 패턴 요약

하나의 Activity가 실행될 때 기록되는 Event 패턴:

```
WorkflowTaskScheduled   -> Worker에게 Workflow Task 배정
WorkflowTaskStarted     -> Worker가 Task 수신
WorkflowTaskCompleted   -> Command(ScheduleActivityTask) 생성
ActivityTaskScheduled   -> Activity Task Queue에 추가
ActivityTaskStarted     -> Worker가 Activity Task 수신
ActivityTaskCompleted   -> Activity 실행 결과 기록
```

이 패턴이 Activity 수만큼 반복된다.

---

## 5. 실제 Event History 분석

### 5.1 성공 시나리오

```
Workflow ID: order-abc12345 / 상태: Completed

┌─────┬──────────────────────────────┬────────────────────────────┐
│ ID  │ Event Type                   │ 상세 정보                  │
├─────┼──────────────────────────────┼────────────────────────────┤
│ 1   │ WorkflowExecutionStarted     │ input: {customerId:1,...}  │
│ 2   │ WorkflowTaskScheduled        │ taskQueue: order-task-queue│
│ 3   │ WorkflowTaskStarted          │ identity: worker-host-1    │
│ 4   │ WorkflowTaskCompleted        │ commands: [createOrder]    │
├─────┼──────────────────────────────┼────────────────────────────┤
│ 5   │ ActivityTaskScheduled        │ createOrder, timeout: 30s  │
│ 6   │ ActivityTaskStarted          │ attempt: 1                 │
│ 7   │ ActivityTaskCompleted        │ result: 123 (orderId)      │
├─────┼──────────────────────────────┼────────────────────────────┤
│ 8   │ WorkflowTaskScheduled        │ (Workflow 재개)            │
│ 9   │ WorkflowTaskStarted          │                            │
│ 10  │ WorkflowTaskCompleted        │ commands: [reserveStock]   │
├─────┼──────────────────────────────┼────────────────────────────┤
│ 11  │ ActivityTaskScheduled        │ reserveStock               │
│ 12  │ ActivityTaskStarted          │ attempt: 1                 │
│ 13  │ ActivityTaskCompleted        │ result: null (void)        │
├─────┼──────────────────────────────┼────────────────────────────┤
│ ... │ (createPayment, approve,     │ ...                        │
│     │  confirm 등 동일 패턴 반복)  │                            │
├─────┼──────────────────────────────┼────────────────────────────┤
│ N-1 │ WorkflowTaskCompleted        │ commands: [Complete]       │
│ N   │ WorkflowExecutionCompleted   │ result: {success:true,     │
│     │                              │  orderId:123, paymentId:456│
│     │                              │  workflowId:order-abc...}  │
└─────┴──────────────────────────────┴────────────────────────────┘
```

### 5.2 실패 + 보상 시나리오 (재고 부족)

```
Workflow ID: order-def67890 / 상태: Completed (비즈니스 실패, Workflow는 정상 종료)

┌─────┬──────────────────────────────┬────────────────────────────┐
│ ID  │ Event Type                   │ 상세 정보                  │
├─────┼──────────────────────────────┼────────────────────────────┤
│ 1-4 │ (Workflow 시작)              │ input: {quantity:999,...}   │
├─────┼──────────────────────────────┼────────────────────────────┤
│ 5-7 │ createOrder Activity         │ result: 789 (orderId)      │
├─────┼──────────────────────────────┼────────────────────────────┤
│8-10 │ Workflow 재개                │ commands: [reserveStock]   │
├─────┼──────────────────────────────┼────────────────────────────┤
│ 11  │ ActivityTaskScheduled        │ reserveStock (qty:999)     │
│ 12  │ ActivityTaskStarted          │ attempt: 1                 │
│ 13  │ ActivityTaskFailed           │ INSUFFICIENT_STOCK         │
├─────┼──────────────────────────────┼────────────────────────────┤
│ 14  │ ActivityTaskScheduled        │ reserveStock (재시도 1)    │
│ 15  │ ActivityTaskStarted          │ attempt: 2                 │
│ 16  │ ActivityTaskFailed           │ (또 실패)                  │
├─────┼──────────────────────────────┼────────────────────────────┤
│ 17  │ ActivityTaskScheduled        │ reserveStock (재시도 2)    │
│ 18  │ ActivityTaskStarted          │ attempt: 3                 │
│ 19  │ ActivityTaskFailed           │ (3회 실패, 재시도 종료)    │
├─────┼──────────────────────────────┼────────────────────────────┤
│20-22│ Workflow 재개                │ commands: [cancelOrder]    │
│     │                              │ (Saga 보상 시작!)          │
├─────┼──────────────────────────────┼────────────────────────────┤
│23-25│ cancelOrder Activity         │ (주문 취소 성공)           │
├─────┼──────────────────────────────┼────────────────────────────┤
│26-28│ Workflow 완료                │ commands: [Complete]       │
│ 29  │ WorkflowExecutionCompleted   │ result: {success:false,    │
│     │                              │  error:"재고 부족"}        │
└─────┴──────────────────────────────┴────────────────────────────┘

포인트:
- Event 13, 16, 19: Activity 3회 재시도 후 최종 실패
- Event 23-25: Saga 보상(cancelOrder) 자동 실행
- Workflow 자체는 "정상 종료" (비즈니스 실패를 결과로 반환)
```

---

## 6. 크래시 복구 Flow

### 6.1 Worker 크래시 시나리오

```
상황: T1(createOrder) 완료, T2(reserveStock) 완료,
      T3(createPayment) 진행 중 Worker 크래시!

Event History (크래시 시점):
┌─────┬──────────────────────────────┬────────────────────────────┐
│ 7   │ ActivityTaskCompleted        │ createOrder 완료           │
│ 13  │ ActivityTaskCompleted        │ reserveStock 완료          │
│ 17  │ ActivityTaskScheduled        │ createPayment              │
│ 18  │ ActivityTaskStarted          │ <-- 여기서 Worker 크래시!  │
└─────┴──────────────────────────────┴────────────────────────────┘

복구 과정:
═══════════════════════════════════════════════════════════════

1. Temporal Server가 Activity 타임아웃 감지 (30초 후)
   -> Event 19: ActivityTaskTimedOut

2. 재시도 정책에 따라 Activity 재스케줄
   -> Event 20: ActivityTaskScheduled (attempt: 2)

3. 다른 Worker(또는 재시작된 Worker)가 Activity Task 수신
   -> Event 21: ActivityTaskStarted
   -> Event 22: ActivityTaskCompleted {result: 456}

4. Workflow Task 스케줄 -> Worker가 Replay 시작
   ┌──────────────────────────────────────────────────────┐
   │  processOrder() 실행 (처음부터!)                      │
   │                                                       │
   │  orderId = activities.createOrder();                  │
   │  // Event 7에서 결과(123) 복원 -> 실행 안 함          │
   │                                                       │
   │  activities.reserveStock();                            │
   │  // Event 13에서 완료 확인 -> 실행 안 함              │
   │                                                       │
   │  paymentId = activities.createPayment();              │
   │  // Event 22에서 결과(456) 복원 -> 실행 안 함         │
   │                                                       │
   │  activities.approvePayment();                          │
   │  // Event History에 없음 -> 새 Command 생성!          │
   └──────────────────────────────────────────────────────┘

5. 이후 정상 진행...

핵심:
- T1, T2 Activity는 재실행되지 않음 (Event History에 결과 있음)
- T3 Activity만 재실행됨 (타임아웃 후 재시도)
- Workflow 코드는 처음부터 실행되지만, Replay로 빠르게 복원
```

### 6.2 멱등성의 중요성

크래시 복구 시 주의할 점이 있다.

```
문제 상황:
  1. createPayment Activity 실행
  2. service-payment에서 결제 생성 성공 (paymentId=456)
  3. 응답 반환 직전 Worker 크래시!
  4. Temporal Server는 결과를 모름 -> Activity 재시도

멱등성 없이:
  1차: paymentId=456 생성 (DB 저장)
  2차: paymentId=457 생성 (중복! 데이터 불일치)

멱등성 적용 (우리 프로젝트):
  header("X-Idempotency-Key", sagaId + "-payment-create")

  1차: 키="order-abc-payment-create" -> paymentId=456 생성, 캐시 저장
  2차: 키="order-abc-payment-create" -> 캐시에서 456 반환 (DB 미생성)
  결과: 동일한 paymentId=456 -> 데이터 일관성 유지
```

Phase 2에서 배운 멱등성이 Temporal에서도 **반드시 필요한 이유**가 바로 이것이다.

---

## 요약

```
┌─────────────────────────────────────────────────────────────────┐
│                    Worker 동작 핵심 요약                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. Worker는 당신의 애플리케이션 안에서 실행된다               │
│     (Temporal Server 안에 있지 않음!)                           │
│                                                                  │
│  2. Worker는 2가지 Task를 처리한다:                             │
│     Workflow Task: 코드 실행 -> Command 생성                    │
│     Activity Task: 코드 실행 -> 실제 작업 수행                  │
│                                                                  │
│  3. Workflow 코드는 Activity를 직접 실행하지 않는다             │
│     "이 Activity를 실행해달라"는 Command만 생성한다             │
│                                                                  │
│  4. Activity 완료 후 Workflow는 Replay로 재실행된다             │
│     이미 완료된 Activity는 Event History에서 결과를 가져온다    │
│                                                                  │
│  5. 크래시 시 Event History 덕분에:                             │
│     - 완료된 Activity는 재실행되지 않음                         │
│     - 진행 중이던 Activity만 재시도                             │
│                                                                  │
│  6. 멱등성은 여전히 필요하다                                    │
│     "실행됐지만 결과 전달 전 크래시" 상황을 대비                │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 참고 자료

- [What is a Temporal Worker?](https://docs.temporal.io/workers)
- [Task Queues](https://docs.temporal.io/task-queue)
- [Events and Event History](https://docs.temporal.io/workflow-execution/event)
- [Worker Performance](https://docs.temporal.io/develop/worker-performance)

---

*이전 학습: `02-core-concepts.md` | 다음 학습: `05-retry-timeout.md`*
