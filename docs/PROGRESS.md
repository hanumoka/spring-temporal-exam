# 프로젝트 진행 현황

## 현재 상태

- **현재 Phase**: Phase 1 - 기반 구축
- **마지막 업데이트**: 2026-01-25
- **Spring Boot**: 3.4.0 (Core 라이브러리와 동일 버전)

---

## Core 라이브러리 개발 계획

> 자체 개발 공통 라이브러리 - JAR 배포 및 개인 프로젝트 재사용 ([D016 참조](./architecture/DECISIONS.md#d016-core-라이브러리-전략))

| 모듈 | 용도 | 개발 시점 | 상태 |
|------|------|----------|------|
| core-lock | RLock + RSemaphore | Phase 2-A | 대기 |
| core-stream | Redis Stream 추상화 | Phase 2-B | 대기 |
| core-observability | 메트릭 표준화 | Phase 2-B | 대기 |

---

## Phase 1: 기반 구축

### 학습 순서 (권장)

```
01-gradle-multimodule → 02-flyway → 03-spring-profiles → 04-docker-compose
```

### 진행 현황

| # | 항목 | 상태 | 학습 문서 | 실습 가이드 |
|---|------|------|----------|------------|
| 1 | 멀티모듈 프로젝트 구조 설계 | 대기 | 01-gradle-multimodule | step1 |
| 2 | 공통 모듈 (common) 구성 | 대기 | 01-gradle-multimodule | step2 |
| 3 | Docker Compose 인프라 구성 | 대기 | 04-docker-compose | step3 (예정) |
| 4 | Flyway DB 마이그레이션 설정 | 대기 | 02-flyway | step4 (예정) |
| 5 | Spring Profiles 환경별 설정 | 대기 | 03-spring-profiles | step5 (예정) |
| 6 | 데이터 모델 설계 | 대기 | - | step6 (예정) |
| 7 | 각 서비스 모듈 스켈레톤 생성 | 대기 | - | step7 (예정) |

## Phase 2-A: 동기 REST 기반 Saga

> **외부 서비스 시뮬레이션**: Fake 구현체 사용 ([D015 참조](./architecture/DECISIONS.md#d015-외부-서비스-시뮬레이션-전략))

### 학습 순서 (권장)

```
01-saga-pattern → 02-idempotency → 03-resilience4j → 04-distributed-lock
→ 05-optimistic-lock → 06-bean-validation → 07-exception-handling
→ 08-mdc-logging → 09-transaction-template
```

> **순서 변경 이유**: 멱등성(02)이 재시도(03)의 전제조건이므로 Resilience4j 앞에서 학습

### 진행 현황

| # | 항목 | 상태 | 학습 문서 |
|---|------|------|----------|
| 1 | Saga 패턴 이해 + 서비스 도메인/API 설계 | 대기 | 01-saga-pattern |
| 2 | Fake PG 구현체 작성 | 대기 | [D015](./architecture/DECISIONS.md#d015-외부-서비스-시뮬레이션-전략) |
| 3 | 오케스트레이터 REST 호출 구현 | 대기 | 01-saga-pattern |
| 4 | 보상 트랜잭션 구현 | 대기 | 01-saga-pattern |
| 5 | 멱등성 처리 (Idempotency Key) | 대기 | 02-idempotency |
| 6 | Resilience4j 재시도/타임아웃 | 대기 | 03-resilience4j |
| 7 | **core-lock 모듈 개발** | 대기 | [D016](./architecture/DECISIONS.md#d016-core-라이브러리-전략) |
| 8 | 재고 차감 분산 락 (RLock) | 대기 | 04-distributed-lock |
| 9 | PG 호출 제한 세마포어 (RSemaphore) | 대기 | 04-distributed-lock |
| 10 | 낙관적 락 (JPA @Version) | 대기 | 05-optimistic-lock |
| 11 | Bean Validation 입력 검증 | 대기 | 06-bean-validation |
| 12 | 글로벌 예외 처리 | 대기 | 07-exception-handling |
| 13 | MDC 로깅 | 대기 | 08-mdc-logging |
| 14 | TransactionTemplate 적용 | 대기 | 09-transaction-template |

## Phase 2-B: MQ + Redis + Observability

> **외부 서비스 시뮬레이션**: Fake 구현체 사용 ([D015 참조](./architecture/DECISIONS.md#d015-외부-서비스-시뮬레이션-전략))

### 학습 순서 (권장)

```
01-redis-basics → 02-redis-stream → 03-redisson → 04-outbox-pattern
→ 05-opentelemetry-zipkin → 06-prometheus-grafana → 07-loki → 08-alertmanager
```

### 진행 현황

| # | 항목 | 상태 | 학습 문서 |
|---|------|------|----------|
| 1 | Redis 기초 학습 | 대기 | 01-redis-basics |
| 2 | Redis Stream 학습 | 대기 | 02-redis-stream |
| 3 | Redisson 학습 | 대기 | 03-redisson |
| 4 | **core-stream 모듈 개발** | 대기 | [D016](./architecture/DECISIONS.md#d016-core-라이브러리-전략) |
| 5 | Notification 서비스 구현 | 대기 | - |
| 6 | Fake SMS/Email 구현체 작성 | 대기 | [D015](./architecture/DECISIONS.md#d015-외부-서비스-시뮬레이션-전략) |
| 7 | Outbox 패턴 (이벤트 발행 신뢰성) | 대기 | 04-outbox-pattern |
| 8 | **core-observability 모듈 개발** | 대기 | [D016](./architecture/DECISIONS.md#d016-core-라이브러리-전략) |
| 9 | OpenTelemetry/Zipkin 연동 | 대기 | 05-opentelemetry-zipkin |
| 10 | Micrometer + Prometheus 연동 | 대기 | 06-prometheus-grafana |
| 11 | Grafana 대시보드 구성 | 대기 | 06-prometheus-grafana |
| 12 | Loki 로그 수집 연동 | 대기 | 07-loki |
| 13 | Alertmanager 장애 알림 설정 | 대기 | 08-alertmanager |

## Phase 3: Temporal 연동

| 항목 | 상태 |
|------|------|
| Temporal 로컬 인프라 구성 | 대기 |
| Workflow/Activity 정의 | 대기 |
| 기존 로직 Temporal 전환 | 대기 |

---

## 세션 기록

세션별 상세 기록은 `sessions/` 폴더 참조:
- [Session 1 - 2026-01-21](./sessions/SESSION-001.md): 프로젝트 초기 설정

---

## 세션 템플릿

새 세션 파일 생성 시: `sessions/SESSION-NNN.md`

```markdown
# Session N - YYYY-MM-DD

## 목표

## 진행 내용
- [ ]

## 메모

## 다음 세션 목표
```
