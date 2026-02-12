# Temporal 프로덕션 가이드

> **전제**: `00-temporal-deep-dive.md`, `01-temporal-concepts.md` 학습 완료
> **작성일**: 2026-02-10

---

## 목차

1. [Workflow Versioning](#1-workflow-versioning)
2. [Schedule과 Cron Job](#2-schedule과-cron-job)
3. [Namespace와 Task Queue 설계](#3-namespace와-task-queue-설계)
4. [프로덕션 배포 아키텍처](#4-프로덕션-배포-아키텍처)
5. [Temporal Cloud vs Self-Hosted](#5-temporal-cloud-vs-self-hosted)
6. [모니터링과 운영](#6-모니터링과-운영)
7. [Workflow 수정 시 주의사항](#7-workflow-수정-시-주의사항)

---

## 1. Workflow Versioning

> **출처**: [Versioning - Java SDK](https://docs.temporal.io/develop/java/versioning)

### 1.1 왜 Versioning이 필요한가?

Temporal Workflow는 Event Sourcing 기반이다. 코드가 변경되면 기존 Workflow의 Event History와
새 코드의 실행 경로가 달라져 **Non-Deterministic Error**가 발생한다.

```
+-----------------------------------------------------------------------+
|  원본 코드 (v1):          |  변경된 코드 (v2):                         |
|  createOrder()            |  createOrder()                             |
|  reserveStock()           |  sendNotification()  // <-- 새로 추가!     |
|  processPayment()         |  reserveStock()                            |
|                           |  processPayment()                          |
+-----------------------------------------------------------------------+
|  기존 Workflow Replay 시 (v2 코드로):                                  |
|  Event 7은 reserveStock인데, 새 코드는 sendNotification 시도           |
|  --> Non-Deterministic Error!                                          |
+-----------------------------------------------------------------------+
```

### 1.2 두 가지 Versioning 방법

| 방법 | 특징 | 권장 시점 |
|------|------|----------|
| **Workflow.getVersion** (Patching) | 코드 내 버전 분기, 같은 Worker에서 처리 | 소규모 변경 |
| **Worker Versioning** (Build ID) | Worker 자체를 버전별로 분리, 인프라 관리 필요 | 대규모 변경 |

처음 시작한다면 **Workflow.getVersion** 먼저 익히는 것을 권장한다.

> **[프로젝트 참고]**: Versioning은 학습 목적으로 문서화했으며, 현재 프로젝트 코드에는 아직 적용되지 않았습니다. Production Readiness 단계에서 구현 예정입니다.

### 1.3 getVersion 사용법

```java
public class OrderWorkflowImpl implements OrderWorkflow {
    @Override
    public OrderResult processOrder(OrderRequest request) {
        Long orderId = activities.createOrder(request.customerId());

        // 버전 체크: "add-notification" 변경점
        int version = Workflow.getVersion(
            "add-notification",           // 변경점 식별자
            Workflow.DEFAULT_VERSION,     // 최소 지원 버전 (-1)
            1                             // 최대 버전 (현재)
        );

        if (version >= 1) {
            activities.sendNotification(orderId);  // 새 버전만 실행
        }

        activities.reserveStock(request.productId(), request.quantity());
        activities.processPayment(orderId, request.amount());
        return OrderResult.success(orderId);
    }
}
```

**동작 원리:**
- 새 Workflow: MarkerRecorded 이벤트 기록 --> getVersion() = 1 --> 새 코드 실행
- 기존 Workflow Replay: Marker 없음 --> getVersion() = DEFAULT_VERSION (-1) --> 새 코드 스킵 --> History와 일치

### 1.4 단계적 업그레이드 절차

```java
// 단계 1: 새 기능 추가 (기존/신규 공존)
int version = Workflow.getVersion("add-notification", Workflow.DEFAULT_VERSION, 1);
if (version >= 1) {
    activities.sendNotification(orderId);
}

// 단계 2: 모든 기존 Workflow 완료 후, 최소 버전 올리기
int version = Workflow.getVersion("add-notification", 1, 1);
activities.sendNotification(orderId);

// 단계 3: 최종적으로 getVersion 제거 (선택)
activities.sendNotification(orderId);
```

### 1.5 Replay Test로 검증

```java
@Test
public void testVersionCompatibility() {
    WorkflowHistory history = WorkflowHistoryLoader.loadFromResource(
        "workflow-history-v1.json"
    );
    // Non-Determinism 에러 없으면 통과
    WorkflowReplayer.replayWorkflowExecution(history, OrderWorkflowImpl.class);
}
```

---

## 2. Schedule과 Cron Job

> **출처**: [Schedule](https://docs.temporal.io/schedule), [Cron Job](https://docs.temporal.io/cron-job)

### 2.1 Cron Job vs Schedule 비교

| 항목 | Cron Job (기존) | Schedule (권장) |
|------|----------------|-----------------|
| 스케줄 변경 | 불가 (재생성 필요) | 언제든 수정 가능 |
| 일시 정지/재개 | 불가 | 가능 |
| 백필 (과거 보충) | 불가 | 지원 |
| 시간 설정 | Cron 표현식만 | Cron + Interval |
| Overlap 제어 | 제한적 | 6가지 정책 |

**결론: 새 프로젝트는 Schedule 사용 권장**

### 2.2 Cron Job 사용법

```java
WorkflowOptions options = WorkflowOptions.newBuilder()
        .setTaskQueue("batch-task-queue")
        .setWorkflowId("daily-report")
        .setCronSchedule("0 2 * * *")  // 매일 새벽 2시
        .build();
WorkflowClient.start(workflow::generateReport);
```

### 2.3 Schedule 사용법 (권장)

```java
Schedule schedule = Schedule.newBuilder()
        .setAction(ScheduleActionStartWorkflow.newBuilder()
            .setWorkflowType(DailyReportWorkflow.class)
            .setTaskQueue("batch-task-queue")
            .setWorkflowId("daily-report").build())
        .setSpec(ScheduleSpec.newBuilder()
            .setCronExpressions(List.of("0 2 * * *")).build())
        .setPolicy(SchedulePolicy.newBuilder()
            .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP).build())
        .build();

ScheduleHandle handle = scheduleClient.createSchedule(
        "daily-report-schedule", schedule, ScheduleOptions.newBuilder().build());
```

### 2.4 Schedule 관리

```java
ScheduleHandle handle = scheduleClient.getHandle("daily-report-schedule");

handle.pause("점검 중");     // 일시 정지
handle.unpause();             // 재개
handle.trigger(ScheduleTriggerOptions.newBuilder().build());  // 즉시 실행

// 스케줄 수정
handle.update(input -> {
    Schedule current = input.getDescription().getSchedule();
    return Schedule.newBuilder(current)
            .setSpec(ScheduleSpec.newBuilder()
                .setCronExpressions(List.of("0 3 * * *")).build())
            .build();
});

// 백필 (과거 실행 보충)
handle.backfill(ScheduleBackfill.newBuilder()
    .setStartAt(Instant.parse("2026-02-01T00:00:00Z"))
    .setEndAt(Instant.parse("2026-02-05T00:00:00Z"))
    .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_ALLOW_ALL).build());
```

### 2.5 Overlap Policy

| Policy | 설명 | 사용 시점 |
|--------|------|----------|
| **SKIP** | 이전 실행 중이면 건너뜀 | 중복 실행 불가 작업 |
| **BUFFER_ONE** | 하나만 대기열에 저장 | 최신 실행 보장 |
| **BUFFER_ALL** | 모두 대기열에 저장 | 모든 실행 필요 |
| **CANCEL_OTHER** | 이전 실행 취소 후 새 실행 | 최신 실행만 필요 |
| **TERMINATE_OTHER** | 이전 실행 강제 종료 | 긴급 대체 |
| **ALLOW_ALL** | 병렬 실행 허용 | 독립적 작업 |

---

## 3. Namespace와 Task Queue 설계

> **출처**: [Task Queues](https://docs.temporal.io/task-queue)

### 3.1 Namespace 구조

```
+-----------------------------------------------------------------------+
|  Temporal Cluster                                                      |
|  +-------------------------------+                                     |
|  |  Namespace: production        |  - Workflow, Task Queue, Schedule  |
|  +-------------------------------+  - 완전한 격리 (서로 영향 없음)    |
|  |  Namespace: staging           |  - 같은 Workflow ID도 공존 가능    |
|  +-------------------------------+  - 별도 보존 기간/권한 설정        |
|  |  Namespace: development       |                                     |
|  +-------------------------------+                                     |
+-----------------------------------------------------------------------+
```

### 3.2 Task Queue 설계 패턴

| 패턴 | 큐 예시 | 장점 |
|------|---------|------|
| **도메인별 분리** | order-queue, payment-queue | 독립 스케일링, 장애 격리 |
| **우선순위별 분리** | high-priority, normal, batch | 중요 작업 우선 처리 |
| **리소스별 분리** | cpu-intensive, io-intensive, api | Worker 리소스 최적화 |

### 3.3 Task Queue 파티셔닝

```
+-----------------------------------------------------------------------+
|  order-task-queue (기본 4 파티션)                                      |
|  +-----------+-----------+-----------+-----------+                     |
|  |Partition 0|Partition 1|Partition 2|Partition 3|                     |
|  +-----+-----+-----+-----+-----+-----+-----+-----+                   |
|        v           v           v           v                           |
|    Worker 1    Worker 2    Worker 3    Worker 4                        |
+-----------------------------------------------------------------------+

고부하 시 dynamic config로 파티션 수 증가:
  matching.numTaskqueueReadPartitions: 16
  matching.numTaskqueueWritePartitions: 16

주의: 파티션 수 ~= 최대 Worker 수
```

---

## 4. 프로덕션 배포 아키텍처

> **출처**: [Production Deployment](https://docs.temporal.io/production-deployment)

### 4.1 자체 호스팅 아키텍처

```
+-----------------------------------------------------------------------+
|  Load Balancer (L7)                                                    |
|       |                                                                 |
|       v                                                                 |
|  +------------------------------------------------------------------+  |
|  |  Frontend Service (Stateless, 3+ Pods, 수평 확장)                |  |
|  +------------------------------------------------------------------+  |
|       |                  |                  |                           |
|       v                  v                  v                           |
|  +----------+       +----------+       +----------+                    |
|  | History  |       | Matching |       |  Worker  |                    |
|  | Service  |       | Service  |       | Service  |                    |
|  | (Sharded)|       |(Partitioned)     |          |                    |
|  | 3+ Pods  |       | 3+ Pods  |       | 2+ Pods  |                   |
|  +----+-----+       +----------+       +----------+                    |
|       |                                                                 |
|       v                                                                 |
|  +------------------------------------------------------------------+  |
|  |  PostgreSQL/MySQL (Primary+Replica) + Elasticsearch (선택)       |  |
|  +------------------------------------------------------------------+  |
|                                                                         |
|  권장: History Shards 512(소규모)/4096(대규모), DB 8vCPU 32GB SSD    |
+-----------------------------------------------------------------------+
```

### 4.2 Worker 배포 전략 (Kubernetes)

```
+-----------------------------------------------------------------------+
|  Deployment: order-worker (replicas: 3)                                |
|  +---------+  +---------+  +---------+                                 |
|  | Pod(AZ-a)|  | Pod(AZ-b)|  | Pod(AZ-c)|  <-- 가용 영역 분산          |
|  +---------+  +---------+  +---------+                                 |
|  Task Queue: order-task-queue                                          |
|                                                                         |
|  Deployment: payment-worker (replicas: 5, 더 많은 부하)                |
|  Task Queue: payment-task-queue                                        |
+-----------------------------------------------------------------------+

스케일링 기준:
  - Task Queue 대기열 크기
  - Activity 처리 시간
  - Worker CPU/메모리 사용률
```

### 4.3 고가용성 (HA) 체크리스트

| 영역 | 항목 |
|------|------|
| **Temporal Server** | History Shard 수 결정 (변경 불가!), 각 서비스 최소 3 인스턴스, AZ 분산 |
| **데이터베이스** | HA 구성, 자동 백업, 연결 풀 설정, 쿼리 성능 모니터링 |
| **Worker** | 최소 2 인스턴스, Graceful Shutdown, Health Check, 리소스 제한 |
| **모니터링** | Prometheus 메트릭, Grafana 대시보드, 알림 (Slack/PagerDuty), 로그 수집 |
| **보안** | TLS (gRPC), 인증/인가, Namespace 권한 분리, 민감 데이터 암호화 |

---

## 5. Temporal Cloud vs Self-Hosted

### 5.1 비교표

| 항목 | Temporal Cloud | Self-Hosted |
|------|---------------|-------------|
| **운영 부담** | 없음 (관리형) | 높음 (직접 운영) |
| **비용** | 사용량 기반 과금 | 인프라 비용 |
| **확장성** | 자동 (무제한) | 직접 설정 |
| **가용성** | SLA 99.99% | 직접 구성 |
| **보안** | SOC2, HIPAA 등 | 직접 구성 |
| **커스터마이징** | 제한적 | 자유로움 |
| **학습/개발** | 무료 티어 제공 | Docker로 간단히 시작 |

### 5.2 선택 가이드

| Temporal Cloud 선택 | Self-Hosted 선택 |
|---------------------|------------------|
| 운영 인력이 부족할 때 | 학습/개발 목적 |
| 빠르게 프로덕션 배포 필요 | 데이터 주권 (금융, 의료) |
| SLA가 중요할 때 | 기존 인프라 통합 |
| 글로벌 서비스 (Multi-Region) | 비용 최적화 (대규모) |

> 현재 프로젝트 (학습 목적) --> Self-Hosted (Docker Compose)로 충분!

---

## 6. 모니터링과 운영

### 6.1 Temporal UI 활용

```
+-----------------------------------------------------------------------+
|  URL: http://localhost:21088                                           |
+-----------------------------------------------------------------------+
|  1. Workflow 목록  : 상태별 필터, 검색 (Workflow ID, Type, 날짜)       |
|  2. Workflow 상세  : Event History, Timeline, 실패 원인 분석           |
|  3. 액션          : Signal 전송, Query 실행, 취소/종료, Reset          |
|  4. 시스템 모니터링 : Task Queue 상태, Worker 연결, Namespace 설정     |
+-----------------------------------------------------------------------+
```

### 6.2 주요 메트릭

| 카테고리 | 메트릭 | 설명 |
|----------|--------|------|
| **Workflow** | workflow_started_total | 시작된 Workflow 수 |
| | workflow_failed_total | 실패한 Workflow 수 |
| | workflow_execution_latency | 실행 시간 분포 |
| **Activity** | activity_execution_latency | Activity 실행 시간 |
| | activity_execution_failed | Activity 실패 수 |
| | activity_task_timeout | 타임아웃 수 |
| **Task Queue** | task_latency | Task 대기 시간 |
| **Worker** | worker_task_slots_available | 사용 가능한 슬롯 |

**알림 설정 예시:**
- `workflow_failed_total` 급증 --> 즉시 알림
- `task_latency > 10s` --> 경고 알림
- `worker_task_slots_available = 0` --> 스케일 업 알림

### 6.3 Graceful Shutdown

Worker 종료 시 진행 중인 Task를 안전하게 마무리해야 한다.

```java
@Component
public class TemporalWorkerLifecycle implements DisposableBean {
    private final WorkerFactory workerFactory;

    public TemporalWorkerLifecycle(WorkerFactory workerFactory) {
        this.workerFactory = workerFactory;
    }

    @Override
    public void destroy() throws Exception {
        workerFactory.shutdown();                                // 새 Task 폴링 중지
        workerFactory.awaitTermination(30, TimeUnit.SECONDS);   // 완료 대기 (최대 30초)
        workerFactory.shutdownNow();                             // 강제 종료
    }
}
```

### 6.4 디버깅 팁

| 상황 | 방법 |
|------|------|
| 실패 원인 분석 | Temporal UI에서 Event History 클릭, 에러 메시지 확인 |
| 실행 중 상태 확인 | Query 메서드로 현재 단계 조회 |
| Replay 문제 | Workflow 로그는 Replay 시에도 실행됨 -> `Workflow.isReplaying()` 가드 권장 |
| 타이머 테스트 | TestWorkflowEnvironment에서 시간 빠르게 진행 |

> **[프로젝트 참고]**: `Workflow.isReplaying()` 가드는 실제 OrderWorkflowImpl 코드에도 적용할 예정입니다. 상세 예시는 `14-faq-troubleshooting.md` 5.6절 참조.

---

## 7. Workflow 수정 시 주의사항

### 7.1 케이스별 정리

| 상황 | 재배포? | Versioning? | 비고 |
|------|---------|-------------|------|
| 새 Workflow 추가 | Worker 재배포 | 불필요 | Temporal Server 변경 불필요 |
| 새 Activity 추가 | Worker 재배포 | 불필요 | |
| 기존 Workflow 수정 | Worker 재배포 | **실행 중 Workflow 있으면 필수** | 누락 시 Non-Deterministic Error |
| Workflow 시작/실행 | 불필요 | - | |
| Signal/Query 전송 | 불필요 | - | |

### 7.2 새 Workflow 추가 절차

```java
// Step 1: 코드 작성
@WorkflowInterface
public interface PaymentWorkflow { ... }
public class PaymentWorkflowImpl implements PaymentWorkflow { ... }

// Step 2: Worker에 등록
worker.registerWorkflowImplementationTypes(
    OrderWorkflowImpl.class,
    PaymentWorkflowImpl.class   // <-- 추가!
);

// Step 3: Worker 재배포 (Temporal Server 변경 불필요)
```

### 7.3 기존 Workflow 수정 (위험!)

실행 중인 Workflow가 있을 때 코드를 직접 변경하면 Event History와 불일치가 발생한다.
반드시 `Workflow.getVersion()`으로 분기 처리해야 한다.

```java
public OrderResult processOrder(OrderRequest request) {
    Long orderId = activities.createOrder(request.customerId());

    int version = Workflow.getVersion(
        "add-stock-validation",
        Workflow.DEFAULT_VERSION,  // 최소 버전 (-1, 기존 코드)
        1                          // 최대 버전 (1, 새 코드)
    );

    if (version >= 1) {
        activities.validateStock(request.productId());  // 새 Workflow만 실행
    }
    // 기존 Workflow는 이 블록을 스킵

    activities.reserveStock(request.productId(), request.quantity());
    activities.processPayment(orderId);
    return OrderResult.success(orderId);
}
```

> **[프로젝트 참고]**: `Workflow.getVersion()`은 위 1절에서 설명한 것과 동일한 메커니즘입니다. 현재 프로젝트 코드에는 미적용 상태이며, Production Readiness 단계에서 적용 예정입니다.

### 7.4 안전하지 않은 수정 vs 안전한 수정

| 수정 유형 | Versioning 필요? | 이유 |
|-----------|-----------------|------|
| Activity 내부 로직 변경 (시그니처 동일) | 불필요 | Activity는 결과만 History에 기록 |
| Activity 추가/순서 변경 | **필수** | Replay 시 실행 경로가 달라짐 |
| Workflow 내 조건문/분기 변경 | **필수** | 결정적 실행 경로 변경 |
| Activity 재시도 옵션 변경 | 불필요 | 옵션은 새 실행에만 적용 |

---

## 참고 자료

### 공식 문서
- [Versioning](https://docs.temporal.io/develop/java/versioning) | [Safe Deployments](https://docs.temporal.io/develop/safe-deployments)
- [Schedule](https://docs.temporal.io/schedule) | [Task Queues](https://docs.temporal.io/task-queue)
- [Production Deployment](https://docs.temporal.io/production-deployment) | [High Availability](https://docs.temporal.io/evaluate/development-production-features/high-availability)

### 블로그
- [Schedules vs Cron Jobs](https://temporal.io/blog/temporal-schedules-reliable-scalable-and-more-flexible-than-cron-jobs)
- [Scaling Temporal](https://dev.to/temporalio/scaling-temporal-the-basics-31l5)

---

*이전 문서: `12-limitations-combo.md`*
*다음 학습: `14-faq-troubleshooting.md`*
