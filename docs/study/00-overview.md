# 학습 로드맵 개요

## 이 문서의 목적

이 학습 자료는 **MSA(Microservice Architecture) 환경에서 EDA(Event-Driven Architecture)를 구현**할 때 필요한 기술들을 단계별로 학습할 수 있도록 구성되었습니다.

주니어 개발자도 이해할 수 있도록 각 개념을 상세히 설명하고, 실제 코드 예시와 함께 "왜 이 기술이 필요한지"를 중심으로 설명합니다.

---

## 프로젝트 배경: 왜 이것을 배워야 하는가?

### 모놀리식 vs 마이크로서비스

**모놀리식 아키텍처**는 하나의 큰 애플리케이션으로 모든 기능을 구현합니다:

```
┌─────────────────────────────────────┐
│           모놀리식 서버              │
│  ┌─────────────────────────────┐   │
│  │  주문 + 결제 + 재고 + 알림   │   │
│  │      (하나의 DB 트랜잭션)    │   │
│  └─────────────────────────────┘   │
│              │                      │
│         [ MySQL ]                   │
└─────────────────────────────────────┘
```

**장점**: 개발이 단순하고, 하나의 DB 트랜잭션으로 데이터 일관성 보장이 쉽습니다.

**단점**: 서비스가 커지면 배포가 어렵고, 한 부분의 장애가 전체에 영향을 미칩니다.

---

**마이크로서비스 아키텍처**는 기능별로 독립된 서비스로 분리합니다:

```
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│  주문    │  │  결제    │  │  재고    │  │  알림    │
│ 서비스   │  │ 서비스   │  │ 서비스   │  │ 서비스   │
└────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘
     │             │             │             │
  [Order DB]   [Payment DB]  [Inventory DB]  [Notif DB]
```

**장점**: 독립 배포, 확장성, 기술 다양성, 장애 격리

**단점**: 분산 트랜잭션 관리가 어려움 (이 프로젝트의 핵심 주제!)

---

### 분산 트랜잭션의 어려움

모놀리식에서는 간단했던 "주문 생성"이 MSA에서는 복잡해집니다:

```
[모놀리식]
BEGIN TRANSACTION
  1. 주문 생성
  2. 재고 차감
  3. 결제 처리
COMMIT  ← 하나라도 실패하면 전체 ROLLBACK

[MSA]
1. 주문 서비스: 주문 생성 (성공)
2. 재고 서비스: 재고 차감 (성공)
3. 결제 서비스: 결제 처리 (실패!)  ← 이미 1, 2는 커밋됨!
   → 어떻게 롤백하지? 😱
```

이 문제를 해결하기 위해 **Saga 패턴**, **보상 트랜잭션**, **이벤트 기반 아키텍처** 등을 학습합니다.

---

## 학습 경로 전체 구조

```
┌─────────────────────────────────────────────────────────────────┐
│                        학습 로드맵                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Phase 1: 기반 구축                                             │
│  ├── Gradle 멀티모듈                                            │
│  ├── Flyway (DB 마이그레이션)                                   │
│  ├── Spring Profiles (환경 설정)                                │
│  └── Docker Compose (인프라)                                    │
│           │                                                     │
│           ▼                                                     │
│  Phase 2-A: REST 기반 Saga + 동시성/장애 대응                   │
│  ├── Saga 패턴 (Orchestration)                                  │
│  ├── Resilience4j (재시도, 서킷브레이커)                        │
│  ├── 분산 락 / 낙관적 락 / 멱등성                               │
│  ├── Bean Validation                                            │
│  ├── 예외 처리                                                  │
│  └── MDC 로깅                                                   │
│           │                                                     │
│           ▼                                                     │
│  Phase 2-B: MQ + Redis + Observability                          │
│  ├── Redis 기초                                                 │
│  ├── Redis Stream (MQ)                                          │
│  ├── Redisson (분산 락)                                         │
│  ├── Outbox 패턴                                                │
│  ├── OpenTelemetry/Zipkin (분산 추적)                           │
│  ├── Prometheus/Grafana (메트릭)                                │
│  ├── Loki (로그 수집)                                           │
│  └── Alertmanager (알람)                                        │
│           │                                                     │
│           ▼                                                     │
│  Phase 3: Temporal 연동                                         │
│  ├── Temporal 핵심 개념                                         │
│  └── Temporal + Spring 연동                                     │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Phase별 학습 목표

### Phase 1: 기반 구축

**목표**: 프로젝트의 뼈대를 구성합니다.

| 주제 | 학습 내용 | 왜 필요한가? |
|------|----------|-------------|
| Gradle 멀티모듈 | 하나의 프로젝트에서 여러 모듈 관리 | MSA 각 서비스를 독립 모듈로 구성 |
| Flyway | DB 스키마 버전 관리 | 팀 협업 시 DB 변경 이력 추적 |
| Spring Profiles | 환경별 설정 분리 | dev/staging/prod 환경 구분 |
| Docker Compose | 로컬 인프라 구성 | MySQL, Redis 등을 로컬에서 실행 |

---

### Phase 2-A: REST 기반 Saga + 동시성/장애 대응

**목표**: 분산 트랜잭션의 어려움을 직접 체험하고, 이를 해결하는 패턴을 학습합니다.

| 주제 | 학습 내용 | 왜 필요한가? |
|------|----------|-------------|
| Saga 패턴 | 분산 트랜잭션을 여러 로컬 트랜잭션으로 분리 | MSA에서 데이터 일관성 보장 |
| Resilience4j | 재시도, 타임아웃, 서킷브레이커 | 외부 서비스 장애 대응 |
| 분산 락 | 여러 서버에서 동시 접근 제어 | 재고 차감 등 동시성 이슈 해결 |
| 낙관적 락 | 충돌 감지 기반 동시성 제어 | DB 레벨 동시성 제어 |
| 멱등성 | 같은 요청을 여러 번 해도 결과가 동일 | 재시도 시 중복 처리 방지 |
| Bean Validation | 입력 값 검증 | 잘못된 요청 사전 차단 |
| 예외 처리 | 일관된 에러 응답 | API 품질 향상 |
| MDC 로깅 | 요청 추적을 위한 로깅 | 디버깅 용이성 |

---

### Phase 2-B: MQ + Redis + Observability

**목표**: 이벤트 기반 아키텍처와 모니터링 시스템을 학습합니다.

| 주제 | 학습 내용 | 왜 필요한가? |
|------|----------|-------------|
| Redis 기초 | 자료구조, 캐싱 | 성능 향상, 세션 관리 등 |
| Redis Stream | 메시지 큐 | 서비스 간 비동기 통신 |
| Redisson | Redis Java 클라이언트 | 분산 락, 분산 자료구조 |
| Outbox 패턴 | 이벤트 발행 신뢰성 | DB 저장과 이벤트 발행의 원자성 |
| OpenTelemetry/Zipkin | 분산 추적 | 서비스 간 요청 흐름 파악 |
| Prometheus/Grafana | 메트릭 수집/시각화 | 시스템 상태 모니터링 |
| Loki | 로그 수집 | 중앙 집중식 로그 관리 |
| Alertmanager | 알람 설정 | 장애 감지 시 알림 |

---

### Phase 3: Temporal 연동

**목표**: Temporal을 도입하여 분산 트랜잭션 관리가 얼마나 쉬워지는지 체감합니다.

| 주제 | 학습 내용 | 왜 필요한가? |
|------|----------|-------------|
| Temporal 개념 | Workflow, Activity, Worker | Temporal의 핵심 구성 요소 이해 |
| Spring 연동 | Temporal + Spring Boot | 기존 코드를 Temporal로 전환 |

---

## 학습 순서의 의미

이 프로젝트의 학습 순서는 **의도적으로 설계**되었습니다:

```
1. Phase 2-A에서 REST로 Saga를 직접 구현
   → 분산 트랜잭션이 얼마나 복잡한지 체험
   → 재시도, 타임아웃, 보상 트랜잭션의 어려움 인식

2. Phase 2-B에서 MQ를 추가
   → 비동기 처리의 복잡성 체험
   → 이벤트 순서, 중복 처리 등의 어려움 인식

3. Phase 3에서 Temporal 도입
   → "아, 이래서 Temporal이 필요하구나!" 체감
   → 이전에 고생했던 부분이 얼마나 쉬워지는지 느낌
```

---

## 비즈니스 시나리오: 상품 결제 플로우

모든 학습은 **상품 결제**라는 실제 비즈니스 시나리오를 기반으로 진행됩니다.

### 정상 플로우

```
고객이 상품 주문
       │
       ▼
┌──────────────┐
│ 1. 주문 생성 │ → Order Service (상태: PENDING)
└──────┬───────┘
       │
       ▼
┌──────────────┐
│ 2. 재고 차감 │ → Inventory Service (재고 예약)
└──────┬───────┘
       │
       ▼
┌──────────────┐
│ 3. 결제 처리 │ → Payment Service (결제 완료)
└──────┬───────┘
       │
       ▼
┌──────────────┐
│ 4. 주문 확정 │ → Order Service (상태: CONFIRMED)
└──────┬───────┘
       │
       ▼
┌──────────────┐
│ 5. 알림 발송 │ → Notification Service (이메일/SMS)
└──────────────┘
```

### 실패 시나리오 (보상 트랜잭션)

```
결제 실패 시:
┌──────────────┐
│ 결제 실패!   │
└──────┬───────┘
       │
       ▼
┌──────────────┐
│ 재고 복구    │ → Inventory Service (예약 취소)
└──────┬───────┘
       │
       ▼
┌──────────────┐
│ 주문 취소    │ → Order Service (상태: CANCELLED)
└──────────────┘
```

---

## 기술 스택 요약

| 분류 | 기술 | 용도 |
|------|------|------|
| **프레임워크** | Spring Boot 4.0.1 | 애플리케이션 프레임워크 |
| **언어** | Java 21 | 프로그래밍 언어 |
| **빌드** | Gradle | 빌드 도구 (멀티모듈) |
| **DB** | MySQL | 관계형 데이터베이스 |
| **ORM** | JPA (Hibernate) | 객체-관계 매핑 |
| **DB 마이그레이션** | Flyway | 스키마 버전 관리 |
| **캐시/락** | Redis + Redisson | 캐싱, 분산 락 |
| **MQ** | Redis Stream | 메시지 큐 |
| **장애 대응** | Resilience4j | 재시도, 서킷브레이커 |
| **검증** | Bean Validation | 입력 값 검증 |
| **분산 추적** | OpenTelemetry + Zipkin | 요청 추적 |
| **메트릭** | Micrometer + Prometheus | 메트릭 수집 |
| **시각화** | Grafana | 대시보드 |
| **로그** | Loki | 로그 수집 |
| **알람** | Alertmanager | 장애 알림 |
| **워크플로우** | Temporal | 분산 트랜잭션 관리 |
| **테스트** | JUnit 5 + Testcontainers | 단위/통합 테스트 |
| **컨테이너** | Docker Compose | 로컬 인프라 |

---

## 학습 자료 목록

### Phase 1: 기반 구축
- [01-gradle-multimodule.md](./phase1/01-gradle-multimodule.md) - Gradle 멀티모듈
- [02-flyway.md](./phase1/02-flyway.md) - DB 마이그레이션
- [03-spring-profiles.md](./phase1/03-spring-profiles.md) - 환경별 설정
- [04-docker-compose.md](./phase1/04-docker-compose.md) - Docker Compose

### Phase 2-A: REST 기반 Saga
- [01-saga-pattern.md](./phase2a/01-saga-pattern.md) - Saga 패턴
- [02-resilience4j.md](./phase2a/02-resilience4j.md) - 장애 대응
- [03-distributed-lock.md](./phase2a/03-distributed-lock.md) - 분산 락
- [04-optimistic-lock.md](./phase2a/04-optimistic-lock.md) - 낙관적 락
- [05-idempotency.md](./phase2a/05-idempotency.md) - 멱등성
- [06-bean-validation.md](./phase2a/06-bean-validation.md) - 입력 검증
- [07-exception-handling.md](./phase2a/07-exception-handling.md) - 예외 처리
- [08-mdc-logging.md](./phase2a/08-mdc-logging.md) - MDC 로깅

### Phase 2-B: MQ + Observability
- [01-redis-basics.md](./phase2b/01-redis-basics.md) - Redis 기초
- [02-redis-stream.md](./phase2b/02-redis-stream.md) - Redis Stream
- [03-redisson.md](./phase2b/03-redisson.md) - Redisson
- [04-outbox-pattern.md](./phase2b/04-outbox-pattern.md) - Outbox 패턴
- [05-opentelemetry-zipkin.md](./phase2b/05-opentelemetry-zipkin.md) - 분산 추적
- [06-prometheus-grafana.md](./phase2b/06-prometheus-grafana.md) - 메트릭
- [07-loki.md](./phase2b/07-loki.md) - 로그 수집
- [08-alertmanager.md](./phase2b/08-alertmanager.md) - 알람

### Phase 3: Temporal
- [01-temporal-concepts.md](./phase3/01-temporal-concepts.md) - Temporal 개념
- [02-temporal-spring.md](./phase3/02-temporal-spring.md) - Spring 연동

---

## 학습 팁

### 1. 순서대로 학습하세요
각 Phase는 이전 Phase의 내용을 기반으로 합니다. 건너뛰지 마세요.

### 2. 코드를 직접 작성하세요
읽기만 하지 말고, 예제 코드를 직접 타이핑하고 실행해 보세요.

### 3. 왜?를 항상 생각하세요
"이 기술이 왜 필요한가?", "없으면 어떤 문제가 생기나?"를 항상 생각하세요.

### 4. 실패를 경험하세요
의도적으로 장애 상황을 만들어 보세요. (네트워크 끊기, 서비스 중지 등)

### 5. 공식 문서를 참고하세요
이 자료는 시작점일 뿐입니다. 깊이 있는 학습은 공식 문서에서!

---

## 다음 단계

[Phase 1: Gradle 멀티모듈](./phase1/01-gradle-multimodule.md)부터 시작하세요!
