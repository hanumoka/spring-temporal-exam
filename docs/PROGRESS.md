# 프로젝트 진행 현황

## 현재 상태

- **현재 Phase**: Phase 2-A - 동기 REST 기반 Saga
- **마지막 업데이트**: 2026-02-02
- **Spring Boot**: 3.5.9
- **목표 완료일**: 2026-02-08 (토) - 7일 확장

---

## 학습 여정 전체 구조

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           학습 여정 전체 구조                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   "왜 이렇게 복잡해?" ──────────────→ "아, 이래서 Temporal을 쓰는구나!"       │
│                                                                             │
│   Phase 1        Phase 2-A        Phase 2-B        Phase 3                 │
│   ─────────      ──────────       ──────────       ─────────               │
│   기반 구축   →   문제 직면    →   심화 문제   →   해결책 체감              │
│   (완료)         (진행 중)        (대기)          (대기)                    │
│                                                                             │
│   + DevOps: CI/CD + 성능 테스트 (Phase 2-B와 병행)                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 집중 일정 (7일 완료 목표)

> 기간: 2026-02-02 (일) ~ 2026-02-08 (토)
> 범위: 전체 항목 (35개, 보강 8개 포함)

### 완료된 사전 작업

| Phase | 항목 | 상태 |
|-------|------|------|
| Phase 1 | 멀티모듈, Flyway, Profiles, Docker Compose | ✅ 완료 |
| Phase 2-A | 문제 인식 문서 (00-problem-recognition) | ✅ 완료 |
| Phase 2-A | Saga 패턴 이해 + 오케스트레이터 구현 | ✅ 완료 |
| Phase 3 | Temporal 한계 문서 (03-temporal-limitations) | ✅ 완료 |

---

### Day 1 - 2/2 (일) : Phase 2-A 핵심

> 목표: 분산 환경 핵심 패턴 (멱등성, 재시도, 동시성 제어)

| 시간 | 항목 | 학습 문서 | 상태 |
|------|------|----------|------|
| 오전 | Fake PG 구현체 작성 | [D015](./architecture/DECISIONS.md#d015), [D026](./architecture/DECISIONS.md#d026) | ✅ 완료 |
| 오전 | 멱등성 처리 (Idempotency Key) | 02-idempotency | ✅ 완료 |
| 오후 | Resilience4j (재시도/타임아웃/서킷브레이커) | 03-resilience4j | ⬜ |
| 저녁 | 분산 락 (RLock) + 세마포어 (RSemaphore) | 04-distributed-lock | ⬜ |

**핵심 학습 포인트**:
- 멱등성이 재시도의 전제조건임을 이해
- 분산 락 vs 세마포어 사용 시점 구분

**⚠️ Fake PG 구현 시 주의사항** ([D026 참조](./architecture/DECISIONS.md#d026)):
```
현재 Saga에서 결제 승인(T3-2) = 실제 돈 인출 시점

문제: T3-2 이후 실패 시 환불 처리 필요 (시간 소요, 수수료 발생 가능)
권장: 2단계 결제 패턴 (Authorization → Capture)
     - authorize(): 카드 홀딩 (돈 안 빠짐)
     - capture(): 실제 청구 (Saga 완료 후)
     - void(): 홀딩 취소 (중간 실패 시, 즉시, 무료)

Fake PG 구현 시 두 패턴 모두 테스트 가능하도록 설계
```

---

### Day 2 - 2/3 (월) : Phase 2-A 심화

> 목표: 동시성 심화 + Saga Isolation + 분산 락 함정

| 시간 | 항목 | 학습 문서 | 상태 |
|------|------|----------|------|
| 오전 | 대기열 + 세마포어 조합 (버퍼링 패턴) | 04-1-queue-semaphore | ⬜ |
| 오전 | 낙관적 락 (JPA @Version) | 05-optimistic-lock | ⬜ |
| 오후 | **Saga Isolation 문제** (Dirty Read, Lost Update) | 11-saga-isolation | ⬜ |
| 저녁 | **Redis 분산 락 10가지 함정** | 12-redis-lock-pitfalls | ⬜ |

**핵심 학습 포인트**:
- 대기열+세마포어가 Temporal Task Queue 원리와 동일
- Saga 동시 실행 시 데이터 불일치 문제와 해결책
- Redis 분산 락 프로덕션 체크리스트

---

### Day 3 - 2/4 (화) : Phase 2-A 완료 + 테스트

> 목표: 애플리케이션 안정성 + Contract Testing

| 시간 | 항목 | 학습 문서 | 상태 |
|------|------|----------|------|
| 오전 | Bean Validation 입력 검증 | 06-bean-validation | ⬜ |
| 오전 | 글로벌 예외 처리 | 07-exception-handling | ⬜ |
| 오후 | MDC 로깅 (분산 추적 준비) | 08-mdc-logging | ⬜ |
| 오후 | TransactionTemplate (프로그래밍 방식) | 09-transaction-template | ⬜ |
| 저녁 | **Contract Testing** (Pact) | 10-contract-testing | ⬜ |

**핵심 학습 포인트**:
- 서비스 간 API 계약 검증으로 독립 배포 가능
- 테스트 다이아몬드 (Integration + Contract 중심)

---

### Day 4 - 2/5 (수) : Phase 2-B 전반

> 목표: Redis 심화 + 이벤트 신뢰성

| 시간 | 항목 | 학습 문서 | 상태 |
|------|------|----------|------|
| 오전 | Redis 기초 (자료구조, 명령어) | 01-redis-basics | ⬜ |
| 오전 | Redis Stream (Consumer Group) | 02-redis-stream | ⬜ |
| 오후 | Redisson 심화 (Pending List, Phantom Key) | 03-redisson | ⬜ |
| 저녁 | Outbox 패턴 (이벤트 발행 신뢰성) | 04-outbox-pattern | ⬜ |
| 저녁 | Notification 서비스 구현 | - | ⬜ |

**핵심 학습 포인트**:
- Redis Stream이 Kafka 대안으로 어떻게 동작하는지
- Outbox 패턴이 분산 트랜잭션을 어떻게 보완하는지

---

### Day 5 - 2/6 (목) : Phase 2-B 완료 (Observability)

> 목표: 분산 시스템 가시성 확보

| 시간 | 항목 | 학습 문서 | 상태 |
|------|------|----------|------|
| 오전 | OpenTelemetry + **Grafana Tempo** (분산 추적) | 05-opentelemetry-tempo | ⬜ |
| 오후 | Prometheus + Grafana (메트릭 시각화) | 06-prometheus-grafana | ⬜ |
| 저녁 | Loki (로그 수집) | 07-loki | ⬜ |
| 저녁 | Alertmanager (장애 알림) | 08-alertmanager | ⬜ |

**핵심 학습 포인트**:
- 분산 환경에서 traceId로 요청 추적하는 방법
- Grafana 스택 (Tempo + Prometheus + Loki) 통합

---

### Day 6 - 2/7 (금) : Phase 3 + DevOps

> 목표: Temporal 연동 + CI/CD 파이프라인

| 시간 | 항목 | 학습 문서 | 상태 |
|------|------|----------|------|
| 오전 | Temporal 핵심 개념 (Workflow, Activity, Worker) | 01-temporal-concepts | ⬜ |
| 오전 | Temporal 로컬 인프라 구성 | 01-temporal-concepts | ⬜ |
| 오후 | Temporal + Spring Boot 연동 | 02-temporal-spring | ⬜ |
| 오후 | Workflow/Activity 정의 | 02-temporal-spring | ⬜ |
| 저녁 | **GitHub Actions CI/CD** | devops/01-github-actions | ⬜ |

**핵심 학습 포인트**:
- temporal-spring-boot-starter 1.32.0 자동 등록 기능
- 마이크로서비스별 독립 파이프라인

---

### Day 7 - 2/8 (토) : Phase 3 완료 + 성능 테스트

> 목표: Saga → Temporal 전환 + 부하 테스트

| 시간 | 항목 | 학습 문서 | 상태 |
|------|------|----------|------|
| 오전 | 기존 Saga → Temporal 전환 | 02-temporal-spring | ⬜ |
| 오후 | Temporal 한계 실습 (분산 락 + 멱등성 조합) | 03-temporal-limitations | ⬜ |
| 저녁 | **k6 성능 테스트** | phase2b/09-performance-testing | ⬜ |
| 저녁 | Virtual Threads 활성화 | - | ⬜ |

**핵심 학습 포인트**:
- Phase 2에서 직접 구현한 것들이 Temporal에서 어떻게 자동화되는지
- Temporal이 해결 못하는 6가지를 Phase 2 기술로 보완

---

## 일정 요약

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         7일 학습 일정 요약                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Day 1 (2/2 일): Phase 2-A 핵심                                             │
│  ├── 멱등성, Resilience4j, 분산 락                                          │
│  └── "재시도의 전제조건" 이해                                                │
│                                                                             │
│  Day 2 (2/3 월): Phase 2-A 심화 ★ 보강                                      │
│  ├── 대기열+세마포어, 낙관적 락                                              │
│  ├── Saga Isolation (Dirty Read, Lost Update)                              │
│  └── Redis 분산 락 10가지 함정                                              │
│                                                                             │
│  Day 3 (2/4 화): Phase 2-A 완료 + 테스트 ★ 보강                             │
│  ├── Validation, 예외 처리, MDC, TransactionTemplate                        │
│  └── Contract Testing (Pact)                                               │
│                                                                             │
│  Day 4 (2/5 수): Phase 2-B 전반                                             │
│  ├── Redis 기초, Stream, Redisson                                          │
│  └── Outbox 패턴, Notification 서비스                                       │
│                                                                             │
│  Day 5 (2/6 목): Phase 2-B 후반 (Observability) ★ 변경                      │
│  ├── OpenTelemetry + Grafana Tempo (Zipkin 대체)                            │
│  └── Prometheus/Grafana, Loki, Alertmanager                                │
│                                                                             │
│  Day 6 (2/7 금): Phase 3 + DevOps ★ 보강                                    │
│  ├── Temporal 개념 + 인프라 + Spring 연동                                   │
│  └── GitHub Actions CI/CD                                                  │
│                                                                             │
│  Day 7 (2/8 토): Phase 3 완료 + 성능 테스트 ★ 보강                          │
│  ├── Saga → Temporal 전환, 한계 실습                                        │
│  └── k6 성능 테스트, Virtual Threads                                        │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 학습 경로 가이드: 필수 vs 선택

> 시간 제약이 있을 때 우선순위를 명확히 하기 위한 분류입니다.

### 핵심 경로 (필수 - 22개 항목)

Temporal의 가치를 체감하기 위해 반드시 거쳐야 하는 학습 경로입니다.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           핵심 학습 경로                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Phase 1 (전체 필수)                                                        │
│  └── 01-gradle ~ 04-docker-compose (4개)                                    │
│                                                                             │
│  Phase 2-A (9개 필수) ★ 3개 추가                                            │
│  ├── 00-problem-recognition ← MSA/EDA 문제 인식 종합                        │
│  ├── 01-saga-pattern        ← Saga 핵심 (오케스트레이션)                    │
│  ├── 02-idempotency         ← 재시도의 전제조건                             │
│  ├── 03-resilience4j        ← 재시도/타임아웃/서킷브레이커                  │
│  ├── 04-distributed-lock    ← 분산 락 + 세마포어                            │
│  ├── 05-optimistic-lock     ← 낙관적 락 (@Version)                          │
│  ├── 10-contract-testing    ← ★ 서비스 간 계약 검증 (신규)                  │
│  ├── 11-saga-isolation      ← ★ Saga Dirty Read/Lost Update (신규)         │
│  └── 12-redis-lock-pitfalls ← ★ 분산 락 10가지 함정 (신규)                  │
│                                                                             │
│  Phase 2-B (4개 필수) ★ 1개 변경                                            │
│  ├── 01-redis-basics        ← Redis 기초 (다른 주제의 전제)                  │
│  ├── 04-outbox-pattern      ← 이벤트 발행 신뢰성 (중요!)                    │
│  ├── 05-opentelemetry-tempo ← ★ 분산 추적 (Zipkin→Tempo 변경)              │
│  └── 09-performance-testing ← ★ k6 부하 테스트 (신규)                       │
│                                                                             │
│  Phase 3 (전체 필수)                                                        │
│  ├── 01-temporal-concepts   ← Temporal 핵심 개념                            │
│  ├── 02-temporal-spring     ← Spring 연동 + Saga 전환                       │
│  └── 03-temporal-limitations← Temporal 한계와 보완 전략                     │
│                                                                             │
│  DevOps (1개 필수) ★ 신규                                                   │
│  └── 01-github-actions      ← CI/CD 파이프라인                              │
│                                                                             │
│  ═══════════════════════════════════════════════════════════════════════    │
│  총 22개 항목 → "MSA 어려움 → Temporal 해결 + 한계 인식 + 실무 배포" 체감   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 선택 항목 (심화/부가 - 13개)

기본 지식이 있거나 시간이 부족하면 건너뛸 수 있습니다.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           선택 학습 항목                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Phase 2-A 선택 (4개)                                                       │
│  ├── 04-1-queue-semaphore    ← 대기열+세마포어 조합 (심화)                  │
│  ├── 06-bean-validation      ← 입력 검증 (기본 지식 있으면 생략)            │
│  ├── 07-exception-handling   ← 예외 처리 (기본 지식 있으면 생략)            │
│  ├── 08-mdc-logging          ← MDC 로깅 (부가)                              │
│  └── 09-transaction-template ← 트랜잭션 템플릿 (부가)                       │
│                                                                             │
│  Phase 2-B 선택 (5개)                                                       │
│  ├── 02-redis-stream         ← Redis Stream (MQ 구현)                       │
│  ├── 03-redisson             ← Redisson 심화                                │
│  ├── 06-prometheus-grafana   ← 메트릭 시각화 (운영)                         │
│  ├── 07-loki                 ← 로그 수집 (운영)                             │
│  └── 08-alertmanager         ← 알림 설정 (운영)                             │
│                                                                             │
│  고급 (선택적 학습)                                                          │
│  ├── Event Sourcing / CQRS   ← 금융/감사 도메인 필수                        │
│  ├── API Gateway + JWT       ← 인증/인가 필요 시                            │
│  └── Virtual Threads         ← Spring Boot 3.5+ 최적화                      │
│                                                                             │
│  ═══════════════════════════════════════════════════════════════════════    │
│  운영 관련(Prometheus, Loki, Alertmanager)은 프로덕션 배포 시 필요          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 보강된 학습 문서 목록 (신규 8개)

| Phase | 문서 | 내용 | 우선순위 |
|-------|------|------|----------|
| 2-A | `10-contract-testing.md` | Pact 기반 계약 테스트 | 필수 |
| 2-A | `11-saga-isolation.md` | Saga Dirty Read, Lost Update 해결 | 필수 |
| 2-A | `12-redis-lock-pitfalls.md` | 10가지 함정과 해결책 | 필수 |
| 2-B | `05-opentelemetry-tempo.md` | Zipkin → Grafana Tempo | 필수 |
| 2-B | `09-performance-testing.md` | k6 부하 테스트 | 필수 |
| DevOps | `01-github-actions.md` | CI/CD 파이프라인 | 필수 |
| 고급 | `event-sourcing-cqrs.md` | 언제 쓰고 언제 안 쓰는지 | 선택 |
| 고급 | `api-gateway-auth.md` | Spring Cloud Gateway + JWT | 선택 |

---

## 학습 순서 (권장)

### Phase 2-A (동기 REST 기반 Saga) - 12개

```
00-problem-recognition → 01-saga-pattern → 02-idempotency → 03-resilience4j
→ 04-distributed-lock → 04-1-queue-semaphore → 05-optimistic-lock
→ 06-bean-validation → 07-exception-handling → 08-mdc-logging → 09-transaction-template
→ 10-contract-testing → 11-saga-isolation → 12-redis-lock-pitfalls
```

> **핵심 변경**: 멱등성(02) → 재시도(03) 순서 (멱등성이 재시도의 전제조건)
> **신규 추가**: 10, 11, 12 (테스트, Saga 심화, 분산 락 심화)

### Phase 2-B (MQ + Observability) - 9개

```
01-redis-basics → 02-redis-stream → 03-redisson → 04-outbox-pattern
→ 05-opentelemetry-tempo → 06-prometheus-grafana → 07-loki → 08-alertmanager
→ 09-performance-testing
```

> **핵심 변경**: Zipkin → Grafana Tempo
> **신규 추가**: 09 (k6 성능 테스트)

### Phase 3 (Temporal) - 3개

```
01-temporal-concepts → 02-temporal-spring → 03-temporal-limitations
```

### DevOps - 1개

```
01-github-actions
```

---

## Phase 2-A: 동기 REST 기반 Saga

> **외부 서비스 시뮬레이션**: Fake 구현체 사용 ([D015 참조](./architecture/DECISIONS.md#d015-외부-서비스-시뮬레이션-전략))

### 진행 현황

| # | 항목 | 상태 | 학습 문서 | 비고 |
|---|------|------|----------|------|
| 0 | MSA/EDA 문제 인식 종합 | ✅ 완료 | 00-problem-recognition | |
| 1 | Saga 패턴 이해 + 서비스 도메인/API 설계 | ✅ 완료 | 01-saga-pattern | |
| 2 | Fake PG 구현체 작성 | ✅ 완료 | [D015](./architecture/DECISIONS.md#d015) | 1단계/2단계 결제 패턴 지원 |
| 3 | 오케스트레이터 REST 호출 구현 | ✅ 완료 | 01-saga-pattern | |
| 4 | 보상 트랜잭션 구현 | ✅ 완료 | 01-saga-pattern | |
| 5 | 멱등성 처리 (Idempotency Key) | ✅ 완료 | 02-idempotency | AOP + Redis 기반 |
| 6 | Resilience4j 재시도/타임아웃 | 대기 | 03-resilience4j | |
| 7 | 재고 차감 분산 락 (RLock) | 대기 | 04-distributed-lock | |
| 8 | PG 호출 제한 세마포어 (RSemaphore) | 대기 | 04-distributed-lock | |
| 9 | 대기열 + 세마포어 조합 (버퍼링) | 대기 | 04-1-queue-semaphore | |
| 10 | 낙관적 락 (JPA @Version) | 대기 | 05-optimistic-lock | |
| 11 | Bean Validation 입력 검증 | 대기 | 06-bean-validation | |
| 12 | 글로벌 예외 처리 | 대기 | 07-exception-handling | |
| 13 | MDC 로깅 | 대기 | 08-mdc-logging | |
| 14 | TransactionTemplate 적용 | 대기 | 09-transaction-template | |
| 15 | **Contract Testing** | 대기 | 10-contract-testing | ★ 신규 |
| 16 | **Saga Isolation** | 대기 | 11-saga-isolation | ★ 신규 |
| 17 | **Redis 분산 락 함정** | 대기 | 12-redis-lock-pitfalls | ★ 신규 |

---

## Phase 2-B: MQ + Redis + Observability

> **외부 서비스 시뮬레이션**: Fake 구현체 사용 ([D015 참조](./architecture/DECISIONS.md#d015))

### 진행 현황

| # | 항목 | 상태 | 학습 문서 | 비고 |
|---|------|------|----------|------|
| 1 | Redis 기초 학습 | 대기 | 01-redis-basics | |
| 2 | Redis Stream 학습 | 대기 | 02-redis-stream | |
| 3 | Redisson 학습 | 대기 | 03-redisson | |
| 4 | Notification 서비스 구현 | 대기 | - | |
| 5 | Fake SMS/Email 구현체 작성 | 대기 | [D015](./architecture/DECISIONS.md#d015) | |
| 6 | Outbox 패턴 (이벤트 발행 신뢰성) | 대기 | 04-outbox-pattern | |
| 7 | OpenTelemetry + **Grafana Tempo** | 대기 | 05-opentelemetry-tempo | ★ Zipkin 대체 |
| 8 | Micrometer + Prometheus 연동 | 대기 | 06-prometheus-grafana | |
| 9 | Grafana 대시보드 구성 | 대기 | 06-prometheus-grafana | |
| 10 | Loki 로그 수집 연동 | 대기 | 07-loki | |
| 11 | Alertmanager 장애 알림 설정 | 대기 | 08-alertmanager | |
| 12 | **k6 성능 테스트** | 대기 | 09-performance-testing | ★ 신규 |

---

## Phase 3: Temporal 연동

### 진행 현황

| # | 항목 | 상태 | 학습 문서 | 비고 |
|---|------|------|----------|------|
| 1 | Temporal 핵심 개념 학습 | 대기 | 01-temporal-concepts | |
| 2 | Temporal 로컬 인프라 구성 | 대기 | 01-temporal-concepts | |
| 3 | Temporal + Spring 연동 | 대기 | 02-temporal-spring | spring-boot-starter 1.32.0 |
| 4 | Workflow/Activity 정의 | 대기 | 02-temporal-spring | |
| 5 | 기존 Saga 로직 Temporal 전환 | 대기 | 02-temporal-spring | |
| 6 | Temporal 한계와 보완 전략 | ✅ 완료 | 03-temporal-limitations | |

---

## DevOps

### 진행 현황

| # | 항목 | 상태 | 학습 문서 | 비고 |
|---|------|------|----------|------|
| 1 | **GitHub Actions CI/CD** | 대기 | devops/01-github-actions | ★ 신규 |
| 2 | Docker 멀티스테이지 빌드 | 선택 | devops/02-docker-best-practices | |

---

## 고도화: Core 라이브러리 (최후 목표)

> **우선순위**: 낮음 - Phase 1~3 학습 완료 후 진행

| # | 모듈 | 용도 | 상태 |
|---|------|------|------|
| 1 | core-lock | RLock + RSemaphore 추상화 | 대기 |
| 2 | core-stream | Redis Stream 추상화 | 대기 |
| 3 | core-observability | 메트릭 표준화 (Micrometer) | 대기 |

---

## 기술 스택 업데이트

| 기술 | 이전 | 현재 | 비고 |
|------|------|------|------|
| Spring Boot | 3.4.0 | 3.5.9 | Virtual Threads 정식 지원 |
| 분산 추적 | Zipkin | Grafana Tempo | Grafana 스택 통합 |
| 성능 테스트 | - | k6 | JavaScript 기반 |
| CI/CD | - | GitHub Actions | Docker 통합 |
| Temporal SDK | 1.x | 1.32.0 | spring-boot-starter GA |

---

## 세션 기록

세션별 상세 기록은 `sessions/` 폴더 참조:
- [Session 1 - 2026-01-21](./sessions/SESSION-001.md): 프로젝트 초기 설정
