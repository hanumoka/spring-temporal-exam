# Temporal 심층 학습 가이드

> **대상**: Temporal을 처음 접하는 개발자
> **목표**: 인프라부터 코드까지 완전한 이해
> **작성일**: 2026-02-05

---

## 목차

1. [Temporal이란 무엇인가?](#1-temporal이란-무엇인가)
2. [왜 Temporal이 필요한가?](#2-왜-temporal이-필요한가)
3. [핵심 개념](#3-핵심-개념)
4. [Temporal Server 아키텍처](#4-temporal-server-아키텍처)
5. [Event History와 Durable Execution](#5-event-history와-durable-execution)
6. [Workflow 결정적 코드 규칙](#6-workflow-결정적-코드-규칙)
7. [재시도 정책과 타임아웃](#7-재시도-정책과-타임아웃)
8. [Saga 패턴과 보상 트랜잭션](#8-saga-패턴과-보상-트랜잭션)
9. [프로젝트 코드 상세 분석](#9-프로젝트-코드-상세-분석)
10. [실행 흐름 완전 분석](#10-실행-흐름-완전-분석)
11. [orchestrator-pure vs orchestrator-temporal](#11-orchestrator-pure-vs-orchestrator-temporal)

---

## 1. Temporal이란 무엇인가?

### 1.1 한 줄 정의

**Temporal**은 **Durable Execution(내구성 있는 실행)** 플랫폼입니다.

```
일반 코드:    프로세스 죽으면 → 상태 유실 → 처음부터 다시
Temporal:    프로세스 죽어도 → 상태 복구 → 중단된 곳부터 재개
```

### 1.2 무엇을 해결하는가?

분산 시스템에서 발생하는 **가장 어려운 문제들**을 해결합니다:

| 문제 | 일반적인 해결책 | Temporal의 해결책 |
|------|----------------|------------------|
| 프로세스 크래시 | 상태 직접 저장 + 복구 로직 | **자동 복구** (Event History) |
| 네트워크 실패 | 재시도 로직 직접 구현 | **자동 재시도** (Retry Policy) |
| 장기 실행 작업 | 폴링 + 상태 관리 | **자연스러운 대기** (Workflow.sleep) |
| 분산 트랜잭션 | Saga 패턴 직접 구현 | **선언적 보상** (saga.addCompensation) |
| 디버깅 | 로그 분석 | **Event History 조회** (UI) |

### 1.3 Temporal의 철학

> **"비즈니스 로직에만 집중하라. 인프라 걱정은 Temporal이 한다."**

```java
// 일반 코드 - 인프라 걱정이 섞여있음
public void processOrder(OrderRequest request) {
    try {
        // 상태 저장해야 함
        saveState("STARTED");

        // 재시도 로직 필요
        int retryCount = 0;
        while (retryCount < 3) {
            try {
                createOrder();
                break;
            } catch (Exception e) {
                retryCount++;
                Thread.sleep(1000 * retryCount);  // 백오프
            }
        }

        // 실패 시 보상 로직
        try {
            reserveStock();
        } catch (Exception e) {
            cancelOrder();  // 보상
            throw e;
        }

        saveState("COMPLETED");
    } catch (Exception e) {
        saveState("FAILED");
        // 알림 발송...
    }
}

// Temporal 코드 - 비즈니스 로직만
public void processOrder(OrderRequest request) {
    saga.addCompensation(() -> activities.cancelOrder(orderId));
    Long orderId = activities.createOrder();  // 재시도 자동

    saga.addCompensation(() -> activities.cancelReservation());
    activities.reserveStock();  // 실패 시 보상 자동

    return OrderResult.success(orderId);  // 상태 저장 자동
}
```

### 1.4 Temporal의 역사

```
2016: Uber에서 Cadence 프로젝트 시작
      - 대규모 분산 시스템 오케스트레이션 필요

2019: Cadence 핵심 개발자들이 Temporal Technologies 설립
      - Cadence의 상업적 발전 버전

2020: Temporal 1.0 출시
      - 오픈소스 + 클라우드 서비스

2024: Temporal 1.24+
      - Spring Boot Starter 공식 지원
      - 현재 프로젝트: 1.26.0 사용
```

---

## 2. 왜 Temporal이 필요한가?

### 2.1 마이크로서비스의 현실

```
┌─────────────────────────────────────────────────────────────────────┐
│                    이상적인 마이크로서비스                            │
│                                                                     │
│   Client → Order Service → Inventory Service → Payment Service     │
│            "모든 것이 성공한다"                                       │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                    현실의 마이크로서비스                             │
│                                                                     │
│   Client → Order Service ──X── Inventory Service                   │
│                            ↑                                        │
│                        네트워크 끊김                                 │
│                                                                     │
│   Client → Order Service → Inventory Service ──X── Payment Service │
│                                               ↑                     │
│                                           서비스 다운                │
│                                                                     │
│   Client → Order Service → Inventory Service → Payment Service     │
│                                                       ↓             │
│                                                   응답 지연 (30초)   │
│                                                       ↓             │
│                                                   타임아웃!          │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 직접 해결할 때의 복잡성

**Phase 2에서 우리가 구현한 것들:**

| 문제 | 우리의 해결책 | 코드량 |
|------|-------------|--------|
| 중복 처리 | 멱등성 (IdempotencyService) | ~200줄 |
| 일시적 실패 | Resilience4j Retry | 설정 + ~100줄 |
| 연쇄 장애 | Resilience4j CircuitBreaker | 설정 + ~50줄 |
| 동시성 | RLock + Semantic Lock | ~300줄 |
| 보상 트랜잭션 | OrderSagaOrchestrator | 167줄 |
| **합계** | | **~800줄+** |

**Temporal이 대체하는 것:**

```
┌─────────────────────────────────────────────────────────────────────┐
│  "이 모든 것을 Temporal이 자동으로 처리합니다"                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ✅ 재시도 → Activity RetryOptions (선언적)                         │
│  ✅ 타임아웃 → Activity Timeout Options                             │
│  ✅ 상태 저장 → Event History (자동)                                │
│  ✅ 크래시 복구 → Durable Execution (자동)                          │
│  ✅ 보상 트랜잭션 → Saga API (선언적)                               │
│                                                                     │
│  ⚠️ 여전히 필요한 것:                                               │
│  - 멱등성 (Temporal이 보장하지 않음)                                │
│  - 분산 락 (동시성 제어)                                            │
│  - Semantic Lock (비즈니스 락)                                      │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.3 Temporal vs 다른 솔루션

| 솔루션 | 특징 | 적합한 사용처 |
|--------|------|--------------|
| **Temporal** | 코드 중심, 범용 | 마이크로서비스 오케스트레이션 |
| Apache Airflow | DAG 기반, Python | 데이터 파이프라인 |
| AWS Step Functions | JSON 기반, AWS 종속 | AWS 네이티브 서비스 |
| Camunda | BPMN 기반, 비개발자 친화적 | 비즈니스 프로세스 관리 |

**Temporal의 차별점:**
- **코드가 곧 워크플로우**: JSON/YAML/GUI 대신 일반 코드
- **클라우드 중립**: AWS, GCP, Azure, 온프레미스 모두 가능
- **언어 다양성**: Java, Go, TypeScript, Python, .NET 지원

---

## 3. 핵심 개념

### 3.1 4가지 핵심 구성요소

```
┌─────────────────────────────────────────────────────────────────────┐
│                     Temporal 핵심 구성요소                           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│   ┌─────────────┐         ┌─────────────┐                          │
│   │  Workflow   │ ──────→ │  Activity   │                          │
│   │  (오케스트라)│         │  (연주자)   │                          │
│   └─────────────┘         └─────────────┘                          │
│         ↑                       ↑                                   │
│         │                       │                                   │
│         └───────────┬───────────┘                                   │
│                     │                                               │
│               ┌─────┴─────┐                                         │
│               │  Worker   │                                         │
│               │  (실행자) │                                         │
│               └─────┬─────┘                                         │
│                     │                                               │
│                     ↓                                               │
│              ┌────────────┐                                         │
│              │ Task Queue │                                         │
│              │ (대기열)    │                                         │
│              └────────────┘                                         │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2 Workflow (워크플로우)

> **정의**: 비즈니스 프로세스의 전체 흐름을 정의하는 함수

```java
// Workflow 인터페이스 정의
@WorkflowInterface
public interface OrderWorkflow {

    @WorkflowMethod
    OrderResult processOrder(OrderRequest request);
}

// Workflow 구현
public class OrderWorkflowImpl implements OrderWorkflow {

    @Override
    public OrderResult processOrder(OrderRequest request) {
        // 비즈니스 로직의 "순서"를 정의
        Long orderId = activities.createOrder();
        activities.reserveStock();
        activities.processPayment();
        return OrderResult.success(orderId);
    }
}
```

**핵심 특징:**
| 특징 | 설명 |
|------|------|
| **결정적** | 같은 입력 → 항상 같은 출력 |
| **오래 실행 가능** | 몇 초 ~ 몇 년까지 가능 |
| **상태 자동 저장** | 모든 단계가 Event History에 기록 |
| **재개 가능** | 크래시 후 중단된 곳부터 재개 |

**Workflow가 할 수 있는 것:**
- Activity 호출
- 다른 Workflow 호출 (Child Workflow)
- 타이머 설정 (Workflow.sleep)
- 시그널 대기 (외부 이벤트)
- 쿼리 응답 (상태 조회)

**Workflow가 할 수 없는 것:**
- 직접적인 I/O (DB, HTTP, 파일)
- 비결정적 코드 (Random, System.currentTimeMillis)
- Thread.sleep (Workflow.sleep 사용)

### 3.3 Activity (액티비티)

> **정의**: 실제 부작용(side effect)이 있는 작업을 수행하는 함수

```java
// Activity 인터페이스 정의
@ActivityInterface
public interface OrderActivities {

    @ActivityMethod
    Long createOrder(Long customerId);

    @ActivityMethod
    void reserveStock(Long productId, int quantity, String sagaId);

    @ActivityMethod
    Long createPayment(Long orderId, BigDecimal amount, String method, String sagaId);
}

// Activity 구현
@Component
public class OrderActivitiesImpl implements OrderActivities {

    private final RestClient orderClient;

    @Override
    public Long createOrder(Long customerId) {
        // 실제 HTTP 호출 (부작용 있음)
        Map<String, Object> response = orderClient.post()
                .uri("/api/orders")
                .body(Map.of("customerId", customerId))
                .retrieve()
                .body(Map.class);

        return ((Number) response.get("orderId")).longValue();
    }
}
```

**핵심 특징:**
| 특징 | 설명 |
|------|------|
| **비결정적 허용** | I/O, Random, 현재 시간 모두 OK |
| **자동 재시도** | 실패 시 Retry Policy에 따라 재시도 |
| **타임아웃 지원** | 다양한 타임아웃 옵션 제공 |
| **Heartbeat** | 장기 실행 Activity 생존 신호 |

**Activity가 하는 것:**
- HTTP/gRPC 호출
- 데이터베이스 작업
- 파일 I/O
- 외부 서비스 연동
- 메시지 발행

### 3.4 Worker (워커)

> **정의**: Task Queue를 폴링하여 Workflow와 Activity를 실제로 실행하는 프로세스

```java
// Worker 설정 (우리 프로젝트)
@Bean
public Worker worker(WorkerFactory factory, OrderActivities activities) {
    // 1. Task Queue 이름으로 Worker 생성
    Worker worker = factory.newWorker("order-task-queue");

    // 2. 이 Worker가 실행할 Workflow 등록
    worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);

    // 3. 이 Worker가 실행할 Activity 등록
    worker.registerActivitiesImplementations(activities);

    // 4. 폴링 시작
    factory.start();

    return worker;
}
```

**Worker의 동작 방식:**
```
┌─────────────────────────────────────────────────────────────────────┐
│                         Worker 동작 흐름                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│   1. Long Polling 시작                                              │
│      Worker ──────────────────────→ Temporal Server                │
│              "order-task-queue에                                    │
│               할 일 있나요?"                                         │
│                                                                     │
│   2. Task 수신                                                      │
│      Worker ←────────────────────── Temporal Server                │
│              "Workflow Task 있음:                                   │
│               processOrder 실행해"                                   │
│                                                                     │
│   3. Workflow 실행                                                  │
│      Worker: OrderWorkflowImpl.processOrder() 실행                  │
│              → activities.createOrder() 호출                        │
│              → Command 생성: "ScheduleActivityTask"                 │
│                                                                     │
│   4. Command 전송                                                   │
│      Worker ──────────────────────→ Temporal Server                │
│              "createOrder Activity                                  │
│               실행해줘"                                              │
│                                                                     │
│   5. Activity Task 수신                                             │
│      Worker ←────────────────────── Temporal Server                │
│              "Activity Task 있음:                                   │
│               createOrder 실행해"                                    │
│                                                                     │
│   6. Activity 실행                                                  │
│      Worker: OrderActivitiesImpl.createOrder() 실행                 │
│              → 실제 HTTP 호출                                        │
│              → 결과 반환                                             │
│                                                                     │
│   7. 결과 보고                                                       │
│      Worker ──────────────────────→ Temporal Server                │
│              "Activity 완료: orderId=123"                           │
│                                                                     │
│   (2-7 반복)                                                        │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

**Worker 스케일링:**
```
                    ┌─────────┐
                    │ Temporal │
                    │ Server   │
                    └────┬────┘
                         │
              ┌──────────┼──────────┐
              │          │          │
         ┌────┴────┐┌────┴────┐┌────┴────┐
         │ Worker 1││ Worker 2││ Worker 3│  ← 같은 Task Queue
         │ (Pod 1) ││ (Pod 2) ││ (Pod 3) │     폴링하면 자동
         └─────────┘└─────────┘└─────────┘     로드밸런싱
```

### 3.5 Task Queue (태스크 큐)

> **정의**: Workflow Task와 Activity Task가 대기하는 논리적 큐

```
┌─────────────────────────────────────────────────────────────────────┐
│                      Task Queue 개념도                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│   "order-task-queue"                                                │
│   ┌─────────────────────────────────────────────────────────┐       │
│   │ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐        │       │
│   │ │Workflow │ │Activity │ │Workflow │ │Activity │  ...   │       │
│   │ │ Task 1  │ │ Task 1  │ │ Task 2  │ │ Task 2  │        │       │
│   │ └─────────┘ └─────────┘ └─────────┘ └─────────┘        │       │
│   └─────────────────────────────────────────────────────────┘       │
│         ↑                                                           │
│         │ 폴링                                                      │
│   ┌─────┴─────┐                                                     │
│   │  Workers  │                                                     │
│   └───────────┘                                                     │
│                                                                     │
│   "payment-task-queue" (다른 큐)                                    │
│   ┌─────────────────────────────────────────────────────────┐       │
│   │ ┌─────────┐ ┌─────────┐                                │       │
│   │ │Payment  │ │Payment  │  ...                           │       │
│   │ │Workflow │ │Activity │                                │       │
│   │ └─────────┘ └─────────┘                                │       │
│   └─────────────────────────────────────────────────────────┘       │
│         ↑                                                           │
│         │ 폴링                                                      │
│   ┌─────┴─────┐                                                     │
│   │ Payment   │  ← 다른 Worker 그룹                                 │
│   │ Workers   │                                                     │
│   └───────────┘                                                     │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

**Task Queue의 특징:**
- **동적 생성**: 미리 생성할 필요 없음, 사용 시 자동 생성
- **부하 분산**: 같은 큐를 폴링하는 Worker들이 자동으로 작업 분배
- **격리**: 서로 다른 큐는 완전히 독립적

---

## 4. Temporal Server 아키텍처

### 4.1 전체 아키텍처

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     Temporal Server 내부 아키텍처                        │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│   ┌─────────────────────────────────────────────────────────────────┐   │
│   │                      Your Application                           │   │
│   │  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐         │   │
│   │  │ Workflow    │    │ Activity    │    │ Workflow    │         │   │
│   │  │ Client      │    │ Worker      │    │ Worker      │         │   │
│   │  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘         │   │
│   └─────────│──────────────────│──────────────────│─────────────────┘   │
│             │                  │                  │                     │
│             │ gRPC             │ gRPC             │ gRPC                │
│             ▼                  ▼                  ▼                     │
│   ┌─────────────────────────────────────────────────────────────────┐   │
│   │                    Frontend Service                             │   │
│   │  ┌─────────────────────────────────────────────────────────┐   │   │
│   │  │  • API Gateway (진입점)                                  │   │   │
│   │  │  • Rate Limiting (요청 제한)                             │   │   │
│   │  │  • Authentication (인증)                                 │   │   │
│   │  │  • Request Routing (요청 라우팅)                         │   │   │
│   │  └─────────────────────────────────────────────────────────┘   │   │
│   └─────────────────────────────┬───────────────────────────────────┘   │
│                                 │                                       │
│                    ┌────────────┼────────────┐                          │
│                    │            │            │                          │
│                    ▼            ▼            ▼                          │
│   ┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐        │
│   │ History Service  │ │ Matching Service │ │ Worker Service   │        │
│   │                  │ │                  │ │                  │        │
│   │ • Event History  │ │ • Task Queue     │ │ • 내부 작업      │        │
│   │   관리 및 저장    │ │   관리           │ │   스케줄링       │        │
│   │ • Workflow 상태  │ │ • Worker ↔ Task  │ │ • Archival       │        │
│   │   머신 실행      │ │   매칭           │ │ • Replication    │        │
│   │ • Timer 관리     │ │ • Long Polling   │ │                  │        │
│   │                  │ │   처리           │ │                  │        │
│   └────────┬─────────┘ └────────┬─────────┘ └────────┬─────────┘        │
│            │                    │                    │                  │
│            └────────────────────┼────────────────────┘                  │
│                                 │                                       │
│                                 ▼                                       │
│   ┌─────────────────────────────────────────────────────────────────┐   │
│   │                       Persistence                               │   │
│   │  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐         │   │
│   │  │ PostgreSQL  │    │ Cassandra   │    │ MySQL       │         │   │
│   │  │ (우리 사용) │    │             │    │             │         │   │
│   │  └─────────────┘    └─────────────┘    └─────────────┘         │   │
│   │                                                                 │   │
│   │  (선택) Elasticsearch - 검색 기능                               │   │
│   │  (선택) S3/GCS - 아카이브 저장                                  │   │
│   └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 4.2 각 서비스 역할

#### Frontend Service
```
┌─────────────────────────────────────────────────────────────────────┐
│                      Frontend Service                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  역할: Temporal Server의 "정문"                                     │
│                                                                     │
│  기능:                                                              │
│  ├── API Gateway: 모든 gRPC 요청의 진입점                          │
│  ├── Rate Limiting: 요청 과부하 방지                               │
│  ├── Authentication: 클라이언트 인증                               │
│  ├── Authorization: 권한 확인                                      │
│  └── Routing: 요청을 적절한 내부 서비스로 전달                     │
│                                                                     │
│  특징:                                                              │
│  • Stateless (상태 없음)                                           │
│  • 수평 확장 용이                                                   │
│  • 로드밸런서 뒤에 여러 인스턴스 배치 가능                         │
│                                                                     │
│  통신:                                                              │
│  • Client/Worker → Frontend (gRPC)                                 │
│  • Frontend → History/Matching/Worker Service                      │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

#### History Service
```
┌─────────────────────────────────────────────────────────────────────┐
│                      History Service                                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  역할: Workflow 실행의 "두뇌"                                       │
│                                                                     │
│  기능:                                                              │
│  ├── Event History 관리: 모든 이벤트 저장/조회                     │
│  ├── Mutable State: 현재 Workflow 상태 관리                        │
│  ├── Timer 관리: Workflow.sleep, 타임아웃 처리                     │
│  ├── Workflow 상태 머신: 다음 단계 결정                            │
│  └── Task 생성: Matching Service로 Task 전달                       │
│                                                                     │
│  Sharding (샤딩):                                                   │
│  • Workflow ID를 기반으로 샤드 결정                                │
│  • 각 샤드는 독립적으로 처리 (병렬화)                              │
│  • 예: 1024개 샤드 → 최대 1024개 History Pod                       │
│                                                                     │
│  저장하는 것:                                                       │
│  • Event History (불변)                                            │
│  • Mutable State (현재 상태)                                       │
│  • Timer 정보                                                      │
│  • Pending Activity 정보                                           │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

#### Matching Service
```
┌─────────────────────────────────────────────────────────────────────┐
│                      Matching Service                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  역할: Worker와 Task를 "중매"                                       │
│                                                                     │
│  기능:                                                              │
│  ├── Task Queue 관리: 메모리에 Task 보관                           │
│  ├── Long Polling 처리: Worker 연결 유지                           │
│  ├── Task Dispatch: 적절한 Worker에 Task 전달                      │
│  └── Sync Match: 가능하면 즉시 매칭 (효율성)                       │
│                                                                     │
│  Partitioning (파티셔닝):                                          │
│  • Task Queue를 여러 파티션으로 분할                               │
│  • 고부하 Queue의 확장성 보장                                      │
│                                                                     │
│  동작 방식:                                                        │
│  1. Worker가 Poll 요청 (Long Polling)                              │
│  2. Task가 있으면 즉시 반환                                        │
│  3. Task가 없으면 대기 (최대 60초)                                 │
│  4. 새 Task 도착 시 대기 중인 Worker에 전달                        │
│                                                                     │
│  Sticky Execution:                                                 │
│  • 같은 Workflow를 같은 Worker에 우선 배정                         │
│  • 캐시 활용으로 성능 향상                                         │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.3 우리 프로젝트의 Docker 구성

```yaml
# docker-compose.yml 분석

services:
  # Temporal Server 본체
  temporal:
    image: temporalio/auto-setup:1.25.2
    ports:
      - "21733:7233"  # gRPC 포트 (SDK 연결용)
    environment:
      - DB=postgres12
      - DB_PORT=5432
      - POSTGRES_USER=temporal
      - POSTGRES_PWD=temporal
      - POSTGRES_SEEDS=temporal-postgresql

  # Temporal 상태 저장용 DB (애플리케이션 DB와 분리!)
  temporal-postgresql:
    image: postgres:15-alpine
    ports:
      - "21432:5432"

  # 모니터링 UI
  temporal-ui:
    image: temporalio/ui:2.31.2
    ports:
      - "21088:8080"  # http://localhost:21088
```

```
┌─────────────────────────────────────────────────────────────────────┐
│                    우리 프로젝트 인프라 구성                         │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│   ┌─────────────────┐     ┌─────────────────┐                      │
│   │ 애플리케이션 DB  │     │  Temporal DB    │                      │
│   │    (MySQL)      │     │  (PostgreSQL)   │                      │
│   │   포트: 21306   │     │   포트: 21432   │                      │
│   └─────────────────┘     └─────────────────┘                      │
│          ↑                        ↑                                 │
│          │                        │                                 │
│   ┌──────┴──────┐          ┌──────┴──────┐                         │
│   │ 우리 서비스  │          │  Temporal   │                         │
│   │ Order       │          │  Server     │ ← 포트: 21733 (gRPC)    │
│   │ Inventory   │          └─────────────┘                         │
│   │ Payment     │                 ↑                                 │
│   └─────────────┘                 │ gRPC                           │
│          ↑                        │                                 │
│          │ REST                   │                                 │
│          │                 ┌──────┴──────┐                         │
│          └─────────────────┤orchestrator-│                         │
│                            │  temporal   │ ← 포트: 21081           │
│                            └─────────────┘                         │
│                                   │                                 │
│                                   │ HTTP                           │
│                                   ▼                                 │
│                            ┌─────────────┐                         │
│                            │ Temporal UI │ ← 포트: 21088           │
│                            │ (모니터링)   │                         │
│                            └─────────────┘                         │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 5. Event History와 Durable Execution

### 5.1 Event History란?

> **정의**: Workflow 실행 중 발생한 모든 이벤트의 불변 로그

```
┌─────────────────────────────────────────────────────────────────────┐
│                 Event History 예시 (주문 Workflow)                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Event ID │ Event Type                    │ 상세 정보               │
│  ─────────┼───────────────────────────────┼─────────────────────── │
│  1        │ WorkflowExecutionStarted      │ 입력: OrderRequest     │
│  2        │ WorkflowTaskScheduled         │                        │
│  3        │ WorkflowTaskStarted           │ Worker가 Task 수신     │
│  4        │ WorkflowTaskCompleted         │ Command 생성됨         │
│  5        │ ActivityTaskScheduled         │ createOrder 예약       │
│  6        │ ActivityTaskStarted           │ Activity 시작          │
│  7        │ ActivityTaskCompleted         │ 결과: orderId=123      │
│  8        │ WorkflowTaskScheduled         │                        │
│  9        │ WorkflowTaskStarted           │                        │
│  10       │ WorkflowTaskCompleted         │                        │
│  11       │ ActivityTaskScheduled         │ reserveStock 예약      │
│  12       │ ActivityTaskStarted           │                        │
│  13       │ ActivityTaskCompleted         │ 성공                   │
│  ...      │ ...                           │ ...                    │
│  N        │ WorkflowExecutionCompleted    │ 결과: OrderResult      │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 5.2 Event Sourcing 원리

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Event Sourcing in Temporal                       │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  전통적 상태 저장:                                                  │
│  ┌──────────────────────────────────────────────┐                  │
│  │  order_status = "PAYMENT_COMPLETED"          │ ← 최신 상태만    │
│  │  last_updated = "2026-02-05 14:30:00"        │                  │
│  └──────────────────────────────────────────────┘                  │
│                                                                     │
│  문제: 중간에 무슨 일이 있었는지 알 수 없음                        │
│        복구 시 어디부터 시작해야 할지 불명확                        │
│                                                                     │
│  ─────────────────────────────────────────────────────────────────  │
│                                                                     │
│  Event Sourcing (Temporal):                                        │
│  ┌──────────────────────────────────────────────┐                  │
│  │  Event 1: OrderCreated(orderId=123)          │                  │
│  │  Event 2: StockReserved(productId=1, qty=2)  │                  │
│  │  Event 3: PaymentCreated(paymentId=456)      │                  │
│  │  Event 4: PaymentApproved(paymentId=456)     │                  │
│  │  Event 5: OrderConfirmed(orderId=123)        │                  │
│  └──────────────────────────────────────────────┘                  │
│         ↓                                                           │
│  현재 상태 = Event 1 적용 → Event 2 적용 → ... → Event N 적용      │
│                                                                     │
│  장점:                                                              │
│  • 전체 히스토리 추적 가능                                         │
│  • 임의 시점 상태 복원 가능                                        │
│  • 디버깅 용이                                                     │
│  • 크래시 복구 완벽                                                │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 5.3 Replay (재생) 메커니즘

> **핵심 개념**: 크래시 후 Worker가 재시작되면, Event History를 재생하여 상태 복구

```
┌─────────────────────────────────────────────────────────────────────┐
│                       Replay 동작 방식                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  정상 실행 시:                                                      │
│  ─────────────                                                      │
│  1. processOrder() 시작                                            │
│  2. activities.createOrder() 호출                                   │
│     → Command 생성: "ScheduleActivityTask"                         │
│     → Temporal에 Command 전송                                       │
│     → Activity 실행, 결과 반환 (orderId=123)                       │
│     → Event 기록: "ActivityTaskCompleted(result=123)"              │
│                                                                     │
│  3. activities.reserveStock() 호출                                  │
│     → Command 생성: "ScheduleActivityTask"                         │
│     → ... (계속)                                                    │
│                                                                     │
│  ─────────────────────────────────────────────────────────────────  │
│                                                                     │
│  크래시 후 Replay 시:                                               │
│  ─────────────────────                                              │
│  1. processOrder() 시작 (다시!)                                    │
│  2. activities.createOrder() 호출                                   │
│     → Temporal SDK가 Event History 확인                            │
│     → "ActivityTaskCompleted(result=123)" 이벤트 발견!             │
│     → Activity 실행 안 함! 캐시된 결과(123) 즉시 반환              │
│                                                                     │
│  3. activities.reserveStock() 호출                                  │
│     → Event History 확인                                            │
│     → 이벤트 없음 → 실제로 Activity 실행                           │
│                                                                     │
│  핵심: 이미 완료된 작업은 재실행하지 않고 결과만 재사용             │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 5.4 Durable Execution의 마법

```java
// 이 코드가 어떻게 "내구성"을 가지는지

public OrderResult processOrder(OrderRequest request) {
    // 1단계: 주문 생성
    Long orderId = activities.createOrder(request.customerId());
    //     ↓
    //  Worker 크래시!
    //     ↓
    //  새 Worker가 재시작
    //     ↓
    //  Replay: createOrder 결과(orderId=123) 캐시에서 복원
    //     ↓
    //  코드는 여기서부터 "진짜" 실행 재개

    // 2단계: 재고 예약
    activities.reserveStock(request.productId(), request.quantity(), workflowId);
    //     ↓
    //  실제로 HTTP 호출 실행 (크래시 전에 안 했으니까)

    // 3단계: 결제 처리
    Long paymentId = activities.createPayment(...);

    return OrderResult.success(orderId, paymentId, workflowId);
}
```

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Durable Execution 타임라인                       │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  시간 →                                                             │
│  ────────────────────────────────────────────────────────────────   │
│                                                                     │
│  T0: Workflow 시작                                                  │
│      ├── WorkflowExecutionStarted 이벤트 저장                       │
│      └── Workflow Task를 Task Queue에 추가                          │
│                                                                     │
│  T1: Worker A가 Workflow Task 수신                                  │
│      ├── processOrder() 실행 시작                                   │
│      └── activities.createOrder() 호출 → Command 생성              │
│                                                                     │
│  T2: createOrder Activity 완료                                      │
│      └── ActivityTaskCompleted(orderId=123) 이벤트 저장             │
│                                                                     │
│  T3: ⚡ Worker A 크래시! ⚡                                          │
│      └── 진행 중이던 Workflow Task 타임아웃                         │
│                                                                     │
│  T4: Temporal Server가 Workflow Task 재스케줄                       │
│      └── Task Queue에 다시 추가                                     │
│                                                                     │
│  T5: Worker B가 Workflow Task 수신                                  │
│      ├── Event History 로드 (Event 1~N)                            │
│      ├── Replay 시작                                                │
│      ├── processOrder() 다시 실행                                   │
│      ├── activities.createOrder() 호출                              │
│      │   └── Event History에서 결과(123) 찾음 → 재실행 안 함       │
│      ├── activities.reserveStock() 호출                             │
│      │   └── Event History에 없음 → 실제 실행                      │
│      └── ... 계속 진행                                              │
│                                                                     │
│  TN: Workflow 완료                                                  │
│      └── WorkflowExecutionCompleted 이벤트 저장                     │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 6. Workflow 결정적 코드 규칙

### 6.1 왜 결정적이어야 하는가?

> **핵심**: Replay가 동작하려면, 같은 코드가 같은 순서로 같은 결과를 내야 함

```
┌─────────────────────────────────────────────────────────────────────┐
│                    결정적 코드가 필요한 이유                         │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  상황: Workflow가 크래시 후 Replay 중                               │
│                                                                     │
│  Event History:                                                     │
│  1. WorkflowExecutionStarted                                       │
│  2. ActivityTaskScheduled (createOrder)                            │
│  3. ActivityTaskCompleted (result: orderId=123)                    │
│  4. ActivityTaskScheduled (reserveStock)  ← 여기까지 기록됨        │
│                                                                     │
│  ─────────────────────────────────────────────────────────────────  │
│                                                                     │
│  Replay 시 Temporal SDK의 동작:                                    │
│                                                                     │
│  1. processOrder() 실행                                             │
│  2. activities.createOrder() 호출                                   │
│     → "다음에 예상되는 Command는 뭐지?" (Event History 확인)       │
│     → Event 2: "ActivityTaskScheduled(createOrder)" ✓ 일치!        │
│     → 캐시된 결과(123) 반환                                         │
│                                                                     │
│  3. activities.reserveStock() 호출                                  │
│     → "다음에 예상되는 Command는 뭐지?" (Event History 확인)       │
│     → Event 4: "ActivityTaskScheduled(reserveStock)" ✓ 일치!       │
│     → 아직 완료 안 됨 → 실제 실행                                   │
│                                                                     │
│  만약 비결정적 코드가 있다면?                                       │
│                                                                     │
│  if (Math.random() > 0.5) {                                        │
│      activities.createOrder();                                      │
│  } else {                                                           │
│      activities.reserveStock();  // 처음 실행 때 이쪽으로 갔다면?  │
│  }                                                                  │
│                                                                     │
│  Replay 시:                                                         │
│  → Math.random() 결과가 다름                                        │
│  → createOrder 호출                                                 │
│  → Event History에는 reserveStock이 예상됨                         │
│  → ❌ Non-Deterministic Error!                                     │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 6.2 금지되는 코드 패턴

```java
// ❌ 금지 1: 현재 시간 직접 사용
public void processOrder() {
    long now = System.currentTimeMillis();  // ❌ Replay 때 다른 값
    if (now % 2 == 0) {
        activities.methodA();
    } else {
        activities.methodB();
    }
}

// ✅ 대안: Workflow.currentTimeMillis() 사용
public void processOrder() {
    long now = Workflow.currentTimeMillis();  // ✅ Replay 때 같은 값 보장
    // ...
}
```

```java
// ❌ 금지 2: Random 직접 사용
public void processOrder() {
    Random random = new Random();
    if (random.nextBoolean()) {  // ❌ Replay 때 다른 값
        activities.methodA();
    }
}

// ✅ 대안: Workflow.newRandom() 사용
public void processOrder() {
    Random random = Workflow.newRandom();  // ✅ 결정적 난수
    // ...
}
```

```java
// ❌ 금지 3: Thread.sleep 직접 사용
public void processOrder() {
    Thread.sleep(5000);  // ❌ Workflow 스레드 블로킹
    activities.methodA();
}

// ✅ 대안: Workflow.sleep() 사용
public void processOrder() {
    Workflow.sleep(Duration.ofSeconds(5));  // ✅ Durable Timer
    activities.methodA();
}
```

```java
// ❌ 금지 4: 직접 I/O
public void processOrder() {
    // ❌ HTTP 호출
    RestTemplate restTemplate = new RestTemplate();
    restTemplate.getForObject("http://...", String.class);

    // ❌ DB 접근
    jdbcTemplate.query("SELECT ...", ...);

    // ❌ 파일 I/O
    Files.readString(Path.of("/data/file.txt"));
}

// ✅ 대안: Activity로 위임
public void processOrder() {
    String result = activities.callExternalApi();  // ✅ Activity에서 처리
    activities.readFromDatabase();
    activities.readFile();
}
```

```java
// ❌ 금지 5: 전역 상태 의존
public class OrderWorkflowImpl implements OrderWorkflow {
    private static int counter = 0;  // ❌ 전역 상태

    public void processOrder() {
        counter++;  // ❌ Replay 때 다른 값
        if (counter > 10) {
            activities.methodA();
        }
    }
}

// ✅ 대안: Workflow 내부 상태 사용
public class OrderWorkflowImpl implements OrderWorkflow {
    private int counter = 0;  // ✅ 인스턴스 변수 (Replay 시 복원됨)

    public void processOrder() {
        counter++;
        // ...
    }
}
```

### 6.3 안전한 코드 변경

```
┌─────────────────────────────────────────────────────────────────────┐
│                    안전한 코드 변경 vs 위험한 변경                    │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ✅ 안전한 변경 (실행 중인 Workflow에 영향 없음):                   │
│  ────────────────────────────────────────────────                   │
│  • Activity 타임아웃 값 변경                                        │
│  • Activity 재시도 횟수 변경                                        │
│  • 새로운 Signal Handler 추가 (아직 안 받은 Signal만)              │
│  • 로깅 추가/제거                                                   │
│  • 새로운 Activity 추가 (기존 흐름 뒤에)                           │
│                                                                     │
│  ❌ 위험한 변경 (Non-Deterministic Error 발생):                     │
│  ────────────────────────────────────────────────                   │
│  • Activity 호출 순서 변경                                          │
│  • Activity 제거                                                    │
│  • 조건문 로직 변경                                                 │
│  • Timer 추가/제거                                                  │
│  • Child Workflow 추가/제거                                         │
│                                                                     │
│  위험한 변경이 필요할 때:                                           │
│  ────────────────────────                                           │
│  1. Versioning API 사용 (Workflow.getVersion)                       │
│  2. Worker Versioning 사용 (Build ID 기반)                         │
│  3. 새 Workflow 타입으로 마이그레이션                               │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

```java
// Versioning API 예시
public void processOrder(OrderRequest request) {
    int version = Workflow.getVersion("add-notification", Workflow.DEFAULT_VERSION, 1);

    Long orderId = activities.createOrder(request.customerId());
    activities.reserveStock(...);
    activities.processPayment(...);

    // 버전 1 이상에서만 알림 발송
    if (version >= 1) {
        activities.sendNotification(orderId);  // 새로 추가된 Activity
    }

    return OrderResult.success(orderId);
}
```

---

## 7. 재시도 정책과 타임아웃

### 7.1 Activity 타임아웃 종류

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Activity 타임아웃 4종류                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  시간 흐름 →                                                        │
│  ────────────────────────────────────────────────────────────────   │
│                                                                     │
│  │←──────────── Schedule-To-Close Timeout ────────────→│            │
│  │                                                      │            │
│  │←── Schedule ──→│←── Start-To-Close ──→│              │            │
│  │    To-Start    │                       │              │            │
│  │                │                       │              │            │
│  ▼                ▼                       ▼              ▼            │
│  ┌────────────────┬───────────────────────┬──────────────┐           │
│  │ Task Queue에   │ Worker에서            │ Activity     │           │
│  │ 대기 중        │ 실행 중               │ 완료         │           │
│  └────────────────┴───────────────────────┴──────────────┘           │
│                   │                       │                          │
│                   │←─ Heartbeat Timeout ─→│                          │
│                   │    (장기 실행 시)      │                          │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

| 타임아웃 | 설명 | 기본값 | 사용 시점 |
|---------|------|--------|----------|
| **Schedule-To-Start** | 큐 대기 시간 | 무제한 | Worker 가용성 모니터링 |
| **Start-To-Close** | 실제 실행 시간 | 무제한 | **가장 중요** - 반드시 설정 |
| **Schedule-To-Close** | 전체 시간 (재시도 포함) | 무제한 | 전체 제한 필요 시 |
| **Heartbeat** | 생존 신호 간격 | 무제한 | 장기 실행 Activity |

### 7.2 우리 프로젝트의 설정

```java
// OrderWorkflowImpl.java
private final ActivityOptions activityOptions = ActivityOptions.newBuilder()
        // Activity 시작 → 완료까지 최대 30초
        .setStartToCloseTimeout(Duration.ofSeconds(30))

        // 재시도 정책
        .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))      // 첫 재시도 대기: 1초
                .setBackoffCoefficient(2.0)                     // 지수 백오프 계수
                .setMaximumInterval(Duration.ofSeconds(30))     // 최대 대기: 30초
                .setMaximumAttempts(3)                          // 최대 재시도: 3회
                .build())
        .build();
```

### 7.3 재시도 동작 시뮬레이션

```
┌─────────────────────────────────────────────────────────────────────┐
│              Activity 재시도 동작 (예: createOrder)                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  설정:                                                              │
│  - initialInterval: 1초                                             │
│  - backoffCoefficient: 2.0                                          │
│  - maximumInterval: 30초                                            │
│  - maximumAttempts: 3                                               │
│                                                                     │
│  시나리오: 서비스가 일시적으로 다운                                 │
│                                                                     │
│  시간 →                                                             │
│  ─────────────────────────────────────────────────────────────────  │
│                                                                     │
│  T+0s:  [시도 1] createOrder() 호출                                │
│         └── ❌ 실패 (Connection refused)                            │
│                                                                     │
│  T+1s:  [대기 1초] (initialInterval)                               │
│         [시도 2] createOrder() 호출                                │
│         └── ❌ 실패 (Connection refused)                            │
│                                                                     │
│  T+3s:  [대기 2초] (1초 × 2.0)                                     │
│         [시도 3] createOrder() 호출                                │
│         └── ✅ 성공! (서비스 복구됨)                                │
│                                                                     │
│  만약 3번째도 실패했다면?                                           │
│  → ActivityFailure 예외 발생                                        │
│  → Workflow의 catch 블록에서 saga.compensate() 호출                │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 7.4 재시도하면 안 되는 오류

```java
// 재시도 불가 오류 설정
.setRetryOptions(RetryOptions.newBuilder()
        .setDoNotRetry(
            // 비즈니스 로직 오류 - 재시도해도 결과 같음
            "com.hanumoka.common.exception.BusinessException",

            // 인증 오류 - 재시도해도 결과 같음
            "java.lang.SecurityException",

            // 잘못된 요청 - 재시도해도 결과 같음
            "java.lang.IllegalArgumentException"
        )
        .build())
```

```
┌─────────────────────────────────────────────────────────────────────┐
│                  재시도 가능 vs 불가능 오류                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ✅ 재시도 가능 (일시적 오류):                                      │
│  ─────────────────────────────                                      │
│  • Connection refused (서비스 일시 다운)                            │
│  • Socket timeout (네트워크 지연)                                   │
│  • 503 Service Unavailable                                         │
│  • 429 Too Many Requests                                           │
│  • Database connection pool exhausted                              │
│                                                                     │
│  ❌ 재시도 불가능 (영구적 오류):                                    │
│  ─────────────────────────────                                      │
│  • 400 Bad Request (잘못된 요청)                                   │
│  • 401 Unauthorized (인증 실패)                                    │
│  • 404 Not Found (리소스 없음)                                     │
│  • 재고 부족 (BusinessException)                                   │
│  • 잔액 부족 (BusinessException)                                   │
│  • 중복 주문 (BusinessException)                                   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 8. Saga 패턴과 보상 트랜잭션

### 8.1 Saga 패턴 복습

```
┌─────────────────────────────────────────────────────────────────────┐
│                      Saga 패턴이란?                                 │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  문제: 마이크로서비스에서 분산 트랜잭션은 어떻게?                   │
│                                                                     │
│  전통적 트랜잭션 (모놀리식):                                       │
│  ┌─────────────────────────────────────────────┐                   │
│  │ BEGIN TRANSACTION                           │                   │
│  │   INSERT INTO orders ...                    │                   │
│  │   UPDATE inventory SET quantity = ...       │                   │
│  │   INSERT INTO payments ...                  │                   │
│  │ COMMIT (또는 ROLLBACK)                      │                   │
│  └─────────────────────────────────────────────┘                   │
│  → 모든 작업이 성공하거나, 모두 롤백됨 (ACID 보장)                 │
│                                                                     │
│  마이크로서비스 (분산 환경):                                        │
│  ┌──────────┐   ┌───────────┐   ┌──────────┐                       │
│  │ Order DB │   │Inventory  │   │Payment DB│                       │
│  │          │   │    DB     │   │          │                       │
│  └──────────┘   └───────────┘   └──────────┘                       │
│  → 각 DB는 독립적, 하나의 트랜잭션으로 묶을 수 없음!               │
│                                                                     │
│  해결책: Saga 패턴                                                  │
│  ─────────────────                                                  │
│  "각 서비스의 로컬 트랜잭션 + 실패 시 보상 트랜잭션"               │
│                                                                     │
│  성공 시 (Forward):                                                │
│  T1(주문) → T2(재고) → T3(결제) → 완료                             │
│                                                                     │
│  T3에서 실패 시 (Compensation):                                    │
│  T1(주문) → T2(재고) → T3(결제❌) → C2(재고 복구) → C1(주문 취소) │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 8.2 Temporal의 Saga API

```java
// 우리 프로젝트의 Saga 구현 (OrderWorkflowImpl.java)

public OrderResult processOrder(OrderRequest request) {
    String workflowId = Workflow.getInfo().getWorkflowId();

    // Saga 옵션 설정
    Saga.Options sagaOptions = new Saga.Options.Builder()
            .setParallelCompensation(false)  // 순차 보상 (역순)
            .build();

    Saga saga = new Saga(sagaOptions);

    Long orderId = null;
    Long paymentId = null;

    try {
        // ===== 정방향 트랜잭션 =====

        // T1: 주문 생성
        orderId = activities.createOrder(request.customerId());
        // 보상 등록: 주문 취소
        final Long finalOrderId = orderId;
        saga.addCompensation(() -> activities.cancelOrder(finalOrderId));

        // T2: 재고 예약
        activities.reserveStock(request.productId(), request.quantity(), workflowId);
        // 보상 등록: 재고 복구
        saga.addCompensation(() -> activities.cancelReservation(
                request.productId(), request.quantity(), workflowId));

        // T3: 결제 생성 + 승인
        paymentId = activities.createPayment(orderId, request.amount(),
                request.paymentMethod(), workflowId);
        // 보상 등록: 환불
        final Long finalPaymentId = paymentId;
        saga.addCompensation(() -> activities.refundPayment(finalPaymentId, workflowId));

        activities.approvePayment(paymentId, workflowId);

        // T4~T6: 확정 단계
        activities.confirmOrder(orderId);
        activities.confirmReservation(request.productId(), request.quantity(), workflowId);
        activities.confirmPayment(paymentId, workflowId);

        return OrderResult.success(orderId, paymentId, workflowId);

    } catch (ActivityFailure e) {
        // ===== 보상 트랜잭션 자동 실행 =====
        saga.compensate();  // 등록된 보상을 역순으로 실행

        return OrderResult.failure(e.getMessage(), workflowId);
    }
}
```

### 8.3 보상 실행 순서

```
┌─────────────────────────────────────────────────────────────────────┐
│                    보상 트랜잭션 실행 순서                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  정방향 (Forward):                                                  │
│  ─────────────────                                                  │
│  T1: createOrder()         → saga.addCompensation(cancelOrder)     │
│  T2: reserveStock()        → saga.addCompensation(cancelReservation)│
│  T3: createPayment()       → saga.addCompensation(refundPayment)   │
│  T3: approvePayment()      → ❌ 실패 발생!                          │
│                                                                     │
│  보상 스택 (Stack 구조 - LIFO):                                    │
│  ┌─────────────────────────┐                                       │
│  │ [Top] refundPayment     │ ← 가장 마지막에 등록됨                │
│  ├─────────────────────────┤                                       │
│  │       cancelReservation │                                       │
│  ├─────────────────────────┤                                       │
│  │ [Bottom] cancelOrder    │ ← 가장 먼저 등록됨                    │
│  └─────────────────────────┘                                       │
│                                                                     │
│  saga.compensate() 호출 시:                                        │
│  ─────────────────────────                                          │
│  C3: refundPayment()       (paymentId)      ← 먼저 실행            │
│  C2: cancelReservation()   (productId, qty) ← 그 다음              │
│  C1: cancelOrder()         (orderId)        ← 마지막               │
│                                                                     │
│  왜 역순인가?                                                       │
│  ─────────────                                                      │
│  • 의존성 고려: 결제가 있어야 환불 가능                             │
│  • 데이터 일관성: 마지막 작업부터 되돌려야 중간 상태 방지          │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 8.4 Saga의 한계 (ACD)

```
┌─────────────────────────────────────────────────────────────────────┐
│                   Saga는 ACID가 아닌 ACD만 보장                     │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ACID 속성:                                                         │
│  ─────────                                                          │
│  A - Atomicity (원자성)      ✅ Saga가 보장 (보상으로)              │
│  C - Consistency (일관성)    ✅ Saga가 보장 (최종적)                │
│  I - Isolation (격리성)      ❌ Saga가 보장 못함!                   │
│  D - Durability (지속성)     ✅ Saga가 보장                         │
│                                                                     │
│  격리성 부재의 문제:                                                │
│  ────────────────                                                   │
│                                                                     │
│  시나리오: 동시에 2개의 주문이 같은 재고를 예약                     │
│                                                                     │
│  Saga A: T1(주문A) → T2(재고 100→98) → T3(결제A 실패!)             │
│  Saga B: T1(주문B) → T2(재고 98→96) → T3(결제B 성공)               │
│                                                                     │
│  Saga A 보상: C2(재고 96→98) → C1(주문A 취소)                      │
│                                                                     │
│  문제: Saga B가 읽은 재고(98)는 Saga A의 "중간 상태"였음!          │
│        → Dirty Read 발생                                            │
│                                                                     │
│  해결책 (Phase 2에서 학습한 것):                                   │
│  ────────────────────────────                                       │
│  • 분산 락 (RLock) - 동시 접근 차단                                │
│  • Semantic Lock - 비즈니스 수준 락                                │
│  • 낙관적 락 (@Version) - 충돌 감지                                │
│                                                                     │
│  중요: Temporal도 이 문제를 해결해주지 않음!                        │
│        Phase 2에서 배운 기술이 여전히 필요함                        │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 9. 프로젝트 코드 상세 분석

### 9.1 디렉토리 구조

```
orchestrator-temporal/
├── src/main/java/com/hanumoka/orchestrator/temporal/
│   ├── TemporalOrchestratorApplication.java   # Spring Boot 진입점
│   │
│   ├── config/
│   │   └── TemporalConfig.java                # Temporal 설정 (107줄)
│   │
│   ├── controller/
│   │   └── OrderController.java               # REST API (162줄)
│   │
│   ├── dto/
│   │   ├── OrderRequest.java                  # 요청 DTO
│   │   └── OrderResult.java                   # 응답 DTO
│   │
│   ├── workflow/
│   │   ├── OrderWorkflow.java                 # Workflow 인터페이스
│   │   └── impl/
│   │       └── OrderWorkflowImpl.java         # Workflow 구현 (163줄)
│   │
│   └── activity/
│       ├── OrderActivities.java               # Activity 인터페이스 (128줄)
│       └── impl/
│           └── OrderActivitiesImpl.java       # Activity 구현 (232줄)
│
├── src/main/resources/
│   └── application.yml                        # 설정 파일
│
└── build.gradle                               # 의존성
```

### 9.2 핵심 파일별 상세 분석

#### TemporalConfig.java - Temporal 설정

```java
@Configuration
public class TemporalConfig {

    @Value("${temporal.connection.target:localhost:7233}")
    private String temporalTarget;

    // 1. Temporal Server 연결 설정
    @Bean
    public WorkflowServiceStubs workflowServiceStubs() {
        return WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget(temporalTarget)  // localhost:21733
                .build()
        );
    }

    // 2. Workflow 시작/조회용 클라이언트
    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs stubs) {
        return WorkflowClient.newInstance(
            stubs,
            WorkflowClientOptions.newBuilder()
                .setNamespace("default")  // Namespace
                .build()
        );
    }

    // 3. Worker 팩토리
    @Bean
    public WorkerFactory workerFactory(WorkflowClient client) {
        return WorkerFactory.newInstance(client);
    }

    // 4. Worker 설정 및 시작
    @Bean
    public Worker worker(WorkerFactory factory, OrderActivities activities) {
        // Task Queue 이름으로 Worker 생성
        Worker worker = factory.newWorker("order-task-queue");

        // Workflow 구현 등록
        worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);

        // Activity 구현 등록 (Spring Bean 주입됨!)
        worker.registerActivitiesImplementations(activities);

        // 폴링 시작
        factory.start();

        return worker;
    }
}
```

**핵심 포인트:**
- `WorkflowServiceStubs`: gRPC 연결 관리
- `WorkflowClient`: Workflow 시작/조회 API
- `WorkerFactory`: Worker 생성 팩토리
- `Worker`: 실제 Workflow/Activity 실행 엔진

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Bean 의존성 관계                                 │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  WorkflowServiceStubs ──→ WorkflowClient ──→ WorkerFactory         │
│         │                                          │                │
│         │                                          ▼                │
│         │                                       Worker              │
│         │                                          │                │
│         │                              ┌───────────┴───────────┐    │
│         │                              ▼                       ▼    │
│         │                    OrderWorkflowImpl      OrderActivities │
│         │                                                      │    │
│         │                                          (Spring Bean│    │
│         │                                           주입됨)    │    │
│         │                                                      │    │
│         └────────────→ Temporal Server ←───────────────────────┘    │
│                        (localhost:21733)                            │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

#### OrderController.java - REST API

```java
@RestController
@RequestMapping("/api/temporal/orders")
public class OrderController {

    private final WorkflowClient workflowClient;

    // 동기 주문 생성 (완료까지 대기)
    @PostMapping
    public ResponseEntity<OrderResult> createOrder(@RequestBody OrderRequest request) {
        // Workflow ID 생성 (고유 식별자)
        String workflowId = "order-" + UUID.randomUUID().toString().substring(0, 8);

        // Workflow 옵션 설정
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue("order-task-queue")           // Task Queue
                .setWorkflowId(workflowId)                  // 고유 ID
                .setWorkflowExecutionTimeout(Duration.ofMinutes(5))  // 전체 타임아웃
                .build();

        // Workflow Stub 생성 (프록시)
        OrderWorkflow workflow = workflowClient.newWorkflowStub(
                OrderWorkflow.class,
                options
        );

        // Workflow 실행 (완료까지 블로킹)
        OrderResult result = workflow.processOrder(request);

        return ResponseEntity.ok(result);
    }

    // 비동기 주문 생성 (즉시 반환)
    @PostMapping("/async")
    public ResponseEntity<AsyncOrderResponse> createOrderAsync(@RequestBody OrderRequest request) {
        String workflowId = "order-" + UUID.randomUUID().toString().substring(0, 8);

        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue("order-task-queue")
                .setWorkflowId(workflowId)
                .setWorkflowExecutionTimeout(Duration.ofMinutes(5))
                .build();

        OrderWorkflow workflow = workflowClient.newWorkflowStub(
                OrderWorkflow.class,
                options
        );

        // Workflow 시작만 하고 즉시 반환 (논블로킹)
        WorkflowClient.start(workflow::processOrder, request);

        return ResponseEntity.accepted().body(
            new AsyncOrderResponse(
                workflowId,
                "Workflow started. Check Temporal UI for status.",
                "http://localhost:21088/namespaces/default/workflows/" + workflowId
            )
        );
    }

    // Workflow 상태 조회
    @GetMapping("/{workflowId}")
    public ResponseEntity<OrderResult> getOrderStatus(@PathVariable String workflowId) {
        // 기존 Workflow에 연결
        OrderWorkflow workflow = workflowClient.newWorkflowStub(
                OrderWorkflow.class,
                workflowId
        );

        // 완료된 결과 조회
        OrderResult result = workflow.processOrder(null);  // Query는 별도 API 필요

        return ResponseEntity.ok(result);
    }
}
```

**동기 vs 비동기 실행:**

```
┌─────────────────────────────────────────────────────────────────────┐
│                    동기 vs 비동기 실행 비교                         │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  동기 실행 (POST /api/temporal/orders):                            │
│  ─────────────────────────────────────                              │
│  Client ──POST──→ Controller ──start──→ Workflow                   │
│    │                  │                    │                        │
│    │                  │    (수 초 ~ 수 분)  │                        │
│    │                  │                    │                        │
│    │                  │←──── 완료 ─────────│                        │
│    │←──200 OK────────│                                             │
│                                                                     │
│  특징: 결과를 바로 받지만, 응답 지연 가능                          │
│                                                                     │
│  ─────────────────────────────────────────────────────────────────  │
│                                                                     │
│  비동기 실행 (POST /api/temporal/orders/async):                    │
│  ─────────────────────────────────────────                          │
│  Client ──POST──→ Controller ──start──→ Workflow                   │
│    │                  │                    │                        │
│    │←──202 Accepted──│                    │ (계속 실행 중)         │
│    │  (workflowId)                        │                        │
│    │                                      │                        │
│    │──GET status──→ Temporal UI          │                        │
│    │                  ↓                   │                        │
│    │              Event History          ←┘ (완료 시 기록)          │
│                                                                     │
│  특징: 즉시 응답, Temporal UI에서 상태 확인                        │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

#### OrderActivitiesImpl.java - Activity 구현

```java
@Component("orderActivities")
public class OrderActivitiesImpl implements OrderActivities {

    private final RestClient orderClient;
    private final RestClient inventoryClient;
    private final RestClient paymentClient;

    // 생성자: 각 서비스 URL로 RestClient 초기화
    public OrderActivitiesImpl(
            @Value("${services.order.url}") String orderUrl,
            @Value("${services.inventory.url}") String inventoryUrl,
            @Value("${services.payment.url}") String paymentUrl) {

        this.orderClient = RestClient.builder()
                .baseUrl(orderUrl)  // http://localhost:21082
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        // ... 나머지 클라이언트 초기화
    }

    // T1: 주문 생성
    @Override
    public Long createOrder(Long customerId) {
        log.info("Activity: createOrder - customerId={}", customerId);

        Map<String, Object> response = orderClient.post()
                .uri("/api/orders")
                .body(Map.of("customerId", customerId))
                .retrieve()
                .body(Map.class);

        return ((Number) response.get("orderId")).longValue();
    }

    // T2: 재고 예약 (멱등성 키 전달)
    @Override
    public void reserveStock(Long productId, int quantity, String sagaId) {
        log.info("Activity: reserveStock - productId={}, quantity={}, sagaId={}",
                productId, quantity, sagaId);

        inventoryClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/inventory/{productId}/reserve")
                        .queryParam("quantity", quantity)
                        .queryParam("sagaId", sagaId)
                        .build(productId))
                .header("X-Idempotency-Key", sagaId + "-inventory-reserve")  // 멱등성!
                .retrieve()
                .toBodilessEntity();
    }

    // C1: 주문 취소 (보상)
    @Override
    public void cancelOrder(Long orderId) {
        // Null 체크 (보상 안전성)
        if (orderId == null) {
            log.warn("Activity: cancelOrder 스킵 - orderId is null");
            return;
        }

        log.info("Activity: cancelOrder - orderId={}", orderId);

        orderClient.patch()
                .uri("/api/orders/{orderId}/cancel", orderId)
                .retrieve()
                .toBodilessEntity();
    }

    // ... 나머지 Activity 메서드
}
```

**Activity 구현의 핵심 패턴:**

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Activity 구현 패턴                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  1. 멱등성 키 전달:                                                 │
│     header("X-Idempotency-Key", sagaId + "-inventory-reserve")     │
│     → Temporal 재시도 시 중복 실행 방지                            │
│                                                                     │
│  2. Null 체크 (보상 안전성):                                        │
│     if (orderId == null) return;                                   │
│     → 보상 Activity가 안전하게 스킵                                │
│                                                                     │
│  3. 로깅:                                                           │
│     log.info("Activity: {} - params={}", method, params);          │
│     → 디버깅 용이                                                   │
│                                                                     │
│  4. sagaId = workflowId:                                           │
│     reserveStock(productId, quantity, workflowId);                 │
│     → Temporal의 workflowId를 sagaId로 재사용                      │
│     → Semantic Lock, 멱등성에 활용                                 │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 10. 실행 흐름 완전 분석

### 10.1 성공 시나리오

```
┌─────────────────────────────────────────────────────────────────────┐
│                    주문 성공 시나리오 전체 흐름                      │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  [1] Client → orchestrator-temporal                                │
│  ────────────────────────────────────────                           │
│  POST /api/temporal/orders                                         │
│  {                                                                  │
│    "customerId": 1,                                                │
│    "productId": 1,                                                 │
│    "quantity": 2,                                                  │
│    "amount": 20000,                                                │
│    "paymentMethod": "CARD"                                         │
│  }                                                                  │
│                                                                     │
│  [2] Controller → Temporal Server                                  │
│  ────────────────────────────────────────                           │
│  workflowId = "order-abc12345" 생성                                │
│  WorkflowClient.newWorkflowStub() 호출                             │
│  workflow.processOrder(request) 호출                               │
│     ↓                                                               │
│  Temporal Server에 StartWorkflowExecution gRPC 전송               │
│     ↓                                                               │
│  History Service가 WorkflowExecutionStarted 이벤트 저장            │
│     ↓                                                               │
│  Matching Service가 Workflow Task를 Task Queue에 추가              │
│                                                                     │
│  [3] Worker가 Workflow Task 수신                                   │
│  ────────────────────────────────────────                           │
│  Worker가 "order-task-queue" Long Polling                          │
│     ↓                                                               │
│  Workflow Task 수신                                                │
│     ↓                                                               │
│  OrderWorkflowImpl.processOrder() 실행 시작                        │
│                                                                     │
│  [4] T1: 주문 생성                                                 │
│  ────────────────────────────────────────                           │
│  activities.createOrder(1)                                         │
│     ↓                                                               │
│  SDK가 ScheduleActivityTask Command 생성                           │
│     ↓                                                               │
│  Temporal Server로 전송                                            │
│     ↓                                                               │
│  Activity Task가 Task Queue에 추가                                 │
│     ↓                                                               │
│  Worker가 Activity Task 수신                                       │
│     ↓                                                               │
│  OrderActivitiesImpl.createOrder(1) 실행                           │
│     ↓                                                               │
│  REST: POST http://localhost:21082/api/orders                      │
│     ↓                                                               │
│  응답: { "orderId": 123, "status": "CREATED" }                     │
│     ↓                                                               │
│  Activity 완료, 결과(123) Temporal Server로 전송                   │
│     ↓                                                               │
│  ActivityTaskCompleted 이벤트 저장                                 │
│     ↓                                                               │
│  Workflow 재개, orderId = 123 반환                                 │
│                                                                     │
│  [5] saga.addCompensation(cancelOrder) 등록                        │
│                                                                     │
│  [6] T2: 재고 예약 (위와 유사한 흐름)                              │
│  [7] saga.addCompensation(cancelReservation) 등록                  │
│  [8] T3: 결제 생성 + 승인 (위와 유사한 흐름)                       │
│  [9] saga.addCompensation(refundPayment) 등록                      │
│  [10] T4: 주문 확정                                                 │
│  [11] T5: 재고 확정                                                 │
│  [12] T6: 결제 확정                                                 │
│                                                                     │
│  [13] Workflow 완료                                                │
│  ────────────────────────────────────────                           │
│  return OrderResult.success(123, 456, "order-abc12345")            │
│     ↓                                                               │
│  WorkflowExecutionCompleted 이벤트 저장                            │
│     ↓                                                               │
│  Controller로 결과 반환                                             │
│     ↓                                                               │
│  Client에게 200 OK 응답                                            │
│                                                                     │
│  응답:                                                              │
│  {                                                                  │
│    "success": true,                                                │
│    "orderId": 123,                                                 │
│    "paymentId": 456,                                               │
│    "workflowId": "order-abc12345"                                  │
│  }                                                                  │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 10.2 실패 시나리오 (보상 실행)

```
┌─────────────────────────────────────────────────────────────────────┐
│                    재고 부족으로 실패 시나리오                       │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  요청: quantity = 999 (재고보다 많음)                               │
│                                                                     │
│  [1-4] T1: 주문 생성 성공 (orderId = 123)                          │
│        saga.addCompensation(cancelOrder) 등록                      │
│                                                                     │
│  [5] T2: 재고 예약 시도                                            │
│  ────────────────────────────────────────                           │
│  activities.reserveStock(1, 999, "order-abc12345")                 │
│     ↓                                                               │
│  REST: POST http://localhost:21083/api/inventory/1/reserve         │
│        ?quantity=999&sagaId=order-abc12345                         │
│     ↓                                                               │
│  응답: 400 Bad Request                                             │
│  { "code": "INSUFFICIENT_STOCK", "message": "재고 부족" }          │
│     ↓                                                               │
│  Activity 실패!                                                     │
│     ↓                                                               │
│  Temporal 재시도 (RetryPolicy에 따라)                              │
│     ↓                                                               │
│  [시도 1] 실패 → 1초 대기                                          │
│  [시도 2] 실패 → 2초 대기                                          │
│  [시도 3] 실패 → ActivityFailure 발생                              │
│                                                                     │
│  [6] 보상 실행                                                     │
│  ────────────────────────────────────────                           │
│  } catch (ActivityFailure e) {                                     │
│      saga.compensate();  // 보상 시작!                              │
│  }                                                                  │
│     ↓                                                               │
│  보상 스택: [cancelOrder] ← 1개만 등록됨                           │
│     ↓                                                               │
│  C1: cancelOrder(123) 실행                                         │
│     ↓                                                               │
│  REST: PATCH http://localhost:21082/api/orders/123/cancel          │
│     ↓                                                               │
│  주문 상태: CREATED → CANCELLED                                    │
│                                                                     │
│  [7] Workflow 실패 완료                                            │
│  ────────────────────────────────────────                           │
│  return OrderResult.failure("재고 부족", "order-abc12345")         │
│     ↓                                                               │
│  WorkflowExecutionCompleted (실패) 이벤트 저장                     │
│     ↓                                                               │
│  Client에게 200 OK 응답 (비즈니스적 실패)                          │
│                                                                     │
│  응답:                                                              │
│  {                                                                  │
│    "success": false,                                               │
│    "orderId": null,                                                │
│    "paymentId": null,                                              │
│    "errorMessage": "재고 부족",                                    │
│    "workflowId": "order-abc12345"                                  │
│  }                                                                  │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 10.3 크래시 복구 시나리오

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Worker 크래시 후 복구 시나리오                    │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  [정상 진행 중]                                                     │
│  T1: 주문 생성 완료 (orderId=123) ✅                               │
│  T2: 재고 예약 완료 ✅                                              │
│  T3: 결제 생성 중...                                               │
│                                                                     │
│  Event History 상태:                                               │
│  1. WorkflowExecutionStarted                                       │
│  2. ActivityTaskScheduled (createOrder)                            │
│  3. ActivityTaskCompleted (result: 123)                            │
│  4. ActivityTaskScheduled (reserveStock)                           │
│  5. ActivityTaskCompleted (success)                                │
│  6. ActivityTaskScheduled (createPayment) ← 여기까지               │
│                                                                     │
│  ─────────────────────────────────────────────────────────────────  │
│                                                                     │
│  [T+0s] ⚡ Worker A 크래시! ⚡                                      │
│                                                                     │
│  [T+30s] Temporal Server가 Workflow Task 타임아웃 감지             │
│          → Task Queue에 재스케줄                                    │
│                                                                     │
│  [T+31s] Worker B가 Workflow Task 수신                             │
│          Event History 로드 (이벤트 1~6)                           │
│                                                                     │
│  [T+31s] Replay 시작                                               │
│  ────────────────────────────────────────                           │
│                                                                     │
│  processOrder() 시작                                                │
│                                                                     │
│  activities.createOrder(1)                                         │
│  → Event 2,3 확인: "이미 완료됨, result=123"                       │
│  → 실제 실행 안 함, 캐시된 결과 반환                               │
│  → orderId = 123                                                   │
│                                                                     │
│  saga.addCompensation(cancelOrder) 등록                            │
│                                                                     │
│  activities.reserveStock(...)                                      │
│  → Event 4,5 확인: "이미 완료됨"                                   │
│  → 실제 실행 안 함                                                  │
│                                                                     │
│  saga.addCompensation(cancelReservation) 등록                      │
│                                                                     │
│  activities.createPayment(...)                                     │
│  → Event 6 확인: "스케줄됨, 하지만 완료 안 됨"                     │
│  → 실제 Activity 실행!                                             │
│     ↓                                                               │
│  REST: POST http://localhost:21084/api/payments                    │
│     ↓                                                               │
│  응답: { "paymentId": 456 }                                        │
│     ↓                                                               │
│  ActivityTaskCompleted 이벤트 저장                                 │
│                                                                     │
│  ... 이후 정상 진행 ...                                             │
│                                                                     │
│  ─────────────────────────────────────────────────────────────────  │
│                                                                     │
│  핵심: T1, T2는 재실행되지 않음 (이미 완료)                        │
│        T3부터 재개 (Event History 기반)                            │
│        데이터 일관성 유지!                                         │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 11. orchestrator-pure vs orchestrator-temporal

### 11.1 코드 비교

```
┌─────────────────────────────────────────────────────────────────────┐
│                       코드 구조 비교                                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  orchestrator-pure/                   orchestrator-temporal/        │
│  ├── OrderSagaOrchestrator.java       ├── workflow/                 │
│  │   (167줄, 모든 로직)              │   ├── OrderWorkflow.java     │
│  ├── client/                          │   └── impl/                 │
│  │   ├── OrderServiceClient.java      │       └── OrderWorkflowImpl │
│  │   ├── InventoryServiceClient.java  │           (163줄)           │
│  │   └── PaymentServiceClient.java    ├── activity/                 │
│  └── config/                          │   ├── OrderActivities.java  │
│      └── RestClientConfig.java        │   └── impl/                 │
│      └── Resilience4j 설정            │       └── OrderActivitiesImpl│
│                                       │           (232줄)           │
│                                       └── config/                   │
│                                           └── TemporalConfig.java   │
│                                                                     │
│  파일 수: 5개                         파일 수: 6개                  │
│  총 코드: ~500줄                      총 코드: ~600줄               │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 11.2 보상 트랜잭션 비교

```java
// orchestrator-pure: 수동 보상 관리
public class OrderSagaOrchestrator {
    public SagaResult executeOrderSaga(OrderRequest request) {
        Long orderId = null;
        boolean stockReserved = false;
        Long paymentId = null;

        try {
            // T1: 주문 생성
            orderId = orderClient.createOrder(request.getCustomerId());

            // T2: 재고 예약
            inventoryClient.reserveStock(request.getProductId(),
                    request.getQuantity(), sagaId);
            stockReserved = true;

            // T3: 결제
            paymentId = paymentClient.createPayment(orderId,
                    request.getAmount(), sagaId);
            paymentClient.approvePayment(paymentId, sagaId);

            // 확정...
            return SagaResult.success(orderId, paymentId);

        } catch (Exception e) {
            // 수동 보상 (순서 직접 관리!)
            if (paymentId != null) {
                try {
                    paymentClient.refundPayment(paymentId, sagaId);
                } catch (Exception ex) {
                    log.error("환불 실패", ex);  // 보상 실패 처리도 직접
                }
            }

            if (stockReserved) {
                try {
                    inventoryClient.cancelReservation(request.getProductId(),
                            request.getQuantity(), sagaId);
                } catch (Exception ex) {
                    log.error("재고 복구 실패", ex);
                }
            }

            if (orderId != null) {
                try {
                    orderClient.cancelOrder(orderId);
                } catch (Exception ex) {
                    log.error("주문 취소 실패", ex);
                }
            }

            return SagaResult.failure(e.getMessage());
        }
    }
}

// orchestrator-temporal: 선언적 보상 관리
public class OrderWorkflowImpl implements OrderWorkflow {
    public OrderResult processOrder(OrderRequest request) {
        Saga saga = new Saga(new Saga.Options.Builder()
                .setParallelCompensation(false).build());

        try {
            // T1: 주문 생성 + 보상 등록
            Long orderId = activities.createOrder(request.customerId());
            final Long finalOrderId = orderId;
            saga.addCompensation(() -> activities.cancelOrder(finalOrderId));

            // T2: 재고 예약 + 보상 등록
            activities.reserveStock(request.productId(), request.quantity(), workflowId);
            saga.addCompensation(() -> activities.cancelReservation(...));

            // T3: 결제 + 보상 등록
            Long paymentId = activities.createPayment(...);
            final Long finalPaymentId = paymentId;
            saga.addCompensation(() -> activities.refundPayment(finalPaymentId, workflowId));

            activities.approvePayment(paymentId, workflowId);

            // 확정...
            return OrderResult.success(orderId, paymentId, workflowId);

        } catch (ActivityFailure e) {
            saga.compensate();  // 자동 역순 실행!
            return OrderResult.failure(e.getMessage(), workflowId);
        }
    }
}
```

### 11.3 기능 비교표

| 기능 | orchestrator-pure | orchestrator-temporal |
|------|-------------------|----------------------|
| **보상 순서 관리** | 수동 (개발자가 관리) | 자동 (LIFO 스택) |
| **재시도** | Resilience4j 설정 필요 | Activity 옵션으로 선언 |
| **타임아웃** | RestClient 설정 | Activity 옵션으로 선언 |
| **상태 저장** | 없음 | 자동 (Event History) |
| **크래시 복구** | 불가능 | 자동 (Replay) |
| **모니터링** | 로그만 | Temporal UI |
| **비동기 실행** | 미지원 | 지원 |
| **Workflow ID 추적** | sagaId 직접 생성 | workflowId 자동 |
| **서킷브레이커** | Resilience4j | 미지원 (별도 구현 필요) |
| **분산 락** | RLock 직접 | 별도 구현 필요 |
| **멱등성** | IdempotencyService | 별도 구현 필요 |

### 11.4 언제 무엇을 선택할까?

```
┌─────────────────────────────────────────────────────────────────────┐
│                       선택 가이드                                   │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  orchestrator-pure를 선택할 때:                                     │
│  ─────────────────────────────                                      │
│  • 간단한 Saga (2-3단계)                                           │
│  • Temporal 인프라 운영 부담이 클 때                               │
│  • 이미 Resilience4j에 익숙할 때                                   │
│  • 크래시 복구가 필수가 아닐 때                                    │
│                                                                     │
│  orchestrator-temporal을 선택할 때:                                │
│  ────────────────────────────────                                   │
│  • 복잡한 Saga (4단계 이상)                                        │
│  • 장기 실행 Workflow (몇 분 ~ 며칠)                               │
│  • 크래시 복구가 필수일 때                                         │
│  • 실시간 모니터링이 중요할 때                                     │
│  • 비동기 실행이 필요할 때                                         │
│  • 여러 팀이 Workflow를 공유할 때                                  │
│                                                                     │
│  핵심: Temporal은 "마법"이 아님                                    │
│  ────────────────────────────                                       │
│  • 분산 락 → 여전히 필요                                           │
│  • 멱등성 → 여전히 필요                                            │
│  • Semantic Lock → 여전히 필요                                     │
│                                                                     │
│  Phase 2에서 배운 기술 + Temporal = 완전한 솔루션                  │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 부록: Temporal UI 사용법

### A.1 접속

```
URL: http://localhost:21088
Namespace: default
```

### A.2 Workflow 목록

```
┌─────────────────────────────────────────────────────────────────────┐
│  Workflows                                                          │
├─────────────────────────────────────────────────────────────────────┤
│  Workflow ID          │ Type          │ Status    │ Start Time     │
│  ─────────────────────┼───────────────┼───────────┼─────────────── │
│  order-abc12345       │ OrderWorkflow │ Completed │ 2분 전          │
│  order-def67890       │ OrderWorkflow │ Running   │ 1분 전          │
│  order-ghi11111       │ OrderWorkflow │ Failed    │ 5분 전          │
└─────────────────────────────────────────────────────────────────────┘
```

### A.3 Event History 조회

```
Workflow: order-abc12345

Event History:
┌─────┬──────────────────────────────┬─────────────────────────────────┐
│ ID  │ Event Type                   │ Details                         │
├─────┼──────────────────────────────┼─────────────────────────────────┤
│ 1   │ WorkflowExecutionStarted     │ input: OrderRequest{...}        │
│ 2   │ WorkflowTaskScheduled        │                                 │
│ 3   │ WorkflowTaskStarted          │ worker: worker-abc              │
│ 4   │ WorkflowTaskCompleted        │ commands: ScheduleActivity...   │
│ 5   │ ActivityTaskScheduled        │ activityType: createOrder       │
│ 6   │ ActivityTaskStarted          │ worker: worker-abc              │
│ 7   │ ActivityTaskCompleted        │ result: 123                     │
│ ... │ ...                          │ ...                             │
│ N   │ WorkflowExecutionCompleted   │ result: OrderResult{...}        │
└─────┴──────────────────────────────┴─────────────────────────────────┘
```

---

## 참고 자료

### 공식 문서
- [Temporal 공식 문서](https://docs.temporal.io/)
- [How Temporal Works](https://temporal.io/how-it-works)
- [Event History](https://docs.temporal.io/workflow-execution/event)
- [Retry Policies](https://docs.temporal.io/encyclopedia/retry-policies)
- [Worker Performance](https://docs.temporal.io/develop/worker-performance)

### 아키텍처 심화
- [Temporal Server 내부 아키텍처](https://medium.com/data-science-collective/system-design-series-a-step-by-step-breakdown-of-temporals-internal-architecture-52340cc36f30)
- [Worker Architecture and Scaling](https://levelup.gitconnected.com/temporal-worker-architecture-and-scaling-af0c670ce6c1)

### Saga 패턴
- [Saga Pattern in Microservices](https://temporal.io/blog/mastering-saga-patterns-for-distributed-transactions-in-microservices)
- [Saga Orchestration vs Choreography](https://temporal.io/blog/to-choreograph-or-orchestrate-your-saga-that-is-the-question)

### 결정적 코드
- [Understanding Non-Determinism](https://medium.com/@sanhdoan/understanding-non-determinism-in-temporal-io-why-it-matters-how-to-avoid-it-3d397d8a5793)
- [Workflow Definition Rules](https://docs.temporal.io/workflow-definition)

### 재시도 및 타임아웃
- [Activity Timeouts](https://temporal.io/blog/activity-timeouts)
- [Failure Handling Best Practices](https://temporal.io/blog/failure-handling-in-practice)

---

*작성: 2026-02-05*
*대상: Temporal 입문자*
*난이도: 중급 (분산 시스템 기본 지식 필요)*
