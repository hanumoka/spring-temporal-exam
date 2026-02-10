# FAQ와 트러블슈팅

> **작성일**: 2026-02-10
> **대상**: Temporal 학습 중 자주 묻는 질문과 트러블슈팅 가이드
> **전제**: Phase 3 학습 문서 전체 학습 완료 또는 진행 중

---

## 목차

1. [자주 묻는 질문 (Quick Reference)](#1-자주-묻는-질문-quick-reference)
2. [gRPC와 Long Polling 동작 원리](#2-grpc와-long-polling-동작-원리)
3. [성능과 한계](#3-성능과-한계)
4. [자주 하는 실수들](#4-자주-하는-실수들)
5. [트러블슈팅 가이드](#5-트러블슈팅-가이드)
6. [Phase 3 학습 완료 정리](#6-phase-3-학습-완료-정리)

---

## 1. 자주 묻는 질문 (Quick Reference)

아래 질문들은 각 전문 문서에서 상세히 다룹니다. 빠른 답변과 참조 문서를 정리합니다.

| 질문 | 핵심 답변 | 상세 문서 |
|------|-----------|-----------|
| Workflow를 Spring이 관리하나? | Temporal이 생성/관리, Spring DI 불가 | 08-spring-integration.md |
| Activity를 Spring이 관리하나? | Spring Bean으로 관리, DI 가능 | 08-spring-integration.md |
| Workflow 코드가 DB에 저장되나? | Event History만 저장, 코드는 Worker에 있음 | 08-spring-integration.md |
| Workflow 수정하면? | Worker 재배포 + Versioning 필수 | 13-production.md |
| Signal/Query는 뭔가? | Signal=비동기 상태 변경, Query=동기 조회 | 06-signal-query-update.md |
| Child Workflow는 언제? | 복잡한 로직 분리, Event History 크기 관리 | 07-advanced-topics.md |
| Cloud vs Self-Hosted? | 학습=Self-Hosted, 프로덕션=상황에 따라 | 13-production.md |
| 모니터링/디버깅? | Temporal UI + Event History 분석 | 13-production.md |
| 인프라/Worker 흐름? | Docker Compose, Long Polling으로 Task 수신 | 04-worker-event-flow.md |

---

## 2. gRPC와 Long Polling 동작 원리

> -> 상세 Worker 동작 흐름: 04-worker-event-flow.md 참조

### 왜 gRPC인가?

```
┌───────────────────────────────────────────────────────┐
│                  gRPC 통신 특징                         │
├───────────────────────────────────────────────────────┤
│                                                        │
│  Temporal은 HTTP/2 기반 gRPC를 사용:                   │
│                                                        │
│  1. Binary Protocol (Protobuf)                         │
│     -> JSON보다 빠른 직렬화, 네트워크 트래픽 감소      │
│                                                        │
│  2. HTTP/2 Multiplexing                                │
│     -> 단일 TCP 연결로 다중 요청, 연결 오버헤드 감소   │
│                                                        │
│  3. Streaming 지원                                     │
│     -> Long Polling + 양방향 통신에 적합               │
│                                                        │
│  Spring Boot                    Temporal Server        │
│  ┌──────────┐                   ┌──────────┐          │
│  │ gRPC     │=== HTTP/2 (TLS) =>│  :7233   │          │
│  │ Channel  │<==================│          │          │
│  └──────────┘  Bi-directional   └──────────┘          │
│                                                        │
└───────────────────────────────────────────────────────┘
```

### Long Polling 시퀀스

```
┌───────────────────────────────────────────────────────┐
│               Long Polling 동작 원리                    │
├───────────────────────────────────────────────────────┤
│                                                        │
│  Worker                         Temporal Server        │
│  ┌────────┐                     ┌──────────────┐      │
│  │        │  PollTaskQueue      │              │      │
│  │        │  "작업 있으면 줘"   │              │      │
│  │        ├────────────────────>│              │      │
│  │        │                     │              │      │
│  │        │   (대기... 최대 60초)               │      │
│  │        │                     │              │      │
│  │        │  Task 응답          │              │      │
│  │        │<────────────────────┤              │      │
│  │        │                     │              │      │
│  │  실행  │  TaskCompleted      │              │      │
│  │        ├────────────────────>│              │      │
│  └────────┘                     └──────────────┘      │
│                                                        │
│  핵심: 연결을 열어두고 대기 (Push 효과)                │
│  - 타임아웃 시 자동 재연결 (기본 60초)                 │
│  - 지속적 폴링보다 서버 부하 낮음                      │
│  - 작업 도착 즉시 전달 (실시간성 높음)                 │
│                                                        │
└───────────────────────────────────────────────────────┘
```

### Spring Boot 시작 시 연결 순서

```
[1] WorkflowServiceStubs 생성 -> Temporal Server gRPC 연결
[2] WorkflowClient 생성 -> Namespace 설정
[3] Activity Bean 생성 -> Spring DI (RestClient, Repository 등)
[4] Worker Bean 생성 -> Workflow/Activity 등록
[5] factory.start() -> 별도 스레드에서 Long Polling 시작
[6] 애플리케이션 Ready -> Worker가 백그라운드에서 Task 대기
```

---

## 3. 성능과 한계

### 핵심 수치

```
┌───────────────────────────────────────────────────────┐
│                  성능 수치 (참고용)                      │
├───────────────────────────────────────────────────────┤
│                                                        │
│  Event History 크기 제한:                              │
│  - 기본 상한: 50,000 이벤트                            │
│  - 경고: ~10,000 이벤트부터                            │
│  - 초과 시 Workflow 강제 종료!                         │
│                                                        │
│  Workflow 실행 시간: 제한 없음                         │
│  (며칠~몇 년 가능, 단 History 크기 관리 필수)          │
│                                                        │
│  처리량: 초당 수만 Workflow 시작 가능                   │
│  지연: Workflow 시작/Activity 스케줄 < 50ms            │
│                                                        │
└───────────────────────────────────────────────────────┘
```

### Event History 크기 관리

| Workflow 패턴 | 이벤트 수 | 조치 |
|--------------|----------|------|
| Activity 3개 호출 | ~15 | 안전 |
| Activity 10개 + Signal 5개 | ~60 | 안전 |
| 루프 내 Activity 100회 | ~500 | 모니터링 필요 |
| 루프 내 Activity 10,000회 | ~50,000 | ContinueAsNew 필수! |

**해결책 1: ContinueAsNew** - History 초기화 후 새 실행으로 이어서 계속

```java
public class LongRunningWorkflowImpl implements LongRunningWorkflow {
    private int eventCount = 0;

    @Override
    public void run() {
        while (true) {
            activities.doSomething();
            eventCount++;
            if (eventCount > 10000) {
                Workflow.continueAsNew();  // 새 Run ID로 재시작, History 초기화
            }
        }
    }
}
```

**해결책 2: Child Workflow로 분리** - 각 아이템을 별도 History로 관리

```java
public class ParentWorkflowImpl implements ParentWorkflow {
    @Override
    public void process(List<Item> items) {
        for (Item item : items) {
            childWorkflow.processItem(item);  // 별도 History
        }
    }
}
```

> -> 상세: 07-advanced-topics.md 참조

---

## 4. 자주 하는 실수들

```
┌───────────────────────────────────────────────────────┐
│                 자주 하는 실수 TOP 8                     │
├───────────────────────────────────────────────────────┤
│                                                        │
│  1. Workflow에서 Spring DI 사용                        │
│     (X) @Autowired private SomeService svc;            │
│     (O) Activity를 통해 서비스 호출                    │
│                                                        │
│  2. Workflow에서 비결정적 API 사용                     │
│     (X) Math.random() / LocalDateTime.now()            │
│     (O) Workflow.newRandom() / currentTimeMillis()     │
│                                                        │
│  3. Workflow에서 Thread.sleep() 사용                   │
│     (X) Thread.sleep(5000);                            │
│     (O) Workflow.sleep(Duration.ofSeconds(5));          │
│                                                        │
│  4. Activity를 타입(.class)으로 등록                   │
│     (X) registerActivitiesImplementations(Act.class);  │
│     (O) registerActivitiesImplementations(actBean);    │
│                                                        │
│  5. Workflow 수정 시 Versioning 누락                   │
│     (X) 실행 중 Workflow 있는데 코드만 변경            │
│     (O) Workflow.getVersion()으로 분기 처리            │
│                                                        │
│  6. Query에서 상태 변경                                │
│     (X) @QueryMethod에서 this.count++                  │
│     (O) Query는 읽기만! 상태 변경은 Signal로           │
│                                                        │
│  7. Workflow에서 직접 I/O 수행                         │
│     (X) restTemplate.getForObject(...) in Workflow     │
│     (O) activities.callExternalApi(...)                 │
│                                                        │
│  8. Task Queue 이름 불일치                             │
│     (X) Worker="order-queue", Client="orders-queue"    │
│     (O) 상수로 관리하여 일치시킴                       │
│                                                        │
└───────────────────────────────────────────────────────┘
```

### 비결정적 API 빠른 참조

| 사용 불가 | 대체 API |
|-----------|---------|
| `Math.random()` | `Workflow.newRandom()` |
| `LocalDateTime.now()` | `Workflow.currentTimeMillis()` |
| `UUID.randomUUID()` | `Workflow.randomUUID()` |
| `Thread.sleep()` | `Workflow.sleep()` |
| `HttpClient` / I/O | Activity에서 호출 |
| `@Autowired` | Activity에서 DI |

---

## 5. 트러블슈팅 가이드

### 5.1 Workflow가 시작되지 않음

| 확인 사항 | 해결 방법 |
|-----------|----------|
| Worker 시작? | `factory.start()` 호출 + 로그에 "Started Worker" 확인 |
| Task Queue 일치? | Client와 Worker의 Queue 이름 비교 |
| Server 연결? | `docker ps` + gRPC 포트(7233) 확인 |
| Workflow 등록? | `registerWorkflowImplementationTypes()` 확인 |

### 5.2 Non-Deterministic Error

```
증상: io.temporal.internal.replay.InternalWorkflowTaskException
```

| 원인 | 해결 방법 |
|------|----------|
| `Math.random()` 등 비결정적 API | `Workflow.*` API로 교체 |
| Workflow 코드 변경 (Versioning 누락) | `Workflow.getVersion()`으로 분기 |
| Activity 호출 순서 변경 | 기존 Workflow 완료 대기 또는 Versioning |

```java
// Versioning 예시
int version = Workflow.getVersion(
    "add-validation-step",     // 변경 ID
    Workflow.DEFAULT_VERSION,  // 기존 코드 (-1)
    1                          // 새 코드 (1)
);
if (version >= 1) {
    activities.validateInput(request);  // 새 코드에서만 실행
}
```

> -> 상세: 13-production.md 참조 (Versioning 전략)

### 5.3 Activity가 실행되지 않음

| 원인 | 해결 방법 |
|------|----------|
| Activity 미등록 | `registerActivitiesImplementations(bean)` 확인 |
| 다른 Task Queue | `ActivityOptions.setTaskQueue()` 값 확인 |
| Timeout 부족 | `StartToCloseTimeout` 값 늘리기 |
| Bean 생성 실패 | Spring 로그에서 에러 확인 |

### 5.4 연결 타임아웃

```
증상: io.grpc.StatusRuntimeException: UNAVAILABLE
```

| 원인 | 해결 방법 |
|------|----------|
| Server 미실행 | `docker compose up -d` |
| 포트 오류 | `application.yml`의 `temporal.server.url` 확인 |
| Docker 네트워크 | `host.docker.internal` 또는 서비스명 사용 |

### 5.5 Workflow 상태가 FAILED

| 원인 | 해결 방법 |
|------|----------|
| 재시도 소진 | `MaximumAttempts` 확인, 에러 로그 분석 |
| 예외 미처리 | Saga 패턴으로 보상 트랜잭션 구현 |
| 비즈니스 예외 재시도 | `setDoNotRetry()`로 비즈니스 예외 제외 |

```java
RetryOptions.newBuilder()
    .setMaximumAttempts(3)
    .setDoNotRetry(
        IllegalArgumentException.class.getName(),   // 잘못된 입력
        InsufficientStockException.class.getName()  // 재고 부족
    ).build();
```

### 5.6 Replay 시 로그 중복 출력

```java
// 해결: Replay 여부 확인 후 로깅
if (!Workflow.isReplaying()) {
    log.info("주문 처리 시작: {}", orderId);
}
```

### 트러블슈팅 체크리스트

```
┌───────────────────────────────────────────────────────┐
│                트러블슈팅 체크리스트                     │
├───────────────────────────────────────────────────────┤
│                                                        │
│  [ ] 1. Temporal Server 실행 중? (docker ps)           │
│  [ ] 2. gRPC 포트 접근 가능? (localhost:7233)          │
│  [ ] 3. Worker 시작 로그 확인?                         │
│  [ ] 4. Task Queue 이름 일치?                          │
│  [ ] 5. Workflow/Activity 등록 완료?                   │
│  [ ] 6. Temporal UI에서 상태 확인?                     │
│  [ ] 7. Event History에서 실패 지점 확인?              │
│  [ ] 8. 비결정적 API 사용 여부?                        │
│  [ ] 9. Versioning 누락 여부?                          │
│                                                        │
└───────────────────────────────────────────────────────┘
```

---

## 6. Phase 3 학습 완료 정리

### 전체 문서 맵

```
┌───────────────────────────────────────────────────────┐
│            Phase 3: Temporal 학습 문서 맵                │
├───────────────────────────────────────────────────────┤
│                                                        │
│  기초                                                  │
│  00-temporal-deep-dive.md       핵심 원리              │
│  01-temporal-concepts.md        기본 개념              │
│  01-temporal-advanced-concepts.md  심화 개념           │
│                                                        │
│  아키텍처                                              │
│  02-temporal-spring.md          Spring Boot 연동       │
│  02-temporal-production.md      프로덕션 운영          │
│  03-temporal-worker-event-flow.md  Worker 이벤트 흐름 │
│  03-temporal-limitations.md     제약 사항과 한계       │
│  04-temporal-msa-architecture-flow.md  MSA 흐름       │
│                                                        │
│  실전                                                  │
│  05-temporal-faq.md             FAQ (원본 참고)        │
│  06-temporal-activity-design-guide.md  Activity 설계  │
│  14-faq-troubleshooting.md      FAQ + 트러블슈팅      │
│                                                        │
└───────────────────────────────────────────────────────┘
```

### Temporal 핵심 10가지

```
┌───────────────────────────────────────────────────────┐
│            Temporal 학습 핵심 10가지                     │
├───────────────────────────────────────────────────────┤
│                                                        │
│  1. Workflow = 오케스트레이션 (결정적 실행 필수)        │
│  2. Activity = 실제 작업 수행 (Spring Bean, DI 가능)   │
│  3. Worker = Long Polling으로 Task 수신/실행           │
│  4. Event History = 실행 기록 (50,000 이벤트 제한)     │
│  5. 결정적 실행 = Replay 시 같은 결과 보장             │
│  6. Signal = 외부 -> Workflow 데이터 전달 (비동기)     │
│  7. Query = 외부 -> Workflow 상태 조회 (동기)          │
│  8. Saga = 보상 트랜잭션으로 분산 트랜잭션 관리        │
│  9. Versioning = 안전한 Workflow 코드 변경             │
│  10. ContinueAsNew = Event History 초기화              │
│                                                        │
└───────────────────────────────────────────────────────┘
```

### 순수 Saga 구현 vs Temporal (학습 결론)

| 관심사 | 순수 구현 | Temporal |
|--------|----------|----------|
| 재시도 | 직접 구현 (Resilience4j) | 자동 (선언적) |
| 보상 트랜잭션 | 수동 추적 + 실행 | Saga 클래스 제공 |
| 상태 관리 | DB 기반 직접 관리 | Event Sourcing |
| 장애 복구 | 수동 처리 | 자동 Replay |
| 모니터링 | 직접 구현 | Temporal UI |
| 코드 복잡도 | 높음 (인프라 코드 多) | 낮음 (비즈니스만) |
| 학습 곡선 | 낮음 | 중간~높음 |
| 인프라 의존성 | 없음 | Temporal Server |

**결론**: 단순한 서비스는 순수 구현도 충분하지만, 복잡한 분산 트랜잭션이나 장기 실행 프로세스에서는 Temporal이 압도적 이점을 가집니다.

---

> **Phase 3 학습 완료!**
>
> Temporal의 핵심 개념부터 실전 트러블슈팅까지 학습을 마쳤습니다.
> 다음 단계에서는 실제 코드를 작성하며 Temporal을 적용해 보세요.
> Phase 2-A의 순수 Saga 구현과 비교하면서 Temporal의 가치를 체감할 수 있습니다.