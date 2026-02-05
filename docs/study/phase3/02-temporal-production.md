# Temporal 프로덕션 가이드

> **전제**: `00-temporal-deep-dive.md`, `01-temporal-advanced-concepts.md` 학습 완료
> **작성일**: 2026-02-05

---

## 목차

1. [Workflow Versioning](#1-workflow-versioning)
2. [Schedule과 Cron Job](#2-schedule과-cron-job)
3. [Namespace와 Task Queue](#3-namespace와-task-queue)
4. [프로덕션 배포 아키텍처](#4-프로덕션-배포-아키텍처)
5. [Spring Boot 통합 심화](#5-spring-boot-통합-심화)
6. [모니터링과 운영](#6-모니터링과-운영)

---

## 1. Workflow Versioning

> **출처**: [Versioning - Java SDK](https://docs.temporal.io/develop/java/versioning), [Safe Deployments](https://docs.temporal.io/develop/safe-deployments)

### 1.1 Versioning이 필요한 이유

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    코드 변경 시 Non-Determinism 문제                     │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  상황: 실행 중인 Workflow가 있는 상태에서 코드 변경                     │
│                                                                         │
│  원본 코드 (v1):                                                        │
│  ┌─────────────────────────────────────────────────────────────┐       │
│  │  orderId = activities.createOrder();                        │       │
│  │  activities.reserveStock();                                 │       │
│  │  activities.processPayment();                               │       │
│  └─────────────────────────────────────────────────────────────┘       │
│                                                                         │
│  변경된 코드 (v2): 알림 추가                                            │
│  ┌─────────────────────────────────────────────────────────────┐       │
│  │  orderId = activities.createOrder();                        │       │
│  │  activities.sendNotification();  // ← 새로 추가!            │       │
│  │  activities.reserveStock();                                 │       │
│  │  activities.processPayment();                               │       │
│  └─────────────────────────────────────────────────────────────┘       │
│                                                                         │
│  문제 발생:                                                             │
│  ┌─────────────────────────────────────────────────────────────┐       │
│  │  기존 Workflow (v1로 시작, Event History 있음)              │       │
│  │  Event 5: ActivityTaskScheduled (createOrder)               │       │
│  │  Event 6: ActivityTaskCompleted (createOrder)               │       │
│  │  Event 7: ActivityTaskScheduled (reserveStock)  ← 기대값   │       │
│  │                                                              │       │
│  │  Replay 시 (v2 코드로):                                      │       │
│  │  → createOrder 완료                                          │       │
│  │  → sendNotification 스케줄 시도                              │       │
│  │  → Event 7은 reserveStock인데?                              │       │
│  │  → ❌ Non-Deterministic Error!                               │       │
│  └─────────────────────────────────────────────────────────────┘       │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 1.2 두 가지 Versioning 방법

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Versioning 방법 비교                                 │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  방법 1: Workflow.getVersion (Patching)                                │
│  ─────────────────────────────────────                                  │
│  • 코드 내에서 버전 분기                                                │
│  • 기존/신규 실행 모두 같은 Worker에서 처리                             │
│  • 코드가 복잡해질 수 있음                                              │
│                                                                         │
│  방법 2: Worker Versioning (Build ID)                                  │
│  ─────────────────────────────────────                                  │
│  • Worker 자체를 버전별로 분리                                          │
│  • 기존 실행은 기존 Worker, 신규 실행은 신규 Worker                     │
│  • 인프라 관리 필요                                                     │
│                                                                         │
│  권장:                                                                  │
│  • 소규모 변경 → Workflow.getVersion                                   │
│  • 대규모 변경 → Worker Versioning                                     │
│  • 처음 시작 → Workflow.getVersion 먼저 익히기                         │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 1.3 Workflow.getVersion 사용법

```java
public class OrderWorkflowImpl implements OrderWorkflow {

    @Override
    public OrderResult processOrder(OrderRequest request) {
        Long orderId = activities.createOrder(request.customerId());

        // 버전 체크: "add-notification" 변경점
        // DEFAULT_VERSION = 기존 코드 (변경 전)
        // 1 = 새 버전 (알림 추가)
        int version = Workflow.getVersion(
            "add-notification",           // 변경점 식별자
            Workflow.DEFAULT_VERSION,     // 최소 지원 버전
            1                             // 최대 버전 (현재)
        );

        if (version >= 1) {
            // 새 버전: 알림 발송
            activities.sendNotification(orderId);
        }
        // 기존 버전 (DEFAULT_VERSION): 알림 없이 계속

        activities.reserveStock(request.productId(), request.quantity());
        activities.processPayment(orderId, request.amount());

        return OrderResult.success(orderId);
    }
}
```

### 1.4 버전 마커 동작 원리

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Version Marker 동작                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  새 Workflow 실행 시 (v2 코드):                                        │
│  ┌─────────────────────────────────────────────────────────────┐       │
│  │  Event 5: ActivityTaskCompleted (createOrder)               │       │
│  │  Event 6: MarkerRecorded                                    │       │
│  │           markerName: "Version"                             │       │
│  │           details: {changeId: "add-notification", version: 1}│      │
│  │  Event 7: ActivityTaskScheduled (sendNotification)          │       │
│  │  Event 8: ActivityTaskCompleted (sendNotification)          │       │
│  │  Event 9: ActivityTaskScheduled (reserveStock)              │       │
│  └─────────────────────────────────────────────────────────────┘       │
│                                                                         │
│  기존 Workflow Replay 시 (v2 코드):                                    │
│  ┌─────────────────────────────────────────────────────────────┐       │
│  │  Event History에 MarkerRecorded 없음                        │       │
│  │  → getVersion() 호출                                        │       │
│  │  → Marker 없음 = DEFAULT_VERSION 반환                       │       │
│  │  → if (version >= 1) 조건 false                             │       │
│  │  → sendNotification 건너뜀                                  │       │
│  │  → reserveStock 실행 (Event History와 일치!)                │       │
│  └─────────────────────────────────────────────────────────────┘       │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 1.5 버전 업그레이드 단계

```java
// 단계 1: 새 기능 추가 (version 1)
int version = Workflow.getVersion("add-notification", Workflow.DEFAULT_VERSION, 1);
if (version >= 1) {
    activities.sendNotification(orderId);
}

// 단계 2: 모든 기존 Workflow 완료 후, 최소 버전 올리기
int version = Workflow.getVersion("add-notification", 1, 1);  // DEFAULT_VERSION 제거
// 이제 모든 실행에서 알림 발송
activities.sendNotification(orderId);

// 단계 3: 최종적으로 getVersion 제거 (선택)
// 모든 실행이 version 1 이상임이 확실할 때
activities.sendNotification(orderId);  // 그냥 호출
```

### 1.6 Replay Test로 검증

```java
// Replay Test: 기존 Event History로 새 코드 검증
@Test
public void testVersionCompatibility() {
    // 1. 기존 실행의 Event History 가져오기
    WorkflowHistory history = WorkflowHistoryLoader.loadFromResource(
        "workflow-history-v1.json"
    );

    // 2. 새 코드로 Replay
    WorkflowReplayer.replayWorkflowExecution(
        history,
        OrderWorkflowImpl.class  // 새 버전 코드
    );

    // 3. Non-Determinism 에러 없으면 통과
}
```

---

## 2. Schedule과 Cron Job

> **출처**: [Schedule](https://docs.temporal.io/schedule), [Cron Job](https://docs.temporal.io/cron-job)

### 2.1 Cron Job vs Schedule

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Cron Job vs Schedule 비교                            │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Cron Job (기존 방식):                                                  │
│  ─────────────────────                                                  │
│  • Workflow 시작 시 Cron Schedule 지정                                 │
│  • 중간에 스케줄 변경 불가                                              │
│  • 일시 정지/재개 불가                                                  │
│  • 백필 (과거 실행 보충) 불가                                           │
│                                                                         │
│  Schedule (권장):                                                       │
│  ─────────────────                                                      │
│  • 독립적인 스케줄 객체 생성                                            │
│  • 언제든 스케줄 수정 가능                                              │
│  • 일시 정지/재개 가능                                                  │
│  • 백필 지원                                                            │
│  • 더 유연한 시간 설정                                                  │
│                                                                         │
│  결론: 새 프로젝트는 Schedule 사용 권장                                 │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Cron Job 사용법

```java
// Cron 표현식으로 Workflow 실행
WorkflowOptions options = WorkflowOptions.newBuilder()
        .setTaskQueue("batch-task-queue")
        .setWorkflowId("daily-report")
        .setCronSchedule("0 2 * * *")  // 매일 새벽 2시
        .build();

DailyReportWorkflow workflow = workflowClient.newWorkflowStub(
        DailyReportWorkflow.class,
        options
);

// 시작하면 매일 새벽 2시에 자동 실행
WorkflowClient.start(workflow::generateReport);
```

**Cron 표현식:**
```
┌───────────── 분 (0 - 59)
│ ┌───────────── 시 (0 - 23)
│ │ ┌───────────── 일 (1 - 31)
│ │ │ ┌───────────── 월 (1 - 12)
│ │ │ │ ┌───────────── 요일 (0 - 6, 0=일요일)
│ │ │ │ │
* * * * *

예시:
• "0 2 * * *"     : 매일 새벽 2시
• "*/5 * * * *"   : 5분마다
• "0 9 * * 1-5"   : 평일 오전 9시
• "0 0 1 * *"     : 매월 1일 자정
```

### 2.3 Schedule 사용법 (권장)

```java
// Schedule 생성
ScheduleClient scheduleClient = ScheduleClient.newInstance(
        workflowServiceStubs,
        ScheduleClientOptions.newBuilder()
                .setNamespace("default")
                .build()
);

// Schedule 정의
Schedule schedule = Schedule.newBuilder()
        .setAction(
            ScheduleActionStartWorkflow.newBuilder()
                .setWorkflowType(DailyReportWorkflow.class)
                .setTaskQueue("batch-task-queue")
                .setWorkflowId("daily-report")
                .build()
        )
        .setSpec(
            ScheduleSpec.newBuilder()
                // 매일 새벽 2시 (Cron 표현식)
                .setCronExpressions(List.of("0 2 * * *"))
                // 또는 Interval 사용
                // .setIntervals(List.of(ScheduleIntervalSpec.newBuilder()
                //         .setEvery(Duration.ofHours(1))
                //         .build()))
                .build()
        )
        .setPolicy(
            SchedulePolicy.newBuilder()
                // 이전 실행이 안 끝났으면?
                .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP)
                .build()
        )
        .build();

// Schedule 생성
ScheduleHandle handle = scheduleClient.createSchedule(
        "daily-report-schedule",  // Schedule ID
        schedule,
        ScheduleOptions.newBuilder().build()
);
```

### 2.4 Schedule 관리

```java
// Schedule 조회
ScheduleHandle handle = scheduleClient.getHandle("daily-report-schedule");
ScheduleDescription description = handle.describe();
System.out.println("다음 실행: " + description.getInfo().getNextActionTimes());

// Schedule 일시 정지
handle.pause("점검 중");

// Schedule 재개
handle.unpause();

// Schedule 수정
handle.update(input -> {
    Schedule current = input.getDescription().getSchedule();
    return Schedule.newBuilder(current)
            .setSpec(
                ScheduleSpec.newBuilder()
                    .setCronExpressions(List.of("0 3 * * *"))  // 3시로 변경
                    .build()
            )
            .build();
});

// 즉시 실행 (트리거)
handle.trigger(ScheduleTriggerOptions.newBuilder().build());

// 백필 (과거 실행 보충)
handle.backfill(
    ScheduleBackfill.newBuilder()
        .setStartAt(Instant.parse("2026-02-01T00:00:00Z"))
        .setEndAt(Instant.parse("2026-02-05T00:00:00Z"))
        .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_ALLOW_ALL)
        .build()
);

// Schedule 삭제
handle.delete();
```

### 2.5 Overlap Policy

| Policy | 설명 | 사용 시점 |
|--------|------|----------|
| **SKIP** | 이전 실행 중이면 새 실행 건너뜀 | 중복 실행 불가 작업 |
| **BUFFER_ONE** | 하나만 대기열에 저장 | 최신 실행 보장 |
| **BUFFER_ALL** | 모두 대기열에 저장 | 모든 실행 필요 |
| **CANCEL_OTHER** | 이전 실행 취소 후 새 실행 | 최신 실행만 필요 |
| **TERMINATE_OTHER** | 이전 실행 강제 종료 후 새 실행 | 긴급 대체 |
| **ALLOW_ALL** | 병렬 실행 허용 | 독립적인 작업 |

---

## 3. Namespace와 Task Queue

> **출처**: [Task Queues](https://docs.temporal.io/task-queue)

### 3.1 Namespace 개념

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Namespace 구조                                   │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Temporal Cluster                                                      │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                                                                  │   │
│  │  Namespace: production                                          │   │
│  │  ┌───────────────────────────────────────────────────────────┐  │   │
│  │  │  • Workflow: order-123, order-456, payment-789...         │  │   │
│  │  │  • Task Queues: order-queue, payment-queue...             │  │   │
│  │  │  • Schedules: daily-report, hourly-sync...                │  │   │
│  │  └───────────────────────────────────────────────────────────┘  │   │
│  │                                                                  │   │
│  │  Namespace: staging                                             │   │
│  │  ┌───────────────────────────────────────────────────────────┐  │   │
│  │  │  • Workflow: order-test-1, order-test-2...                │  │   │
│  │  │  • Task Queues: order-queue, payment-queue...             │  │   │
│  │  └───────────────────────────────────────────────────────────┘  │   │
│  │                                                                  │   │
│  │  Namespace: development                                         │   │
│  │  ┌───────────────────────────────────────────────────────────┐  │   │
│  │  │  • (개발 환경)                                             │  │   │
│  │  └───────────────────────────────────────────────────────────┘  │   │
│  │                                                                  │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  특징:                                                                  │
│  • 완전한 격리 (서로 영향 없음)                                        │
│  • 같은 Workflow ID도 다른 Namespace면 공존 가능                       │
│  • 별도의 보존 기간 설정 가능                                          │
│  • 별도의 권한 설정 가능                                                │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Task Queue 파티셔닝

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Task Queue Partitioning                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  기본 설정: 4 파티션                                                   │
│                                                                         │
│  order-task-queue                                                      │
│  ┌─────────────┬─────────────┬─────────────┬─────────────┐             │
│  │ Partition 0 │ Partition 1 │ Partition 2 │ Partition 3 │             │
│  │  (Task A)   │  (Task B)   │  (Task C)   │  (Task D)   │             │
│  │  (Task E)   │  (Task F)   │             │             │             │
│  └──────┬──────┴──────┬──────┴──────┬──────┴──────┬──────┘             │
│         │             │             │             │                     │
│         ▼             ▼             ▼             ▼                     │
│     Worker 1      Worker 2      Worker 3      Worker 4                 │
│                                                                         │
│  고부하 시: 파티션 수 증가 (동적 설정)                                  │
│                                                                         │
│  // temporal 설정 (dynamic config)                                     │
│  matching.numTaskqueueReadPartitions: 16                               │
│  matching.numTaskqueueWritePartitions: 16                              │
│                                                                         │
│  스케일링 고려사항:                                                    │
│  • 파티션 수 ≈ 최대 Worker 수                                          │
│  • 너무 많으면 오버헤드 증가                                            │
│  • 너무 적으면 병목 발생                                                │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.3 Task Queue 설계 패턴

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Task Queue 설계 패턴                                 │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  패턴 1: 도메인별 분리                                                 │
│  ─────────────────────                                                  │
│  • order-task-queue     → Order 관련 Workflow/Activity                 │
│  • payment-task-queue   → Payment 관련 Workflow/Activity               │
│  • notification-queue   → 알림 관련 Activity                           │
│                                                                         │
│  장점: 도메인별 독립 스케일링, 장애 격리                               │
│                                                                         │
│  ─────────────────────────────────────────────────────────────────────  │
│                                                                         │
│  패턴 2: 우선순위별 분리                                               │
│  ─────────────────────                                                  │
│  • high-priority-queue  → VIP 고객, 긴급 주문                          │
│  • normal-queue         → 일반 주문                                    │
│  • batch-queue          → 배치 작업 (낮은 우선순위)                    │
│                                                                         │
│  장점: 중요 작업 우선 처리                                             │
│                                                                         │
│  ─────────────────────────────────────────────────────────────────────  │
│                                                                         │
│  패턴 3: 리소스별 분리                                                 │
│  ─────────────────────                                                  │
│  • cpu-intensive-queue  → CPU 집약적 작업 (분석, 렌더링)               │
│  • io-intensive-queue   → I/O 집약적 작업 (파일 처리)                  │
│  • api-queue            → 외부 API 호출                                │
│                                                                         │
│  장점: Worker 리소스 최적화                                            │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 4. 프로덕션 배포 아키텍처

> **출처**: [Production Deployment](https://docs.temporal.io/production-deployment), [High Availability](https://docs.temporal.io/evaluate/development-production-features/high-availability)

### 4.1 자체 호스팅 아키텍처

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    프로덕션 Temporal 아키텍처                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Load Balancer (L7)                                                    │
│       │                                                                 │
│       ▼                                                                 │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                    Frontend Service                             │   │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐                         │   │
│  │  │Frontend │  │Frontend │  │Frontend │  (Stateless, 수평 확장) │   │
│  │  │  Pod 1  │  │  Pod 2  │  │  Pod 3  │                         │   │
│  │  └─────────┘  └─────────┘  └─────────┘                         │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│       │                                                                 │
│       ├──────────────────┬──────────────────┐                          │
│       ▼                  ▼                  ▼                          │
│  ┌──────────┐       ┌──────────┐       ┌──────────┐                   │
│  │ History  │       │ Matching │       │  Worker  │                   │
│  │ Service  │       │ Service  │       │ Service  │                   │
│  │ (Sharded)│       │(Partitioned)│    │          │                   │
│  │ 3+ Pods  │       │ 3+ Pods  │       │ 2+ Pods  │                   │
│  └────┬─────┘       └──────────┘       └──────────┘                   │
│       │                                                                 │
│       ▼                                                                 │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                    Persistence Layer                            │   │
│  │  ┌──────────────────────┐  ┌──────────────────────┐            │   │
│  │  │  PostgreSQL/MySQL    │  │   Elasticsearch      │            │   │
│  │  │  (Primary + Replica) │  │   (선택, 검색용)      │            │   │
│  │  └──────────────────────┘  └──────────────────────┘            │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  권장 사양:                                                            │
│  • History Shards: 512 (소규모), 4096 (대규모)                         │
│  • PostgreSQL: 8 vCPU, 32GB RAM, SSD                                   │
│  • 각 Service: 최소 3 Pod (HA)                                         │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Worker 배포 전략

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Worker 배포 전략                                     │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Kubernetes 배포 예시:                                                 │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  Deployment: order-worker                                       │   │
│  │  replicas: 3                                                    │   │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐                         │   │
│  │  │ Pod 1   │  │ Pod 2   │  │ Pod 3   │                         │   │
│  │  │ Worker  │  │ Worker  │  │ Worker  │                         │   │
│  │  │  (AZ-a) │  │  (AZ-b) │  │  (AZ-c) │  ← 가용 영역 분산       │   │
│  │  └─────────┘  └─────────┘  └─────────┘                         │   │
│  │                                                                 │   │
│  │  Task Queue: order-task-queue                                   │   │
│  │  Workflow: OrderWorkflow                                        │   │
│  │  Activity: OrderActivities                                      │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  Deployment: payment-worker                                     │   │
│  │  replicas: 5  ← 더 많은 부하 처리                               │   │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ...                    │   │
│  │  │ Pod 1   │  │ Pod 2   │  │ Pod 3   │                         │   │
│  │  └─────────┘  └─────────┘  └─────────┘                         │   │
│  │                                                                 │   │
│  │  Task Queue: payment-task-queue                                 │   │
│  │  Activity: PaymentActivities                                    │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  스케일링 기준:                                                        │
│  • Task Queue 대기열 크기                                              │
│  • Activity 처리 시간                                                  │
│  • Worker CPU/메모리 사용률                                            │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 4.3 고가용성 체크리스트

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    프로덕션 체크리스트                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  □ Temporal Server                                                     │
│    □ History Shard 수 결정 (변경 불가!)                                │
│    □ 각 서비스 최소 3 인스턴스                                         │
│    □ 가용 영역 분산 배포                                               │
│    □ 리소스 할당 (CPU, Memory)                                         │
│                                                                         │
│  □ 데이터베이스                                                        │
│    □ PostgreSQL/MySQL 고가용성 구성                                    │
│    □ 자동 백업 설정                                                    │
│    □ 연결 풀 크기 설정                                                 │
│    □ 쿼리 성능 모니터링                                                │
│                                                                         │
│  □ Worker                                                              │
│    □ 최소 2 인스턴스 (HA)                                              │
│    □ Graceful Shutdown 구현                                            │
│    □ Health Check 엔드포인트                                           │
│    □ 리소스 제한 설정                                                  │
│                                                                         │
│  □ 모니터링                                                            │
│    □ Prometheus 메트릭 수집                                            │
│    □ Grafana 대시보드                                                  │
│    □ 알림 설정 (Slack, PagerDuty)                                      │
│    □ 로그 수집 (ELK, Loki)                                             │
│                                                                         │
│  □ 보안                                                                │
│    □ TLS 설정 (gRPC)                                                   │
│    □ 인증/인가 설정                                                    │
│    □ Namespace 권한 분리                                               │
│    □ 민감 데이터 암호화                                                │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Spring Boot 통합 심화

> **출처**: [Spring Boot Integration](https://docs.temporal.io/develop/java/spring-boot-integration)

### 5.1 temporal-spring-boot-starter 설정

```yaml
# application.yml
spring:
  temporal:
    # 연결 설정
    connection:
      target: localhost:7233
      # TLS 설정 (프로덕션)
      # mtls:
      #   key-file: /path/to/client.key
      #   cert-chain-file: /path/to/client.pem

    # Namespace
    namespace: default

    # Worker 설정
    workers:
      - name: order-worker
        task-queue: order-task-queue
        # Workflow 스레드 수
        workflow-executor-threads: 200
        # Activity 스레드 수
        activity-executor-threads: 200
        # 동시 Workflow Task
        max-concurrent-workflow-task-executors: 200
        # 동시 Activity Task
        max-concurrent-activity-executors: 200

    # 테스트 서버 (개발용)
    # test-server:
    #   enabled: true
```

### 5.2 Auto-Discovery 설정

```java
// Workflow 구현체에 @WorkflowImpl 추가
@WorkflowImpl(taskQueues = "order-task-queue")
public class OrderWorkflowImpl implements OrderWorkflow {
    // ...
}

// application.yml에서 패키지 스캔 설정
spring:
  temporal:
    workers:
      - name: order-worker
        task-queue: order-task-queue
    # 자동 발견 패키지
    packages:
      - com.hanumoka.orchestrator.temporal.workflow
      - com.hanumoka.orchestrator.temporal.activity
```

### 5.3 Activity에 Spring Bean 주입

```java
// Activity 구현체 (Spring Bean)
@Component
public class OrderActivitiesImpl implements OrderActivities {

    // Spring Bean 주입 가능!
    private final OrderRepository orderRepository;
    private final RestTemplate restTemplate;
    private final RedisTemplate<String, String> redisTemplate;

    @Autowired
    public OrderActivitiesImpl(
            OrderRepository orderRepository,
            RestTemplate restTemplate,
            RedisTemplate<String, String> redisTemplate) {
        this.orderRepository = orderRepository;
        this.restTemplate = restTemplate;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Long createOrder(Long customerId) {
        // Spring Data JPA 사용 가능
        Order order = new Order(customerId);
        order = orderRepository.save(order);
        return order.getId();
    }

    @Override
    @Transactional  // Spring 트랜잭션 사용 가능
    public void confirmOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문 없음"));
        order.confirm();
        orderRepository.save(order);
    }
}
```

### 5.4 우리 프로젝트 설정 분석

```java
// TemporalConfig.java (우리 프로젝트)
@Configuration
public class TemporalConfig {

    @Value("${temporal.connection.target:localhost:7233}")
    private String temporalTarget;

    @Bean
    public WorkflowServiceStubs workflowServiceStubs() {
        return WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget(temporalTarget)
                .build()
        );
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs stubs) {
        return WorkflowClient.newInstance(stubs,
            WorkflowClientOptions.newBuilder()
                .setNamespace("default")
                .build()
        );
    }

    @Bean
    public WorkerFactory workerFactory(WorkflowClient client) {
        return WorkerFactory.newInstance(client);
    }

    @Bean
    public Worker worker(WorkerFactory factory, OrderActivities activities) {
        Worker worker = factory.newWorker("order-task-queue");

        // Workflow 등록
        worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);

        // Activity 등록 (Spring Bean!)
        worker.registerActivitiesImplementations(activities);

        // Worker 시작
        factory.start();

        return worker;
    }
}
```

**핵심 포인트:**
- `OrderActivities`는 Spring Bean (`@Component`)
- `worker.registerActivitiesImplementations(activities)`로 주입
- Activity에서 DB, Redis, RestClient 등 모든 Spring Bean 사용 가능

---

## 6. 모니터링과 운영

### 6.1 Temporal UI 활용

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       Temporal UI 기능                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  URL: http://localhost:21088 (우리 프로젝트)                            │
│                                                                         │
│  1. Workflow 목록                                                      │
│     • 실행 중/완료/실패 Workflow 조회                                  │
│     • 필터링 (상태, 타입, 시간)                                        │
│     • 검색 (Workflow ID, Search Attribute)                             │
│                                                                         │
│  2. Workflow 상세                                                      │
│     • Event History 전체 조회                                          │
│     • 각 Event 상세 정보 (입력, 출력, 시간)                            │
│     • Timeline 시각화                                                  │
│     • 실패 원인 분석                                                   │
│                                                                         │
│  3. 액션                                                               │
│     • Signal 전송                                                      │
│     • Query 실행                                                       │
│     • 취소 요청                                                        │
│     • 종료 (Terminate)                                                 │
│     • 재시작 (Reset)                                                   │
│                                                                         │
│  4. Task Queue 모니터링                                                │
│     • Poller 수                                                        │
│     • 대기 중인 Task 수                                                │
│     • 처리율                                                           │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 6.2 주요 메트릭

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       핵심 모니터링 메트릭                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Workflow 메트릭:                                                      │
│  ─────────────────                                                      │
│  • workflow_started_total: 시작된 Workflow 수                          │
│  • workflow_completed_total: 완료된 Workflow 수                        │
│  • workflow_failed_total: 실패한 Workflow 수                           │
│  • workflow_execution_latency: 실행 시간 분포                          │
│                                                                         │
│  Activity 메트릭:                                                      │
│  ─────────────────                                                      │
│  • activity_execution_latency: Activity 실행 시간                      │
│  • activity_execution_failed: Activity 실패 수                         │
│  • activity_task_timeout: Activity 타임아웃 수                         │
│                                                                         │
│  Task Queue 메트릭:                                                    │
│  ─────────────────                                                      │
│  • task_queue_poll_success: 성공적인 Poll 수                           │
│  • task_queue_poll_no_task: Task 없는 Poll 수                          │
│  • task_latency: Task 대기 시간                                        │
│                                                                         │
│  Worker 메트릭:                                                        │
│  ─────────────────                                                      │
│  • worker_task_slots_available: 사용 가능한 슬롯                       │
│  • worker_task_slots_used: 사용 중인 슬롯                              │
│                                                                         │
│  알림 설정 예시:                                                       │
│  ─────────────────                                                      │
│  • workflow_failed_total 급증 → 즉시 알림                              │
│  • task_latency > 10s → 경고 알림                                      │
│  • worker_task_slots_available = 0 → 스케일 업 알림                    │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 6.3 Graceful Shutdown

```java
// Worker Graceful Shutdown 구현
@Component
public class TemporalWorkerLifecycle implements DisposableBean {

    private final WorkerFactory workerFactory;

    public TemporalWorkerLifecycle(WorkerFactory workerFactory) {
        this.workerFactory = workerFactory;
    }

    @Override
    public void destroy() throws Exception {
        // 1. 새 Task 폴링 중지
        workerFactory.shutdown();

        // 2. 진행 중인 Task 완료 대기 (최대 30초)
        workerFactory.awaitTermination(30, TimeUnit.SECONDS);

        // 3. 강제 종료 (대기 시간 초과 시)
        workerFactory.shutdownNow();
    }
}
```

---

## 참고 자료

### 공식 문서
- [Versioning](https://docs.temporal.io/develop/java/versioning)
- [Safe Deployments](https://docs.temporal.io/develop/safe-deployments)
- [Schedule](https://docs.temporal.io/schedule)
- [Task Queues](https://docs.temporal.io/task-queue)
- [Production Deployment](https://docs.temporal.io/production-deployment)
- [High Availability](https://docs.temporal.io/evaluate/development-production-features/high-availability)
- [Spring Boot Integration](https://docs.temporal.io/develop/java/spring-boot-integration)

### 블로그
- [Schedules vs Cron Jobs](https://temporal.io/blog/temporal-schedules-reliable-scalable-and-more-flexible-than-cron-jobs)
- [Scaling Temporal](https://dev.to/temporalio/scaling-temporal-the-basics-31l5)
- [Worker Architecture and Scaling](https://levelup.gitconnected.com/temporal-worker-architecture-and-scaling-af0c670ce6c1)

### GitHub
- [temporal-spring-boot-starter](https://github.com/temporalio/sdk-java/tree/master/temporal-spring-boot-autoconfigure)
- [Spring Boot Demo](https://github.com/temporalio/spring-boot-demo)
- [Java Samples](https://github.com/temporalio/samples-java)

---

*이전 문서: `01-temporal-advanced-concepts.md`*
*다음: 실습 진행*
