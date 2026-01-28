# 프로젝트 진행 현황

## 현재 상태

- **현재 Phase**: Phase 1 - 기반 구축
- **마지막 업데이트**: 2026-01-28
- **Spring Boot**: 3.5.9

---

## Phase 1: 기반 구축

### 학습 순서 (권장)

```
01-gradle-multimodule → 02-flyway → 03-spring-profiles → 04-docker-compose
```

> 각 학습 문서 하단에 실습 가이드가 포함되어 있습니다.

### 진행 현황

| # | 항목 | 상태 | 학습 문서 |
|---|------|------|----------|
| 1 | 멀티모듈 프로젝트 구조 설계 | ✅ 완료 | 01-gradle-multimodule |
| 2 | 공통 모듈 (common) 구성 | ✅ 완료 | 01-gradle-multimodule |
| 3 | Docker Compose 인프라 구성 | 🔄 다음 단계 | 04-docker-compose |
| 4 | Flyway DB 마이그레이션 설정 | 대기 | 02-flyway |
| 5 | Spring Profiles 환경별 설정 | 대기 | 03-spring-profiles |
| 6 | 데이터 모델 설계 | 대기 | - |
| 7 | 각 서비스 모듈 스켈레톤 생성 | 대기 | - |

### Phase 1 상세 진행 (2026-01-28)

**Step 1: 멀티모듈 프로젝트 구조 설계**

| 단계 | 항목 | 상태 |
|------|------|------|
| 1-1 | 버전 카탈로그 생성 (`gradle/libs.versions.toml`) | ✅ 완료 |
| 1-2 | 루트 build.gradle 수정 (allprojects, subprojects) | ✅ 완료 |
| 1-3 | 7개 모듈 폴더 생성 | ✅ 완료 |
| 1-4 | 각 모듈 build.gradle 생성 | ✅ 완료 |
| 1-5 | 각 모듈 메인 클래스 생성 | ✅ 완료 |

**생성된 모듈:**
| 모듈 | 타입 | 패키지 | 메인 클래스 |
|------|------|--------|------------|
| common | 라이브러리 | - | - |
| service-order | Spring Boot 앱 | `com.hanumoka.order` | OrderApplication |
| service-inventory | Spring Boot 앱 | `com.hanumoka.inventory` | InventoryApplication |
| service-payment | Spring Boot 앱 | `com.hanumoka.payment` | PaymentApplication |
| service-notification | Spring Boot 앱 | `com.hanumoka.notification` | NotificationApplication |
| orchestrator-pure | Spring Boot 앱 | `com.hanumoka.orchestrator.pure` | PureOrchestratorApplication |
| orchestrator-temporal | Spring Boot 앱 | `com.hanumoka.orchestrator.temporal` | TemporalOrchestratorApplication |

**Step 2: 공통 모듈 (common) 구성**

| 단계 | 항목 | 상태 |
|------|------|------|
| 2-1 | 패키지 구조 생성 (dto, exception, event, util) | ✅ 완료 |
| 2-2 | 공통 API 응답 DTO (ApiResponse, ErrorInfo) | ✅ 완료 |
| 2-3 | 공통 예외 클래스 (BusinessException) | ✅ 완료 |
| 2-4 | 에러 코드 정의 (ErrorCode enum) | ✅ 완료 |

**생성된 공통 클래스:**
| 패키지 | 클래스 | 용도 |
|--------|--------|------|
| `com.hanumoka.common.dto` | ApiResponse<T> | 통일된 API 응답 형식 |
| `com.hanumoka.common.dto` | ErrorInfo | 에러 정보 (code, message) |
| `com.hanumoka.common.exception` | BusinessException | 비즈니스 예외 기반 클래스 |
| `com.hanumoka.common.exception` | ErrorCode | 에러 코드 enum (toErrorInfo 메서드 포함) |

**학습 메모:**
- API Response Body에 traceId, timestamp 등은 불필요
- traceId는 Response Header로 전달 (Phase 2-B OpenTelemetry에서 구현)
- 로깅 정보는 MDC + 구조화된 로그로 처리

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
| 7 | 재고 차감 분산 락 (RLock) | 대기 | 04-distributed-lock |
| 8 | PG 호출 제한 세마포어 (RSemaphore) | 대기 | 04-distributed-lock |
| 9 | 낙관적 락 (JPA @Version) | 대기 | 05-optimistic-lock |
| 10 | Bean Validation 입력 검증 | 대기 | 06-bean-validation |
| 11 | 글로벌 예외 처리 | 대기 | 07-exception-handling |
| 12 | MDC 로깅 | 대기 | 08-mdc-logging |
| 13 | TransactionTemplate 적용 | 대기 | 09-transaction-template |

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
| 4 | Notification 서비스 구현 | 대기 | - |
| 5 | Fake SMS/Email 구현체 작성 | 대기 | [D015](./architecture/DECISIONS.md#d015-외부-서비스-시뮬레이션-전략) |
| 6 | Outbox 패턴 (이벤트 발행 신뢰성) | 대기 | 04-outbox-pattern |
| 7 | OpenTelemetry/Zipkin 연동 | 대기 | 05-opentelemetry-zipkin |
| 8 | Micrometer + Prometheus 연동 | 대기 | 06-prometheus-grafana |
| 9 | Grafana 대시보드 구성 | 대기 | 06-prometheus-grafana |
| 10 | Loki 로그 수집 연동 | 대기 | 07-loki |
| 11 | Alertmanager 장애 알림 설정 | 대기 | 08-alertmanager |

## Phase 3: Temporal 연동

| # | 항목 | 상태 | 학습 문서 |
|---|------|------|----------|
| 1 | Temporal 핵심 개념 학습 | 대기 | 01-temporal-concepts |
| 2 | Temporal 로컬 인프라 구성 | 대기 | 01-temporal-concepts |
| 3 | Temporal + Spring 연동 | 대기 | 02-temporal-spring |
| 4 | Workflow/Activity 정의 | 대기 | 02-temporal-spring |
| 5 | 기존 Saga 로직 Temporal 전환 | 대기 | 02-temporal-spring |

---

## 고도화: Core 라이브러리 (최후 목표)

> **우선순위**: 낮음 - Phase 1~3 학습 완료 후 진행
>
> 자체 개발 공통 라이브러리 - JAR 배포 및 개인 프로젝트 재사용 ([D016 참조](./architecture/DECISIONS.md#d016-core-라이브러리-전략))

| # | 모듈 | 용도 | 상태 |
|---|------|------|------|
| 1 | core-lock | RLock + RSemaphore 추상화 | 대기 |
| 2 | core-stream | Redis Stream 추상화 | 대기 |
| 3 | core-observability | 메트릭 표준화 (Micrometer) | 대기 |

### 개발 조건

```
Phase 1~3 학습 완료 후:
├── 학습 과정에서 반복되는 패턴 식별
├── 추상화가 필요한 부분 도출
└── JAR로 분리하여 재사용 가능하게 개발
```

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
