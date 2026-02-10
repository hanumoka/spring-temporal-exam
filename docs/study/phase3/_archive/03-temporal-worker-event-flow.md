# Temporal Worker와 Event Flow 완전 분석

> **목표**: Worker가 어떻게 동작하는지, Event가 어떤 순서로 발생하는지 완전히 이해
> **작성일**: 2026-02-05

---

## 목차

1. [Worker란 무엇인가?](#1-worker란-무엇인가)
2. [Workflow Task vs Activity Task](#2-workflow-task-vs-activity-task)
3. [Worker 내부 구조](#3-worker-내부-구조)
4. [전체 Event Flow 상세 분석](#4-전체-event-flow-상세-분석)
5. [단계별 상세 설명](#5-단계별-상세-설명)
6. [실제 Event History 분석](#6-실제-event-history-분석)
7. [크래시 복구 Flow](#7-크래시-복구-flow)

---

## 1. Worker란 무엇인가?

### 1.1 Worker의 역할

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Worker의 역할                                    │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Worker는 "일꾼"입니다.                                                 │
│                                                                         │
│  비유: 음식점 주방                                                      │
│  ─────────────────                                                      │
│                                                                         │
│  ┌──────────────┐         ┌──────────────┐         ┌──────────────┐    │
│  │   손님       │         │   주문 전표   │         │    주방장     │    │
│  │   (Client)   │ ──────→ │   (Task     │ ──────→ │   (Worker)   │    │
│  │              │  주문   │    Queue)    │  가져감  │              │    │
│  └──────────────┘         └──────────────┘         └──────┬───────┘    │
│                                                           │            │
│                                                           │ 조리       │
│                                                           ▼            │
│                           ┌──────────────┐         ┌──────────────┐    │
│                           │   완성된     │ ←────── │   요리 완성   │    │
│                           │   요리       │  결과    │              │    │
│                           └──────────────┘         └──────────────┘    │
│                                                                         │
│  Temporal에서:                                                         │
│  • 손님 = Client (Workflow 시작 요청)                                  │
│  • 주문 전표 = Task Queue (작업 대기열)                                │
│  • 주방장 = Worker (실제 코드 실행)                                    │
│  • 요리 = Workflow/Activity 실행 결과                                  │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 1.2 Worker가 하는 일

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     Worker가 하는 일 3가지                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  1. Task Queue 폴링 (Long Polling)                                     │
│     ┌─────────────────────────────────────────────────────────────┐    │
│     │  Worker: "할 일 있나요?" (최대 60초 대기)                    │    │
│     │  Server: "아직 없어요..." (대기)                             │    │
│     │  Server: "Task 왔어요!" (Task 전달)                          │    │
│     └─────────────────────────────────────────────────────────────┘    │
│                                                                         │
│  2. Task 실행                                                          │
│     ┌─────────────────────────────────────────────────────────────┐    │
│     │  • Workflow Task: Workflow 코드 실행 → Command 생성          │    │
│     │  • Activity Task: Activity 코드 실행 → 결과 생성             │    │
│     └─────────────────────────────────────────────────────────────┘    │
│                                                                         │
│  3. 결과 보고                                                          │
│     ┌─────────────────────────────────────────────────────────────┐    │
│     │  Worker → Server: "실행 결과입니다"                          │    │
│     │  Server: Event History에 기록                                │    │
│     └─────────────────────────────────────────────────────────────┘    │
│                                                                         │
│  핵심: Worker는 Temporal Server에 있는 코드가 아니라,                  │
│        당신의 애플리케이션 안에서 실행됩니다!                          │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 1.3 Worker의 위치

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     Worker는 어디에 있는가?                             │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ❌ 잘못된 이해:                                                        │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  Temporal Server 안에 Worker가 있다?                            │   │
│  │  (X) 아닙니다!                                                  │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  ✅ 올바른 이해:                                                        │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                                                                  │   │
│  │  [Your Application]          [Temporal Server]                  │   │
│  │  ┌─────────────────┐         ┌─────────────────┐               │   │
│  │  │                 │         │                 │               │   │
│  │  │  Spring Boot    │         │  Frontend       │               │   │
│  │  │  Application    │ ◄─────► │  History        │               │   │
│  │  │                 │  gRPC   │  Matching       │               │   │
│  │  │  ┌───────────┐  │         │                 │               │   │
│  │  │  │  Worker   │  │         │  (Task Queue    │               │   │
│  │  │  │           │  │         │   관리만 함)    │               │   │
│  │  │  │ Workflow  │  │         │                 │               │   │
│  │  │  │ Activity  │  │         └─────────────────┘               │   │
│  │  │  │ 코드 실행 │  │                                           │   │
│  │  │  └───────────┘  │                                           │   │
│  │  └─────────────────┘                                           │   │
│  │                                                                  │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  Worker는 당신의 애플리케이션(JAR) 안에서 실행됩니다.                  │
│  Temporal Server는 Task 분배와 상태 관리만 합니다.                     │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Workflow Task vs Activity Task

### 2.1 두 가지 Task 타입

```
┌─────────────────────────────────────────────────────────────────────────┐
│                  Workflow Task vs Activity Task                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Task Queue에는 2가지 종류의 Task가 있습니다:                          │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                                                                  │   │
│  │   Task Queue: "order-task-queue"                                │   │
│  │   ┌────────────────────────────────────────────────────────┐   │   │
│  │   │                                                         │   │   │
│  │   │  [Workflow Task]  [Activity Task]  [Workflow Task] ... │   │   │
│  │   │       │                 │               │              │   │   │
│  │   │       │                 │               │              │   │   │
│  │   │       ▼                 ▼               ▼              │   │   │
│  │   │  "Workflow 코드    "Activity 코드   "다음 단계        │   │   │
│  │   │   실행해줘"         실행해줘"        결정해줘"        │   │   │
│  │   │                                                         │   │   │
│  │   └────────────────────────────────────────────────────────┘   │   │
│  │                                                                  │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Workflow Task 상세

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Workflow Task란?                                 │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Workflow Task = "Workflow 코드를 실행하고 다음에 뭘 할지 결정해라"    │
│                                                                         │
│  언제 생성되나?                                                        │
│  ─────────────                                                          │
│  1. Workflow 처음 시작될 때                                            │
│  2. Activity가 완료되었을 때                                           │
│  3. Timer가 만료되었을 때                                              │
│  4. Signal을 받았을 때                                                 │
│  5. Child Workflow가 완료되었을 때                                     │
│                                                                         │
│  Workflow Task 실행 결과:                                              │
│  ─────────────────────────                                              │
│  Worker가 Workflow 코드를 실행하고 "Command"를 생성합니다.             │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  Workflow 코드:                                                  │   │
│  │  ─────────────                                                   │   │
│  │  orderId = activities.createOrder();  // Activity 호출          │   │
│  │                                                                  │   │
│  │  Worker가 생성하는 Command:                                      │   │
│  │  ─────────────────────────                                       │   │
│  │  {                                                               │   │
│  │    type: "ScheduleActivityTask",                                │   │
│  │    activityType: "createOrder",                                 │   │
│  │    input: {...}                                                 │   │
│  │  }                                                               │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  중요: Workflow Task는 Activity를 직접 실행하지 않습니다!              │
│        "이 Activity를 실행해달라"는 Command만 생성합니다.              │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2.3 Activity Task 상세

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Activity Task란?                                 │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Activity Task = "Activity 코드를 실제로 실행해라"                     │
│                                                                         │
│  언제 생성되나?                                                        │
│  ─────────────                                                          │
│  Workflow Task가 "ScheduleActivityTask" Command를 보내면               │
│  Temporal Server가 Activity Task를 Task Queue에 추가합니다.            │
│                                                                         │
│  Activity Task 실행:                                                   │
│  ─────────────────                                                      │
│  Worker가 실제로 Activity 코드를 실행합니다.                           │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  Activity 코드:                                                  │   │
│  │  ─────────────                                                   │   │
│  │  public Long createOrder(Long customerId) {                     │   │
│  │      // 실제 HTTP 호출!                                          │   │
│  │      Response response = restClient.post()                      │   │
│  │          .uri("/api/orders")                                    │   │
│  │          .body(Map.of("customerId", customerId))                │   │
│  │          .retrieve()                                            │   │
│  │          .body(Map.class);                                      │   │
│  │                                                                  │   │
│  │      return response.get("orderId");  // 결과 반환              │   │
│  │  }                                                               │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  Activity Task 결과:                                                   │
│  ─────────────────                                                      │
│  실행 결과(orderId=123)를 Temporal Server에 보고합니다.                │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2.4 두 Task의 관계

```
┌─────────────────────────────────────────────────────────────────────────┐
│              Workflow Task와 Activity Task의 관계                       │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  시간 흐름 →                                                            │
│                                                                         │
│  [Workflow Task 1]                                                     │
│       │                                                                 │
│       │ Workflow 코드 실행                                              │
│       │ activities.createOrder() 호출                                   │
│       │                                                                 │
│       ▼                                                                 │
│  Command: "ScheduleActivityTask(createOrder)"                          │
│       │                                                                 │
│       │ Temporal Server가 Activity Task 생성                           │
│       ▼                                                                 │
│  [Activity Task 1]                                                     │
│       │                                                                 │
│       │ Activity 코드 실행                                              │
│       │ HTTP 호출 → 결과: orderId=123                                   │
│       │                                                                 │
│       ▼                                                                 │
│  Activity 완료 → Temporal Server가 Workflow Task 생성                  │
│       │                                                                 │
│       ▼                                                                 │
│  [Workflow Task 2]                                                     │
│       │                                                                 │
│       │ Workflow 코드 실행 (이어서)                                     │
│       │ orderId = 123 (Activity 결과)                                   │
│       │ activities.reserveStock() 호출                                  │
│       │                                                                 │
│       ▼                                                                 │
│  Command: "ScheduleActivityTask(reserveStock)"                         │
│       │                                                                 │
│       ▼                                                                 │
│  [Activity Task 2]                                                     │
│       │                                                                 │
│       ... (반복)                                                        │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Worker 내부 구조

### 3.1 Worker의 구성 요소

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      Worker 내부 구조                                   │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Worker                                                                │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                                                                  │   │
│  │  ┌─────────────────┐      ┌─────────────────┐                   │   │
│  │  │ Workflow Poller │      │ Activity Poller │                   │   │
│  │  │ (스레드 풀)      │      │ (스레드 풀)      │                   │   │
│  │  │                 │      │                 │                   │   │
│  │  │ - Long Polling  │      │ - Long Polling  │                   │   │
│  │  │ - Workflow Task │      │ - Activity Task │                   │   │
│  │  │   수신          │      │   수신          │                   │   │
│  │  └────────┬────────┘      └────────┬────────┘                   │   │
│  │           │                        │                            │   │
│  │           ▼                        ▼                            │   │
│  │  ┌─────────────────┐      ┌─────────────────┐                   │   │
│  │  │Workflow Executor│      │Activity Executor│                   │   │
│  │  │ (스레드 풀)      │      │ (스레드 풀)      │                   │   │
│  │  │                 │      │                 │                   │   │
│  │  │ - Workflow 코드 │      │ - Activity 코드 │                   │   │
│  │  │   실행          │      │   실행          │                   │   │
│  │  │ - Command 생성  │      │ - 결과 반환     │                   │   │
│  │  └────────┬────────┘      └────────┬────────┘                   │   │
│  │           │                        │                            │   │
│  │           └────────────┬───────────┘                            │   │
│  │                        │                                        │   │
│  │                        ▼                                        │   │
│  │               ┌─────────────────┐                               │   │
│  │               │  gRPC Client    │                               │   │
│  │               │ (Temporal SDK)  │                               │   │
│  │               │                 │                               │   │
│  │               │ - 결과 전송     │                               │   │
│  │               │ - Event 수신    │                               │   │
│  │               └─────────────────┘                               │   │
│  │                                                                  │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  설정 예시 (우리 프로젝트 기준):                                       │
│  • Workflow Executor Threads: 200                                      │
│  • Activity Executor Threads: 200                                      │
│  • Max Concurrent Workflow Tasks: 200                                  │
│  • Max Concurrent Activity Tasks: 200                                  │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Poller 동작 방식

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     Long Polling 동작 방식                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Short Polling (❌ Temporal이 사용하지 않음):                           │
│  ─────────────────────────────────────────                              │
│  Worker: "Task 있어?"  Server: "없어"                                  │
│  (1초 후)                                                               │
│  Worker: "Task 있어?"  Server: "없어"                                  │
│  (1초 후)                                                               │
│  Worker: "Task 있어?"  Server: "없어"                                  │
│  ... (계속 반복, 네트워크 낭비)                                        │
│                                                                         │
│  ─────────────────────────────────────────────────────────────────────  │
│                                                                         │
│  Long Polling (✅ Temporal이 사용):                                     │
│  ───────────────────────────────────                                    │
│                                                                         │
│  Worker                              Temporal Server                    │
│    │                                       │                            │
│    │ ──── "Task 있어?" (Poll 요청) ─────→ │                            │
│    │                                       │                            │
│    │ (대기... 최대 60초)                   │ (Task 없으면 대기)         │
│    │                                       │                            │
│    │                                       │ ← Task 도착!               │
│    │                                       │                            │
│    │ ←──── "Task 왔어!" (Task 전달) ────── │                            │
│    │                                       │                            │
│    │ (Task 처리)                           │                            │
│    │                                       │                            │
│    │ ──── "다음 Task 있어?" ────────────→ │                            │
│    │                                       │                            │
│    ... (반복)                                                           │
│                                                                         │
│  장점:                                                                  │
│  • 네트워크 트래픽 최소화                                              │
│  • Task 도착 시 즉시 전달 (지연 최소화)                                │
│  • 연결 유지로 오버헤드 감소                                           │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 4. 전체 Event Flow 상세 분석

### 4.1 주문 Workflow 전체 흐름

이제 주문 Workflow의 전체 흐름을 **매우 상세하게** 분석합니다.

```
┌─────────────────────────────────────────────────────────────────────────┐
│              주문 Workflow 전체 Event Flow (단순화 버전)                │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  참여자:                                                                │
│  • Client: REST API 호출자                                             │
│  • Orchestrator: orchestrator-temporal 애플리케이션                    │
│  • Temporal Server: Task 관리 및 Event 저장                            │
│  • Worker: Orchestrator 안에서 실행되는 Worker                         │
│  • Services: order/inventory/payment 서비스                            │
│                                                                         │
│  ═══════════════════════════════════════════════════════════════════   │
│                                                                         │
│  Client         Orchestrator       Temporal        Worker      Services │
│    │                │              Server            │            │     │
│    │                │                │               │            │     │
│    │ ─── POST ────→ │                │               │            │     │
│    │    /orders     │                │               │            │     │
│    │                │                │               │            │     │
│    │                │ ─ StartWorkflow ─→             │            │     │
│    │                │   (gRPC)       │               │            │     │
│    │                │                │               │            │     │
│    │                │                │ Event 1: WorkflowExecutionStarted│
│    │                │                │ Event 2: WorkflowTaskScheduled   │
│    │                │                │               │            │     │
│    │                │                │ ←── Poll ──── │            │     │
│    │                │                │               │            │     │
│    │                │                │ ─ Task ─────→ │            │     │
│    │                │                │               │            │     │
│    │                │                │ Event 3: WorkflowTaskStarted     │
│    │                │                │               │            │     │
│    │                │                │               │ (Workflow  │     │
│    │                │                │               │  코드 실행)│     │
│    │                │                │               │            │     │
│    │                │                │ ← Command ─── │            │     │
│    │                │                │ (ScheduleActivityTask)     │     │
│    │                │                │               │            │     │
│    │                │                │ Event 4: WorkflowTaskCompleted   │
│    │                │                │ Event 5: ActivityTaskScheduled   │
│    │                │                │               │            │     │
│    │                │                │ ←── Poll ──── │            │     │
│    │                │                │               │            │     │
│    │                │                │ ─ Task ─────→ │            │     │
│    │                │                │               │            │     │
│    │                │                │ Event 6: ActivityTaskStarted     │
│    │                │                │               │            │     │
│    │                │                │               │ ─ HTTP ──→ │     │
│    │                │                │               │ createOrder│     │
│    │                │                │               │            │     │
│    │                │                │               │ ← 결과 ─── │     │
│    │                │                │               │ orderId=123│     │
│    │                │                │               │            │     │
│    │                │                │ ← 결과 ────── │            │     │
│    │                │                │               │            │     │
│    │                │                │ Event 7: ActivityTaskCompleted   │
│    │                │                │ Event 8: WorkflowTaskScheduled   │
│    │                │                │               │            │     │
│    │                │                │ (반복...)                        │
│    │                │                │               │            │     │
│    │                │                │ Event N: WorkflowExecutionCompleted
│    │                │                │               │            │     │
│    │                │ ← 결과 ─────── │               │            │     │
│    │                │                │               │            │     │
│    │ ←── 200 OK ─── │                │               │            │     │
│    │   {orderId:123}│                │               │            │     │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 4.2 상세 시퀀스 다이어그램

```
┌─────────────────────────────────────────────────────────────────────────┐
│           T1: createOrder Activity 상세 흐름                            │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  시간   │  Temporal Server          │  Worker                │  서비스 │
│  ───────┼───────────────────────────┼────────────────────────┼─────────│
│         │                           │                        │         │
│  T+0ms  │ WorkflowTaskScheduled     │                        │         │
│         │ (Task Queue에 추가)       │                        │         │
│         │                           │                        │         │
│  T+5ms  │                           │ Poll 요청 수신         │         │
│         │ ────────────────────────→ │                        │         │
│         │                           │                        │         │
│  T+6ms  │ WorkflowTaskStarted       │                        │         │
│         │ (Event 기록)              │                        │         │
│         │                           │                        │         │
│  T+7ms  │                           │ Workflow 코드 시작     │         │
│         │                           │ processOrder() 진입    │         │
│         │                           │                        │         │
│  T+8ms  │                           │ activities.createOrder()         │
│         │                           │ ↓                      │         │
│         │                           │ SDK가 Command 생성     │         │
│         │                           │                        │         │
│  T+10ms │ ← Command 수신            │                        │         │
│         │ {ScheduleActivityTask,    │                        │         │
│         │  activityType: createOrder│                        │         │
│         │  input: {customerId: 1}}  │                        │         │
│         │                           │                        │         │
│  T+11ms │ WorkflowTaskCompleted     │                        │         │
│         │ ActivityTaskScheduled     │                        │         │
│         │ (Task Queue에 추가)       │                        │         │
│         │                           │                        │         │
│  T+15ms │                           │ Activity Poll 수신     │         │
│         │ ────────────────────────→ │                        │         │
│         │                           │                        │         │
│  T+16ms │ ActivityTaskStarted       │                        │         │
│         │ (Event 기록)              │                        │         │
│         │                           │                        │         │
│  T+17ms │                           │ Activity 코드 시작     │         │
│         │                           │ createOrder() 실행     │         │
│         │                           │                        │         │
│  T+20ms │                           │ HTTP POST 전송 ──────────────→   │
│         │                           │ /api/orders            │         │
│         │                           │                        │         │
│  T+50ms │                           │ ← HTTP 응답 ───────────────────  │
│         │                           │ {orderId: 123}         │         │
│         │                           │                        │         │
│  T+51ms │ ← 결과 보고               │                        │         │
│         │ {result: 123}             │                        │         │
│         │                           │                        │         │
│  T+52ms │ ActivityTaskCompleted     │                        │         │
│         │ {result: 123}             │                        │         │
│         │                           │                        │         │
│  T+53ms │ WorkflowTaskScheduled     │                        │         │
│         │ (다음 단계 처리 위해)     │                        │         │
│         │                           │                        │         │
│  ─────────────────────────────────────────────────────────────────────  │
│                                                                         │
│  T2: reserveStock Activity (유사한 흐름 반복)                          │
│  T3: createPayment + approvePayment Activity                           │
│  T4-T6: confirmOrder, confirmReservation, confirmPayment               │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 5. 단계별 상세 설명

### 5.1 Phase 1: Workflow 시작

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Phase 1: Workflow 시작                               │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  코드 (OrderController.java):                                          │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  @PostMapping                                                    │   │
│  │  public ResponseEntity<OrderResult> createOrder(                │   │
│  │          @RequestBody OrderRequest request) {                   │   │
│  │                                                                  │   │
│  │      // 1. Workflow ID 생성                                      │   │
│  │      String workflowId = "order-" + UUID.randomUUID()           │   │
│  │              .toString().substring(0, 8);                       │   │
│  │                                                                  │   │
│  │      // 2. Workflow 옵션 설정                                    │   │
│  │      WorkflowOptions options = WorkflowOptions.newBuilder()     │   │
│  │              .setTaskQueue("order-task-queue")                  │   │
│  │              .setWorkflowId(workflowId)                         │   │
│  │              .build();                                          │   │
│  │                                                                  │   │
│  │      // 3. Workflow Stub 생성 (프록시)                           │   │
│  │      OrderWorkflow workflow = workflowClient.newWorkflowStub(   │   │
│  │              OrderWorkflow.class, options);                     │   │
│  │                                                                  │   │
│  │      // 4. Workflow 실행 (여기서 gRPC로 Temporal Server에 요청)  │   │
│  │      OrderResult result = workflow.processOrder(request);       │   │
│  │                                                                  │   │
│  │      return ResponseEntity.ok(result);                          │   │
│  │  }                                                               │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  내부 동작:                                                            │
│  ───────────                                                            │
│  workflow.processOrder(request) 호출 시:                               │
│                                                                         │
│  1. SDK가 gRPC로 Temporal Server에 StartWorkflowExecution 요청        │
│     ┌─────────────────────────────────────────────────────────────┐   │
│     │  StartWorkflowExecutionRequest {                            │   │
│     │    namespace: "default"                                     │   │
│     │    workflowId: "order-abc12345"                             │   │
│     │    workflowType: "OrderWorkflow"                            │   │
│     │    taskQueue: "order-task-queue"                            │   │
│     │    input: {customerId:1, productId:1, quantity:2, ...}      │   │
│     │  }                                                           │   │
│     └─────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  2. Temporal Server가 Event 생성 및 저장                               │
│     • Event 1: WorkflowExecutionStarted                               │
│     • Event 2: WorkflowTaskScheduled                                  │
│                                                                         │
│  3. Task Queue에 Workflow Task 추가                                    │
│     "order-task-queue"에 Workflow Task 대기                           │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 5.2 Phase 2: Workflow Task 처리

```
┌─────────────────────────────────────────────────────────────────────────┐
│                   Phase 2: Workflow Task 처리                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Worker 동작:                                                          │
│  ─────────────                                                          │
│                                                                         │
│  1. Worker가 Task Queue 폴링 중                                        │
│     ┌─────────────────────────────────────────────────────────────┐   │
│     │  Worker (Workflow Poller):                                   │   │
│     │  "order-task-queue에 Workflow Task 있나요?"                  │   │
│     │                                                              │   │
│     │  Temporal Server:                                            │   │
│     │  "네! Workflow Task 있습니다" (Task 전달)                    │   │
│     └─────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  2. Event 기록: WorkflowTaskStarted                                    │
│                                                                         │
│  3. Worker가 Workflow 코드 실행 시작                                   │
│     ┌─────────────────────────────────────────────────────────────┐   │
│     │  OrderWorkflowImpl.processOrder(request) 실행               │   │
│     │                                                              │   │
│     │  // 코드 진입                                                 │   │
│     │  String workflowId = Workflow.getInfo().getWorkflowId();    │   │
│     │  Saga saga = new Saga(...);                                  │   │
│     │                                                              │   │
│     │  // T1: 주문 생성 Activity 호출                               │   │
│     │  orderId = activities.createOrder(request.customerId());    │   │
│     │  //        ↑                                                 │   │
│     │  //        여기서 멈춤! Activity를 직접 실행하지 않음        │   │
│     │  //        대신 Command를 생성하고 반환                      │   │
│     └─────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  4. SDK가 Command 생성                                                 │
│     ┌─────────────────────────────────────────────────────────────┐   │
│     │  Command: ScheduleActivityTask {                            │   │
│     │    activityType: "createOrder"                              │   │
│     │    activityId: "1"                                          │   │
│     │    input: [1]  // customerId                                │   │
│     │    taskQueue: "order-task-queue"                            │   │
│     │    scheduleToCloseTimeout: 30s                              │   │
│     │    startToCloseTimeout: 30s                                 │   │
│     │    retryPolicy: {initialInterval:1s, maxAttempts:3, ...}    │   │
│     │  }                                                           │   │
│     └─────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  5. Worker가 Command를 Temporal Server에 전송                          │
│                                                                         │
│  6. Temporal Server가 Event 기록                                       │
│     • Event 4: WorkflowTaskCompleted (commands: [ScheduleActivityTask])│
│     • Event 5: ActivityTaskScheduled (activityType: createOrder)      │
│                                                                         │
│  7. Activity Task를 Task Queue에 추가                                  │
│                                                                         │
│  핵심 이해:                                                            │
│  ───────────                                                            │
│  activities.createOrder()를 호출하면:                                  │
│  • Activity 코드가 바로 실행되는 것이 아님!                            │
│  • SDK가 "이 Activity를 실행해달라"는 Command만 만듦                   │
│  • Workflow 코드는 여기서 "일시 정지" 상태                             │
│  • Activity 결과가 올 때까지 대기                                      │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 5.3 Phase 3: Activity Task 처리

```
┌─────────────────────────────────────────────────────────────────────────┐
│                   Phase 3: Activity Task 처리                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Worker 동작:                                                          │
│  ─────────────                                                          │
│                                                                         │
│  1. Worker가 Activity Task Queue 폴링                                  │
│     ┌─────────────────────────────────────────────────────────────┐   │
│     │  Worker (Activity Poller):                                   │   │
│     │  "order-task-queue에 Activity Task 있나요?"                  │   │
│     │                                                              │   │
│     │  Temporal Server:                                            │   │
│     │  "네! createOrder Activity Task 있습니다"                    │   │
│     └─────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  2. Event 기록: ActivityTaskStarted                                    │
│                                                                         │
│  3. Worker가 Activity 코드 실행                                        │
│     ┌─────────────────────────────────────────────────────────────┐   │
│     │  OrderActivitiesImpl.createOrder(1) 실행                    │   │
│     │                                                              │   │
│     │  @Override                                                   │   │
│     │  public Long createOrder(Long customerId) {                 │   │
│     │      // 실제 HTTP 호출!                                       │   │
│     │      Map<String, Object> response = orderClient.post()      │   │
│     │              .uri("/api/orders")                            │   │
│     │              .body(Map.of("customerId", customerId))        │   │
│     │              .retrieve()                                    │   │
│     │              .body(Map.class);                              │   │
│     │                                                              │   │
│     │      // service-order가 응답: {orderId: 123, status: CREATED}│   │
│     │                                                              │   │
│     │      Long orderId = ((Number) response.get("orderId"))      │   │
│     │              .longValue();                                  │   │
│     │      return orderId;  // 123 반환                            │   │
│     │  }                                                           │   │
│     └─────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  4. Worker가 결과를 Temporal Server에 전송                             │
│     ┌─────────────────────────────────────────────────────────────┐   │
│     │  RespondActivityTaskCompleted {                             │   │
│     │    taskToken: "..."                                         │   │
│     │    result: 123  // orderId                                  │   │
│     │  }                                                           │   │
│     └─────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  5. Temporal Server가 Event 기록                                       │
│     • Event 7: ActivityTaskCompleted {result: 123}                    │
│     • Event 8: WorkflowTaskScheduled (Workflow 재개를 위해)           │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 5.4 Phase 4: Workflow 재개

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Phase 4: Workflow 재개                               │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Activity가 완료되면 Workflow가 다시 실행됩니다.                        │
│                                                                         │
│  Worker 동작:                                                          │
│  ─────────────                                                          │
│                                                                         │
│  1. Worker가 Workflow Task 수신                                        │
│                                                                         │
│  2. Event 기록: WorkflowTaskStarted                                    │
│                                                                         │
│  3. Workflow 코드 "재실행" (Replay)                                    │
│     ┌─────────────────────────────────────────────────────────────┐   │
│     │  // 코드가 처음부터 다시 실행됨!                              │   │
│     │                                                              │   │
│     │  String workflowId = Workflow.getInfo().getWorkflowId();    │   │
│     │  Saga saga = new Saga(...);                                  │   │
│     │                                                              │   │
│     │  // activities.createOrder() 호출                            │   │
│     │  orderId = activities.createOrder(request.customerId());    │   │
│     │  //        ↑                                                 │   │
│     │  //        SDK가 Event History 확인:                         │   │
│     │  //        "ActivityTaskCompleted {result: 123}" 발견!       │   │
│     │  //        Activity 실행 안 함, 캐시된 결과(123) 반환        │   │
│     │                                                              │   │
│     │  // orderId = 123 (Activity 결과)                            │   │
│     │                                                              │   │
│     │  saga.addCompensation(() -> activities.cancelOrder(orderId));│   │
│     │                                                              │   │
│     │  // 다음 Activity 호출                                        │   │
│     │  activities.reserveStock(productId, quantity, workflowId);  │   │
│     │  //        ↑                                                 │   │
│     │  //        Event History에 없음 → 새 Command 생성           │   │
│     └─────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  4. SDK가 새 Command 생성                                              │
│     Command: ScheduleActivityTask {activityType: "reserveStock", ...}  │
│                                                                         │
│  5. Temporal Server가 Event 기록                                       │
│     • Event 10: WorkflowTaskCompleted                                  │
│     • Event 11: ActivityTaskScheduled (reserveStock)                  │
│                                                                         │
│  (Phase 3-4 반복)                                                      │
│                                                                         │
│  핵심 이해 - Replay:                                                   │
│  ────────────────────                                                   │
│  • Workflow 코드는 매번 처음부터 실행됨                                │
│  • 하지만 이미 완료된 Activity는 Event History에서 결과를 가져옴       │
│  • 그래서 실제로 Activity가 다시 실행되지 않음                         │
│  • 이것이 "결정적(Deterministic)" 코드가 필요한 이유!                  │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 5.5 Phase 5: Workflow 완료

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Phase 5: Workflow 완료                               │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  모든 Activity가 완료된 후:                                            │
│                                                                         │
│  1. 마지막 Workflow Task 처리                                          │
│     ┌─────────────────────────────────────────────────────────────┐   │
│     │  // Replay로 모든 Activity 결과 복원                          │   │
│     │  orderId = 123;           // Event History에서                │   │
│     │  paymentId = 456;         // Event History에서                │   │
│     │                                                              │   │
│     │  // 마지막 Activity들도 완료됨                                │   │
│     │  activities.confirmPayment(paymentId, workflowId);          │   │
│     │  //        ↑                                                 │   │
│     │  //        Event History에서 결과 확인 (이미 완료됨)          │   │
│     │                                                              │   │
│     │  // Workflow 완료!                                            │   │
│     │  return OrderResult.success(orderId, paymentId, workflowId);│   │
│     └─────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  2. SDK가 Command 생성                                                 │
│     Command: CompleteWorkflowExecution {result: OrderResult{...}}      │
│                                                                         │
│  3. Temporal Server가 Event 기록                                       │
│     • Event N-1: WorkflowTaskCompleted                                │
│     • Event N: WorkflowExecutionCompleted {result: {...}}             │
│                                                                         │
│  4. Client에게 결과 반환                                               │
│     workflow.processOrder(request) 호출이 반환됨                       │
│     → OrderResult {success:true, orderId:123, paymentId:456, ...}     │
│                                                                         │
│  5. REST API 응답                                                      │
│     200 OK                                                             │
│     {                                                                  │
│       "success": true,                                                 │
│       "orderId": 123,                                                  │
│       "paymentId": 456,                                                │
│       "workflowId": "order-abc12345"                                   │
│     }                                                                  │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 6. 실제 Event History 분석

### 6.1 성공 시나리오 Event History

```
┌─────────────────────────────────────────────────────────────────────────┐
│              실제 Event History (주문 성공 시)                          │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Workflow ID: order-abc12345                                           │
│  상태: Completed                                                        │
│                                                                         │
│  ┌─────┬──────────────────────────────┬────────────────────────────┐   │
│  │ ID  │ Event Type                   │ 상세 정보                  │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 1   │ WorkflowExecutionStarted     │ input: {customerId:1,...}  │   │
│  │     │                              │ taskQueue: order-task-queue│   │
│  │     │                              │ workflowType: OrderWorkflow│   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 2   │ WorkflowTaskScheduled        │ taskQueue: order-task-queue│   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 3   │ WorkflowTaskStarted          │ identity: worker-host-1    │   │
│  │     │                              │ (Worker가 Task 수신)       │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 4   │ WorkflowTaskCompleted        │ commands: [                │   │
│  │     │                              │   ScheduleActivityTask     │   │
│  │     │                              │   (createOrder)            │   │
│  │     │                              │ ]                          │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 5   │ ActivityTaskScheduled        │ activityType: createOrder  │   │
│  │     │                              │ input: [1]                 │   │
│  │     │                              │ startToCloseTimeout: 30s   │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 6   │ ActivityTaskStarted          │ identity: worker-host-1    │   │
│  │     │                              │ attempt: 1                 │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 7   │ ActivityTaskCompleted        │ result: 123 (orderId)      │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 8   │ WorkflowTaskScheduled        │ (Workflow 재개)            │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 9   │ WorkflowTaskStarted          │                            │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 10  │ WorkflowTaskCompleted        │ commands: [                │   │
│  │     │                              │   ScheduleActivityTask     │   │
│  │     │                              │   (reserveStock)           │   │
│  │     │                              │ ]                          │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 11  │ ActivityTaskScheduled        │ activityType: reserveStock │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 12  │ ActivityTaskStarted          │ attempt: 1                 │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 13  │ ActivityTaskCompleted        │ result: null (void)        │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ ... │ (createPayment, approve,     │ ...                        │   │
│  │     │  confirm 등 반복)            │                            │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ N-1 │ WorkflowTaskCompleted        │ commands: [                │   │
│  │     │                              │   CompleteWorkflow         │   │
│  │     │                              │ ]                          │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ N   │ WorkflowExecutionCompleted   │ result: {                  │   │
│  │     │                              │   success: true,           │   │
│  │     │                              │   orderId: 123,            │   │
│  │     │                              │   paymentId: 456,          │   │
│  │     │                              │   workflowId: order-abc... │   │
│  │     │                              │ }                          │   │
│  └─────┴──────────────────────────────┴────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 6.2 실패 + 보상 시나리오 Event History

```
┌─────────────────────────────────────────────────────────────────────────┐
│              실제 Event History (재고 부족으로 실패)                    │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Workflow ID: order-def67890                                           │
│  상태: Completed (비즈니스 실패, Workflow는 정상 종료)                  │
│                                                                         │
│  ┌─────┬──────────────────────────────┬────────────────────────────┐   │
│  │ ID  │ Event Type                   │ 상세 정보                  │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 1   │ WorkflowExecutionStarted     │ input: {quantity:999,...}  │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 2   │ WorkflowTaskScheduled        │                            │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 3   │ WorkflowTaskStarted          │                            │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 4   │ WorkflowTaskCompleted        │ commands: [createOrder]    │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 5   │ ActivityTaskScheduled        │ createOrder                │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 6   │ ActivityTaskStarted          │ attempt: 1                 │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 7   │ ActivityTaskCompleted        │ result: 789 (orderId)      │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 8   │ WorkflowTaskScheduled        │                            │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 9   │ WorkflowTaskStarted          │                            │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 10  │ WorkflowTaskCompleted        │ commands: [reserveStock]   │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 11  │ ActivityTaskScheduled        │ reserveStock (qty:999)     │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 12  │ ActivityTaskStarted          │ attempt: 1                 │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 13  │ ActivityTaskFailed           │ failure: INSUFFICIENT_STOCK│   │
│  │     │                              │ (재고 부족!)               │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 14  │ ActivityTaskScheduled        │ reserveStock (재시도 1)    │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 15  │ ActivityTaskStarted          │ attempt: 2                 │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 16  │ ActivityTaskFailed           │ (또 실패)                  │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 17  │ ActivityTaskScheduled        │ reserveStock (재시도 2)    │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 18  │ ActivityTaskStarted          │ attempt: 3                 │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 19  │ ActivityTaskFailed           │ (3회 실패, 재시도 종료)    │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 20  │ WorkflowTaskScheduled        │ (실패 처리)                │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 21  │ WorkflowTaskStarted          │                            │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 22  │ WorkflowTaskCompleted        │ commands: [cancelOrder]    │   │
│  │     │                              │ (보상 시작!)               │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 23  │ ActivityTaskScheduled        │ cancelOrder (보상)         │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 24  │ ActivityTaskStarted          │ attempt: 1                 │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 25  │ ActivityTaskCompleted        │ (주문 취소 성공)           │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 26  │ WorkflowTaskScheduled        │                            │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 27  │ WorkflowTaskStarted          │                            │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 28  │ WorkflowTaskCompleted        │ commands: [Complete]       │   │
│  ├─────┼──────────────────────────────┼────────────────────────────┤   │
│  │ 29  │ WorkflowExecutionCompleted   │ result: {                  │   │
│  │     │                              │   success: false,          │   │
│  │     │                              │   error: "재고 부족"       │   │
│  │     │                              │ }                          │   │
│  └─────┴──────────────────────────────┴────────────────────────────┘   │
│                                                                         │
│  Event 13, 16, 19: Activity 3회 재시도 후 실패                         │
│  Event 23-25: 보상 Activity (cancelOrder) 실행                         │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 7. 크래시 복구 Flow

### 7.1 Worker 크래시 시나리오

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Worker 크래시 복구 상세                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  시나리오:                                                              │
│  • T1(createOrder) 완료                                                │
│  • T2(reserveStock) 완료                                               │
│  • T3(createPayment) 진행 중 Worker 크래시!                            │
│                                                                         │
│  Event History 상태 (크래시 시점):                                     │
│  ┌─────┬──────────────────────────────┬────────────────────────────┐   │
│  │ ... │ ...                          │ ...                        │   │
│  │ 7   │ ActivityTaskCompleted        │ createOrder 완료           │   │
│  │ ... │ ...                          │ ...                        │   │
│  │ 13  │ ActivityTaskCompleted        │ reserveStock 완료          │   │
│  │ 14  │ WorkflowTaskScheduled        │                            │   │
│  │ 15  │ WorkflowTaskStarted          │                            │   │
│  │ 16  │ WorkflowTaskCompleted        │ commands:[createPayment]   │   │
│  │ 17  │ ActivityTaskScheduled        │ createPayment              │   │
│  │ 18  │ ActivityTaskStarted          │ ← 여기서 Worker 크래시!    │   │
│  └─────┴──────────────────────────────┴────────────────────────────┘   │
│                                                                         │
│  ═══════════════════════════════════════════════════════════════════   │
│                                                                         │
│  복구 과정:                                                            │
│  ────────────                                                           │
│                                                                         │
│  1. Temporal Server가 Activity 타임아웃 감지 (30초 후)                 │
│     Event 19: ActivityTaskTimedOut                                     │
│                                                                         │
│  2. 재시도 정책에 따라 Activity 재스케줄                               │
│     Event 20: ActivityTaskScheduled (attempt: 2)                       │
│                                                                         │
│  3. 다른 Worker(또는 재시작된 Worker)가 Activity Task 수신             │
│     Event 21: ActivityTaskStarted                                      │
│                                                                         │
│  4. Activity 실행 (createPayment)                                      │
│     Event 22: ActivityTaskCompleted {result: 456}                      │
│                                                                         │
│  5. Workflow Task 스케줄                                               │
│     Event 23: WorkflowTaskScheduled                                    │
│                                                                         │
│  6. Worker가 Workflow Task 수신, Replay 시작                           │
│     ┌─────────────────────────────────────────────────────────────┐   │
│     │  processOrder() 실행 (처음부터)                              │   │
│     │                                                              │   │
│     │  orderId = activities.createOrder();                        │   │
│     │  // Event 7에서 결과(123) 복원 → 실행 안 함                  │   │
│     │                                                              │   │
│     │  activities.reserveStock();                                  │   │
│     │  // Event 13에서 완료 확인 → 실행 안 함                      │   │
│     │                                                              │   │
│     │  paymentId = activities.createPayment();                    │   │
│     │  // Event 22에서 결과(456) 복원 → 실행 안 함                 │   │
│     │                                                              │   │
│     │  activities.approvePayment();                                │   │
│     │  // Event History에 없음 → 새 Command 생성                  │   │
│     └─────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  7. 이후 정상 진행...                                                  │
│                                                                         │
│  ═══════════════════════════════════════════════════════════════════   │
│                                                                         │
│  핵심 포인트:                                                          │
│  • T1, T2 Activity는 재실행되지 않음 (Event History에 결과 있음)       │
│  • T3 Activity만 재실행됨 (타임아웃 후 재시도)                         │
│  • Workflow 코드는 처음부터 실행되지만, Replay로 빠르게 복원           │
│  • 데이터 일관성 유지!                                                 │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 7.2 멱등성의 중요성

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Activity 재실행과 멱등성                             │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  문제 상황:                                                            │
│  ─────────────                                                          │
│  1. createPayment Activity가 실행됨                                    │
│  2. service-payment에서 결제 생성 성공 (paymentId=456)                 │
│  3. 응답 반환 직전 Worker 크래시!                                      │
│  4. Temporal Server는 결과를 모름                                      │
│  5. Activity 재시도 (createPayment 다시 호출)                          │
│                                                                         │
│  멱등성 없으면:                                                        │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  1차 호출: paymentId=456 생성 (DB 저장)                         │   │
│  │  2차 호출: paymentId=457 생성 (중복!)                           │   │
│  │                                                                  │   │
│  │  결과: 결제가 2번 생성됨 → 데이터 불일치                        │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  멱등성 있으면 (우리 프로젝트):                                        │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  Activity 호출 시 멱등성 키 전달:                               │   │
│  │  header("X-Idempotency-Key", sagaId + "-payment-create")       │   │
│  │                                                                  │   │
│  │  1차 호출: 키="order-abc-payment-create"                        │   │
│  │           → paymentId=456 생성, 키-결과 캐시 저장               │   │
│  │                                                                  │   │
│  │  2차 호출: 키="order-abc-payment-create" (동일)                 │   │
│  │           → 캐시에서 결과 반환 (paymentId=456)                  │   │
│  │           → DB에 새로 생성하지 않음                             │   │
│  │                                                                  │   │
│  │  결과: 동일한 paymentId=456 → 데이터 일관성 유지                │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  이것이 Phase 2에서 배운 멱등성이 Temporal에서도 필요한 이유입니다!    │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 요약

### Worker 동작 요약

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Worker 동작 요약                                 │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  1. Worker는 당신의 애플리케이션 안에서 실행됩니다.                     │
│     (Temporal Server 안에 있지 않음!)                                   │
│                                                                         │
│  2. Worker는 2가지 Task를 처리합니다:                                   │
│     • Workflow Task: Workflow 코드 실행 → Command 생성                 │
│     • Activity Task: Activity 코드 실행 → 실제 작업 수행               │
│                                                                         │
│  3. Workflow 코드는 Activity를 직접 실행하지 않습니다.                  │
│     "이 Activity를 실행해달라"는 Command만 생성합니다.                  │
│                                                                         │
│  4. Activity가 완료되면 Workflow가 "재실행"됩니다. (Replay)            │
│     하지만 이미 완료된 Activity는 Event History에서 결과를 가져옵니다. │
│                                                                         │
│  5. 크래시가 발생해도 Event History가 있으므로:                        │
│     • 완료된 Activity는 재실행되지 않음                                │
│     • 진행 중이던 Activity만 재시도됨                                  │
│     • 데이터 일관성 유지!                                              │
│                                                                         │
│  6. 멱등성은 여전히 필요합니다.                                        │
│     Activity가 "실행됐지만 결과 전달 전 크래시" 상황을 위해            │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 참고 자료

- [What is a Temporal Worker?](https://docs.temporal.io/workers)
- [Task Queues](https://docs.temporal.io/task-queue)
- [Events and Event History](https://docs.temporal.io/workflow-execution/event)
- [Worker Performance](https://docs.temporal.io/develop/worker-performance)

---

*이전 문서: `02-temporal-production.md`*
