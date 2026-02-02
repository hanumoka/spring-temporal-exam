# 아키텍처 결정 사항 (ADR)

## 결정 요약

| 번호 | 항목 | 결정 |
|------|------|------|
| D001 | Saga 패턴 | Orchestration |
| D002 | 서비스 통신 | 동기 REST |
| D003 | 메시지 큐 | Redis Stream (Phase 2-B) |
| D004 | DB 구성 | 공유 DB + 스키마 분리 |
| D005 | 서비스 구성 | 4개 (Order, Inventory, Payment, Notification) |
| D006 | 테스트 전략 | 단위 + 통합 (Testcontainers) |
| D007 | Spring Cloud | 미사용 (학습 범위 집중) |
| D008 | Kubernetes | 미사용 (Docker Compose로 대체) |
| D009 | Service Mesh | 미사용 (학습 범위 외) |
| D010 | 동시성 제어 | 서비스별 차별화 적용 |
| D011 | ORM 전략 | JPA + MyBatis 전체 서비스 병행 적용 |
| D012 | 트랜잭션 관리 | TransactionTemplate (프로그래밍 방식) |
| D013 | Redis 운영 전략 | Pending List 복구 + Phantom Key 대응 |
| D014 | Spring Boot 버전 전략 | 3.4.0 (Core 라이브러리 동일), 추후 고도화 시 4.x 전환 |
| D015 | 외부 서비스 시뮬레이션 | Fake 구현체 (인터페이스 기반) |
| D016 | Core 라이브러리 전략 | 자체 개발 + JAR 배포 (최후 목표, Phase 3 완료 후) |
| D017 | 대기열 + 세마포어 조합 | Redis Queue + RSemaphore (트래픽 폭주 대응) |
| D018 | Temporal 보완 전략 | Phase 2 기술과 Temporal 조합 (동시성/멱등성 보완) |
| D019 | 테스트 전략 확장 | Contract Testing + Integration Testing (테스트 다이아몬드) |
| D020 | Saga Isolation | Semantic Lock + Reread Values 전략 적용 |
| D021 | Redis 분산 락 심화 | 10가지 함정 대응 + Redlock 고려 |
| D022 | 성능 테스트 | k6 기반 부하 테스트 |
| D023 | CI/CD 파이프라인 | GitHub Actions + Docker |
| D024 | 분산 추적 현대화 | Zipkin → Grafana Tempo 전환 고려 |
| D025 | Virtual Threads | Spring Boot 3.5+ 활성화 |

---

## D001. Saga 패턴 방식

**결정**: Orchestration

**근거**:
- Temporal이 Orchestration 방식이므로 순수 구현 → Temporal 전환이 자연스러움
- 디버깅 용이, 플로우 파악 쉬움

---

## D002. 서비스 간 통신 방식

**결정**: 동기 REST

**통신 구조**:
```
Orchestrator
  [1] POST /orders ──────────────> [Order Service]
  [2] POST /inventory/reserve ───> [Inventory Service]
  [3] POST /payments ────────────> [Payment Service]
  [4] PUT /orders/{id}/confirm ──> [Order Service]
```

---

## D003. 메시지 큐 선택

**결정**: Redis Stream

**사용 용도**: 이벤트 발행/구독 (Notification Service)

---

## D004. 데이터베이스 구성

**결정**: 공유 DB + 스키마 분리

```
MySQL
├── order_db
├── inventory_db
├── payment_db
└── notification_db
```

---

## D005. 서비스 구성

| 서비스 | 역할 | 통신 방식 | 동시성 제어 |
|--------|------|----------|------------|
| Order Service | 주문 생성/확정/취소 | REST | 낙관적 락 |
| Inventory Service | 재고 예약/확정/복구 | REST | 분산 락 |
| Payment Service | 결제 처리/환불 | REST | 세마포어 |
| Notification Service | 알림 발송 | MQ 구독 | 세마포어 |

---

## D006. 테스트 전략

| 수준 | 도구 | 범위 |
|------|------|------|
| 단위 테스트 | JUnit 5, Mockito | 개별 클래스 |
| 통합 테스트 | Testcontainers | DB, MQ 연동 |

---

## D100. 2레이어 아키텍처

```
┌─────────────────────────────────────────────┐
│           Orchestration Layer               │
│  ┌──────────────┐    ┌──────────────────┐  │
│  │ Pure 구현    │ or │ Temporal 구현    │  │
│  └──────────────┘    └──────────────────┘  │
└─────────────────────────────────────────────┘
                    │ REST
                    ▼
┌─────────────────────────────────────────────┐
│         Business Service Layer              │
│  ┌───────┐ ┌─────────┐ ┌───────┐ ┌──────┐  │
│  │ Order │ │Inventory│ │Payment│ │Notif.│  │
│  └───────┘ └─────────┘ └───────┘ └──────┘  │
└─────────────────────────────────────────────┘
```

---

## D101. 학습 경로

```
Phase 2-A: 동기 REST 기반 Saga (MQ 없이)
    ↓ 분산 트랜잭션의 어려움 체험
Phase 2-B: MQ 이벤트 추가 + Redis 학습
    ↓ EDA의 복잡성 인지
Phase 3: Temporal 연동
    → "왜 Temporal이 필요한지" 체감
```

---

## D007. Spring Cloud 미사용

**결정**: 미사용

**고려된 컴포넌트**:
- Spring Cloud Gateway (API 게이트웨이)
- Eureka (서비스 디스커버리)
- Spring Cloud Config (설정 관리)
- Spring Cloud LoadBalancer (로드밸런싱)

**미사용 이유**:

```
1. 학습 목표 집중
   └── Temporal과 분산 트랜잭션이 핵심 주제
   └── Spring Cloud는 별도의 큰 학습 주제

2. 복잡도 관리
   └── 핵심 개념 이해에 방해될 수 있음
   └── localhost 직접 접근으로 충분

3. Temporal과의 역할 중복
   └── 서비스 간 통신은 Temporal Activity로 처리
   └── 재시도/타임아웃은 Temporal이 담당
```

**대안**:
- 서비스 디스커버리: 직접 URL 지정 (localhost:808x)
- 설정 관리: Spring Profiles + 환경변수

**향후 확장**: 실무 적용 시 필요에 따라 추가 가능

---

## D008. Kubernetes 미사용

**결정**: 미사용 (Docker Compose로 대체)

**미사용 이유**:

```
1. 학습 환경 단순화
   └── 로컬 개발 환경에서 K8s는 과도함
   └── Docker Compose로 충분한 컨테이너 환경 제공

2. 인프라 학습과 분리
   └── K8s는 DevOps 영역
   └── 애플리케이션 개발에 집중

3. Temporal과 독립적
   └── Temporal은 K8s 없이도 동작
   └── Docker Compose에서 충분히 학습 가능
```

**K8s가 대체하는 Spring Cloud 기능**:

| Spring Cloud | Kubernetes |
|--------------|------------|
| Eureka | K8s Service + DNS |
| Config Server | ConfigMap/Secret |
| Ribbon | K8s Service (서버사이드 LB) |
| Gateway | Ingress Controller |

**향후 확장**: 프로덕션 배포 시 K8s 전환 권장

---

## D009. Service Mesh 미사용

**결정**: 미사용

**고려된 기술**: Istio, Linkerd

**미사용 이유**:

```
1. 학습 범위 외
   └── Service Mesh는 인프라 레벨 기술
   └── K8s 없이는 의미 없음

2. 복잡도
   └── Istio 학습 곡선이 가파름
   └── 현재 학습 목표와 무관

3. Temporal로 충분
   └── 비즈니스 레벨 오케스트레이션은 Temporal
   └── 네트워크 레벨 기능은 현재 불필요
```

**Service Mesh가 제공하는 기능**:
- mTLS 자동 암호화
- 서킷 브레이커 (플랫폼 레벨)
- 트래픽 관리 (카나리, A/B)
- 분산 추적 자동 수집

**향후 확장**: 대규모 프로덕션 환경에서 고려

---

## D010. 동시성 제어 전략

**결정**: 서비스별 특성에 맞는 동시성 제어 메커니즘 적용

### 서비스별 동시성 제어

| 서비스 | 메커니즘 | 용도 |
|--------|----------|------|
| **Inventory Service** | 분산 락 (RLock) | 재고 차감 시 동시 접근 제어 |
| **Order Service** | 낙관적 락 (@Version) | 주문 상태 변경 충돌 감지 |
| **Payment Service** | 세마포어 (RSemaphore) | 외부 PG사 API 동시 호출 제한 |
| **Notification Service** | 세마포어 (RSemaphore) | SMS/이메일 API 동시 호출 제한 |

### 적용 근거

```
┌─────────────────────────────────────────────────────────────────────┐
│                    동시성 제어 메커니즘 선택 기준                      │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [분산 락 - RLock]                                                   │
│  └── 사용 시점: 단 하나의 요청만 처리해야 할 때                       │
│  └── 예: 재고 차감 (동일 상품에 대해 순차 처리 필요)                  │
│                                                                      │
│  [낙관적 락 - @Version]                                              │
│  └── 사용 시점: 충돌이 드물고, 충돌 시 재시도 가능할 때               │
│  └── 예: 주문 상태 변경 (동시 수정 충돌 감지)                         │
│                                                                      │
│  [세마포어 - RSemaphore]                                             │
│  └── 사용 시점: N개까지 동시 처리를 허용할 때                         │
│  └── 예: 외부 API 호출 제한 (PG사 TPS 제한 준수)                      │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Payment Service 세마포어 상세

```
┌─────────────────────────────────────────────────────────────────────┐
│                  Payment Service - PG사 호출 제한                    │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [Payment Service 인스턴스들]                                        │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐                            │
│  │Instance 1│ │Instance 2│ │Instance 3│                            │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘                            │
│       │            │            │                                   │
│       └────────────┼────────────┘                                   │
│                    │                                                 │
│                    ▼                                                 │
│  ┌─────────────────────────────────────────┐                        │
│  │        RSemaphore (Redis)               │                        │
│  │        Key: "semaphore:pg:toss"         │                        │
│  │        Permits: 10 (동시 10개 요청)      │                        │
│  └─────────────────────────────────────────┘                        │
│                    │                                                 │
│                    ▼                                                 │
│  ┌─────────────────────────────────────────┐                        │
│  │        외부 PG사 API (토스페이먼츠)       │                        │
│  │        Rate Limit: 50 TPS               │                        │
│  └─────────────────────────────────────────┘                        │
│                                                                      │
│  효과: 3개 인스턴스 합계 최대 10개 동시 요청으로 제한                  │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Notification Service 세마포어 상세

```
문제: SMS 발송 API가 초당 5건으로 제한됨
해결: RSemaphore로 동시 발송 수 제한

RSemaphore semaphore = redissonClient.getSemaphore("semaphore:sms:provider");
semaphore.trySetPermits(5);  // 최대 5개 동시 발송
```

### Resilience4j Bulkhead와의 차이

| 특성 | RSemaphore | Resilience4j Bulkhead |
|------|------------|----------------------|
| 범위 | 분산 (전체 인스턴스) | 로컬 (단일 인스턴스) |
| 저장소 | Redis | 메모리 |
| 정확도 | 전역 정확 | 인스턴스별 독립 |
| 사용 시점 | 외부 API 전체 제한 | 내부 리소스 격리 |

**이 프로젝트 선택**: 다중 인스턴스에서 PG사 API 제한을 정확히 지켜야 하므로 RSemaphore 사용

---

## D011. ORM 전략

**결정**: JPA + MyBatis 전체 서비스 병행 적용

### 적용 범위

모든 비즈니스 서비스(Order, Inventory, Payment)에 JPA와 MyBatis를 동시에 적용합니다.

| 서비스 | JPA | MyBatis | 학습 포인트 |
|--------|-----|---------|------------|
| Order Service | 엔티티 관리 | Saga 상태 추적, 낙관적 락 | WHERE version = ? |
| Inventory Service | 기본 CRUD | 재고 차감, 분산 락과 연계 | FOR UPDATE 직접 작성 |
| Payment Service | 결제 정보 관리 | 멱등성 체크, 트랜잭션 로그 | INSERT IGNORE |

### 배경

```
┌─────────────────────────────────────────────────────────────────────┐
│                    쿼리 기반 동시성 제어 학습                         │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [문제점]                                                            │
│  JPA @Version, @Lock 등은 내부 동작이 추상화되어 있음                 │
│  → "WHERE version = ?" 조건이 왜 필요한지 체감하기 어려움            │
│                                                                      │
│  [해결책]                                                            │
│  MyBatis로 직접 SQL 작성하여 쿼리 레벨 원리 이해                     │
│  → 두 방식 모두 학습하여 "원리 이해 + 실무 적용" 모두 달성           │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 병행 학습 대상

| 개념 | JPA | MyBatis |
|------|-----|---------|
| 낙관적 락 | @Version 자동 처리 | WHERE version = ? 직접 작성 |
| 비관적 락 | @Lock(PESSIMISTIC_WRITE) | SELECT ... FOR UPDATE 직접 작성 |
| 벌크 업데이트 | @Modifying + @Query | UPDATE 쿼리 직접 작성 |
| 멱등성 체크 | save() + unique constraint | INSERT IGNORE / ON DUPLICATE KEY |

### 학습 순서

```
1. JPA로 먼저 구현
   └── 선언적 방식의 편리함 경험

2. MyBatis로 동일 기능 구현
   └── SQL 직접 작성하며 원리 이해
   └── "WHERE version = ?" 조건의 의미 체감

3. 두 방식 비교
   └── 장단점 파악
   └── 상황에 맞는 선택 능력 배양
```

### 서비스별 ORM 적용 (선택 가능)

| 서비스 | 권장 ORM | 이유 |
|--------|---------|------|
| Order Service | JPA 또는 MyBatis | 낙관적 락 학습용 |
| Inventory Service | JPA + MyBatis 비교 | 분산 락 + 낙관적 락 조합 |
| Payment Service | JPA | 비즈니스 로직 중심 |
| Notification Service | JPA | 단순 CRUD |

**참고**: 실무에서는 하나의 ORM을 일관되게 사용하는 것이 권장되지만,
학습 목적으로는 두 방식 모두 경험하는 것이 유익합니다.

### MyBatis 학습 문서

아래 문서들에 MyBatis 구현 섹션이 포함되어 있습니다:

| 문서 | MyBatis 학습 내용 | 핵심 SQL 패턴 |
|------|------------------|---------------|
| **Phase 2-A** | | |
| [01-saga-pattern.md](../study/phase2a/01-saga-pattern.md) | Saga 상태 관리 | `WHERE version = ?`, 동적 컬럼 UPDATE |
| [02-idempotency.md](../study/phase2a/02-idempotency.md) | 멱등성 보장 | `INSERT IGNORE`, `ON DUPLICATE KEY UPDATE` |
| [05-optimistic-lock.md](../study/phase2a/05-optimistic-lock.md) | 낙관적 락 구현 | `WHERE version = ?`, CAS 패턴 |
| **Phase 2-B** | | |
| [04-outbox-pattern.md](../study/phase2b/04-outbox-pattern.md) | Outbox 폴링 | `FOR UPDATE SKIP LOCKED`, 배치 삭제 |

---

## D012. 트랜잭션 관리 전략

**결정**: TransactionTemplate (프로그래밍 방식)

### 배경

```
┌─────────────────────────────────────────────────────────────────────┐
│                @Transactional vs TransactionTemplate                 │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [@Transactional - 선언적 방식]                                      │
│  ├── 장점: 간편함, 코드 간결                                         │
│  └── 단점: 경계 암묵적, 모니터링 어려움, self-invocation 문제        │
│                                                                      │
│  [TransactionTemplate - 프로그래밍 방식]                             │
│  ├── 장점: 경계 명시적, 모니터링 용이, 세밀한 제어                   │
│  └── 단점: 코드량 증가                                               │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 선택 이유

```
1. 모니터링 필수
   └── 트랜잭션 시작/종료 시점에 메트릭/로깅 삽입 필요
   └── @Transactional은 AOP 기반이라 삽입 어려움

2. 트랜잭션 경계 명확화
   └── 코드에서 트랜잭션 시작/종료가 명시적으로 보임
   └── 디버깅 및 코드 리뷰 용이

3. 학습 목적
   └── 트랜잭션 동작 원리를 명확히 이해
   └── 스프링의 마법(Magic)에 의존하지 않음
```

### 모니터링 대상

| 항목 | 메트릭 | 설명 |
|------|--------|------|
| 성공/실패 | `transaction.success`, `transaction.failure` | 트랜잭션 결과 카운트 |
| 소요 시간 | `transaction.duration` | 트랜잭션 처리 시간 |
| 활성 수 | `transaction.active` | 현재 진행 중인 트랜잭션 |
| 롤백 | `transaction.rollback` | 롤백 발생 카운트 |

### 구현 패턴

```java
public Order createOrder(OrderRequest request) {
    String txId = generateTxId();
    Instant start = Instant.now();
    MDC.put("txId", txId);

    try {
        Order result = transactionTemplate.execute(status -> {
            // 비즈니스 로직
            return orderRepository.save(Order.create(request));
        });

        meterRegistry.counter("transaction.success", "type", "order").increment();
        return result;

    } catch (Exception e) {
        meterRegistry.counter("transaction.failure", "type", "order").increment();
        throw e;

    } finally {
        meterRegistry.timer("transaction.duration", "type", "order")
            .record(Duration.between(start, Instant.now()));
        MDC.remove("txId");
    }
}
```

### 관련 문서

- [09-transaction-template.md](../study/phase2a/09-transaction-template.md) - TransactionTemplate 학습

---

## D013. Redis 운영 전략

**결정**: Pending List 체계적 복구 + Phantom Key 대응 전략 수립

### 배경

```
┌─────────────────────────────────────────────────────────────────────┐
│                Redis 운영 시 주요 위험 요소                           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [Redis Stream - Pending List 문제]                                  │
│  ├── 고아 메시지: Consumer 크래시 시 ACK 안 된 메시지 방치            │
│  ├── 무한 재시도: 처리 실패 메시지가 계속 순환                        │
│  ├── 메모리 누수: PEL(Pending Entries List) 무한 성장                │
│  └── 중복 처리: XCLAIM 시 원래 Consumer도 처리 중일 수 있음          │
│                                                                      │
│  [Redisson - Phantom Key 문제]                                       │
│  ├── 락 TTL 만료: 처리 중 락이 사라져 동시 접근 발생                 │
│  ├── 다른 서버 락 삭제: unlock 시 다른 서버의 락을 삭제할 수 있음    │
│  └── Check-then-Act: EXISTS 후 GET 사이에 키가 삭제될 수 있음        │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Pending List 대응 전략

| 전략 | 구현 방법 | 목적 |
|------|----------|------|
| **주기적 복구** | XCLAIM + 스케줄러 | 고아 메시지 재처리 |
| **DLQ 이동** | 최대 재시도 초과 시 DLQ로 | 독 메시지 격리 |
| **멱등성 처리** | SETNX로 처리 여부 기록 | 중복 처리 방지 |
| **Consumer 정리** | 비활성 Consumer 자동 삭제 | 리소스 정리 |
| **모니터링** | Pending 수, idle time 메트릭 | 문제 조기 탐지 |

### Phantom Key 대응 전략

| 전략 | 구현 방법 | 목적 |
|------|----------|------|
| **Watch Dog** | leaseTime 미지정 (자동 연장) | 락 자동 연장 |
| **Fencing Token** | 단조 증가 토큰 발급 및 검증 | 지연된 쓰기 방지 |
| **안전한 해제** | Lua Script로 소유자 검증 | 다른 락 삭제 방지 |
| **락 모니터링** | TTL 없는 락, 장기 보유 락 탐지 | 이상 상태 감지 |

### 락 타임아웃 전략

```
┌─────────────────────────────────────────────────────────────────────┐
│                    작업 유형별 락 전략                                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [짧은 작업 < 1초]         → 고정 TTL (5~10초)                       │
│  예: 재고 차감, 포인트 적립                                          │
│                                                                      │
│  [중간 작업 1~30초]        → Watch Dog 또는 충분한 TTL (60초)        │
│  예: 주문 처리, 결제 처리                                            │
│                                                                      │
│  [긴 작업 > 30초]          → Watch Dog 필수                          │
│  예: 배치 처리, 리포트 생성                                          │
│                                                                      │
│  [불확실한 작업]           → Watch Dog + Fencing Token               │
│  예: 외부 API 호출, 복잡한 비즈니스 로직                             │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 필수 모니터링 항목

| 카테고리 | 메트릭 | 알림 조건 |
|----------|--------|----------|
| **Pending** | `redis.stream.pending.total` | > 1000 |
| **Pending** | `redis.stream.pending.old` (5분 이상) | > 100 |
| **DLQ** | `redis.stream.dlq.size` | > 0 |
| **Lock** | `redis.lock.active` | 급격한 변화 |
| **Lock** | `redis.lock.no.ttl` | > 0 (즉시 알림) |
| **Lock** | `redis.lock.expiring.soon` | > 10 |

### 관련 문서

| 문서 | 내용 |
|------|------|
| [02-redis-stream.md](../study/phase2b/02-redis-stream.md) | Pending List 심화 (섹션 6) |
| [03-redisson.md](../study/phase2b/03-redisson.md) | Phantom Key와 락 타임아웃 (섹션 8) |

---

## D014. Spring Boot 버전 전략

**결정**: Spring Boot 3.4.0 사용, 추후 고도화 시 4.x 전환

### 배경

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Spring Boot 버전 결정 (2026-01-25)                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [Core 라이브러리 호환성]                                            │
│  ├── 자체 개발 Core 라이브러리: Spring Boot 3.4.0 기반              │
│  ├── Redisson: 3.52.0 (Spring Boot 3.x 호환)                        │
│  └── Spring Boot 4.x 사용 시 Redisson 4.0+ 필요                     │
│                                                                      │
│  [Temporal 호환성]                                                   │
│  ├── temporal-spring-boot-starter: Spring Boot 3.x 공식 지원        │
│  ├── Spring Boot 4.x: 공식 지원 미확인                              │
│  └── 현재 버전으로 안정적 학습 가능                                  │
│                                                                      │
│  [결정]                                                              │
│  └── Spring Boot 3.4.0으로 진행, 학습 완료 후 버전업 고도화         │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 전략

| Phase | Spring Boot | 비고 |
|-------|-------------|------|
| Phase 1 | 3.4.0 | 기반 구축 |
| Phase 2-A | 3.4.0 | Saga, 동시성 학습 |
| Phase 2-B | 3.4.0 | MQ, Observability 학습 |
| Phase 3 | 3.4.0 | Temporal 연동 |
| **고도화** | 4.x 전환 | 학습 완료 후 마이그레이션 |

### 고도화 시 체크리스트

```
[ ] Spring Boot 4.x 마이그레이션 가이드 참조
[ ] Core 라이브러리 4.x 업그레이드
    ├── Redisson 3.52.0 → 4.x
    ├── Auto-configuration 모듈화 대응
    └── Breaking changes 적용
[ ] Temporal SDK Spring Boot 4 지원 확인
[ ] 전체 테스트 검증
```

### 참고 자료

- [Spring Boot 3.4.0 Release](https://spring.io/blog/2024/11/21/spring-boot-3-4-0-available-now/)
- [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Temporal Spring Boot Integration](https://docs.temporal.io/develop/java/spring-boot-integration)

---

## D015. 외부 서비스 시뮬레이션 전략

**결정**: Fake 구현체 (인터페이스 기반)

### 배경

```
┌─────────────────────────────────────────────────────────────────────┐
│                    외부 서비스 시뮬레이션 필요성                       │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [문제]                                                              │
│  ├── 실제 PG사(토스, 나이스 등) 연동 불가 (개발 계정/비용 이슈)       │
│  ├── 실제 SMS/이메일 발송 불가 (비용/스팸 이슈)                       │
│  └── 하지만 MSA 환경 문제(세마포어, 재시도 등) 학습 필요              │
│                                                                      │
│  [목적]                                                              │
│  └── 실제 외부 API 없이도 MSA 환경의 어려움을 체험하고 학습           │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 선택지 비교

| 방식 | 복잡도 | HTTP 통신 | 장애 시뮬레이션 | 학습 가치 |
|------|--------|----------|----------------|----------|
| **Fake 구현체** | 낮음 | ❌ | 코드로 제어 | 빠른 구현, 핵심 집중 |
| WireMock | 중간 | ✅ | 시나리오 파일 | 실제 HTTP 경험 |
| 별도 Mock 서비스 | 높음 | ✅ | 자유도 높음 | 인프라 이해 |

### 결정 이유

```
1. 학습 목표 집중
   └── HTTP 레벨 시뮬레이션보다 세마포어/재시도/분산 트랜잭션 학습이 핵심
   └── Fake 구현체로도 충분히 동시성/장애 시나리오 체험 가능

2. 인프라 복잡도 최소화
   └── WireMock 등 추가 인프라 설정 불필요
   └── 코드만으로 다양한 시나리오 구현

3. 유연한 시나리오 제어
   └── 지연, 실패, 타임아웃 등을 코드로 직접 제어
   └── 테스트 시나리오별 동작 변경 용이
```

### Fake 구현체 설계

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Fake 구현체 구조                                   │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [인터페이스]                                                        │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  public interface PaymentGateway {                           │   │
│  │      PaymentResult process(PaymentRequest request);          │   │
│  │      RefundResult refund(String transactionId);              │   │
│  │  }                                                           │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                          ▲                                          │
│                          │ implements                               │
│            ┌─────────────┴─────────────┐                           │
│            │                           │                            │
│  ┌─────────────────────┐    ┌─────────────────────┐                │
│  │  FakePaymentGateway │    │  RealPaymentGateway │                │
│  │  (학습/테스트용)     │    │  (실제 연동 - 미구현)│                │
│  └─────────────────────┘    └─────────────────────┘                │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 시뮬레이션 시나리오

| 시나리오 | 구현 방법 | 학습 포인트 |
|----------|----------|------------|
| **지연 응답** | `Thread.sleep(2000)` | 타임아웃 처리, Resilience4j |
| **간헐적 실패** | 랜덤 확률로 예외 발생 | 재시도 전략 |
| **연속 실패** | 실패 횟수 카운터 | 서킷 브레이커 |
| **동시 요청 폭주** | 다중 스레드 테스트 | 세마포어 효과 확인 |
| **부분 성공** | 특정 조건에서만 성공 | 보상 트랜잭션 (Saga) |
| **중복 요청** | 같은 ID로 재요청 | 멱등성 처리 |

### 예시 구현

```java
@Component
@Profile("!production")
@Slf4j
public class FakePaymentGateway implements PaymentGateway {

    private final AtomicInteger callCount = new AtomicInteger(0);
    private final Random random = new Random();

    // 시뮬레이션 설정
    private int delayMs = 500;           // 기본 지연
    private double failureRate = 0.1;    // 10% 실패율
    private int consecutiveFailures = 0; // 연속 실패 횟수 (서킷 브레이커 테스트용)

    @Override
    public PaymentResult process(PaymentRequest request) {
        int currentCall = callCount.incrementAndGet();
        log.info("[Fake PG] 결제 요청 #{}: {}", currentCall, request);

        // 1. 지연 시뮬레이션
        simulateDelay();

        // 2. 실패 시뮬레이션
        if (shouldFail()) {
            log.warn("[Fake PG] 결제 실패 시뮬레이션");
            throw new PaymentGatewayException("PG사 일시적 오류");
        }

        // 3. 성공 응답
        String transactionId = "TXN-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("[Fake PG] 결제 성공: txId={}", transactionId);

        return new PaymentResult(transactionId, PaymentStatus.SUCCESS);
    }

    private void simulateDelay() {
        try {
            // 기본 지연 + 랜덤 변동 (±50%)
            int actualDelay = delayMs + random.nextInt(delayMs / 2);
            Thread.sleep(actualDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean shouldFail() {
        return random.nextDouble() < failureRate;
    }

    // 테스트용 설정 메서드
    public void setDelayMs(int delayMs) { this.delayMs = delayMs; }
    public void setFailureRate(double rate) { this.failureRate = rate; }
}
```

### 적용 서비스

| 서비스 | 시뮬레이션 대상 | 주요 학습 포인트 |
|--------|---------------|-----------------|
| **Payment Service** | PG사 API | 세마포어, 재시도, 멱등성 |
| **Notification Service** | SMS/이메일 API | 세마포어, 비동기 처리 |

### Profile 설정

```yaml
# application.yml
spring:
  profiles:
    active: local  # Fake 구현체 사용

---
# application-local.yml
payment:
  gateway:
    type: fake
    delay-ms: 500
    failure-rate: 0.1

---
# application-production.yml (향후 확장 시)
payment:
  gateway:
    type: real
    api-key: ${PG_API_KEY}
```

---

## D016. Core 라이브러리 전략

**결정**: 자체 개발 + JAR 배포 (최후 목표, Phase 3 완료 후)

### 배경

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Core 라이브러리 전략 (2026-01-25)                 │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [우선순위]                                                         │
│  └── 최후 목표 - Phase 1~3 학습 완료 후 진행                        │
│                                                                      │
│  [목적]                                                             │
│  ├── 개인 프로젝트용 공통 라이브러리 개발                           │
│  ├── 개발 편의 및 코딩 컨벤션 공통화                                │
│  └── JAR로 배포하여 다른 프로젝트에서 재사용                        │
│                                                                      │
│  [참조]                                                             │
│  ├── sonix_kingarthur core (패턴/구조 참고)                         │
│  └── spring-temporal-exam 학습 과정에서 도출된 패턴 기반            │
│                                                                      │
│  [개발 시점]                                                        │
│  └── Phase 3 완료 후, 반복 패턴 식별 및 추상화                      │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 모듈 구성

| 모듈 | 용도 | 우선순위 |
|------|------|----------|
| **core-lock** | RLock (분산락) + RSemaphore (세마포어) | 고도화 |
| **core-stream** | Redis Stream 추상화 | 고도화 |
| **core-observability** | 메트릭 표준화 (Micrometer) | 고도화 |

### 기술 스택

| 항목 | 버전 | 비고 |
|------|------|------|
| Spring Boot | 3.4.0 | kingarthur 동일 |
| Java | 21 | LTS |
| Redisson | 3.52.0 | Spring Boot 3.4 호환 |
| Micrometer | (Spring Boot 제공) | Prometheus 연동 |
| Gradle | 8.x+ | java-library 플러그인 |

### 배포 전략

```
┌─────────────────────────────────────────────────────────────────────┐
│                    배포 단계별 전략                                   │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [개발 단계] Maven Local                                            │
│  └── ./gradlew publishToMavenLocal                                  │
│                                                                      │
│  [안정화 후] GitHub Packages 또는 Maven Central                     │
│  ├── MIT 라이선스 적용                                              │
│  └── 오픈소스 공개                                                  │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 개발 순서

```
1. Phase 1~3 학습 완료 (핵심 목표)
   ├── Saga 패턴, 동시성 제어, MQ, Observability
   └── Temporal 연동까지 완료
   │
2. 학습 과정에서 반복 패턴 식별
   ├── 분산 락 사용 패턴
   ├── Redis Stream 처리 패턴
   └── 메트릭 수집 패턴
   │
3. Core 라이브러리 개발 (고도화)
   ├── core-lock: RLock + RSemaphore 추상화
   ├── core-stream: Redis Stream 추상화
   └── core-observability: 메트릭 표준화
   │
4. JAR 배포 설정 (publishToMavenLocal)
   │
5. 안정화 후 GitHub Packages 배포
```

### 프로젝트 구조 (예정)

```
spring-temporal-exam/
├── core/                          # Core 라이브러리
│   ├── core-lock/
│   ├── core-stream/
│   └── core-observability/
├── common/                        # 비즈니스 공통 (DTO, Event)
├── service-order/
├── service-inventory/
├── service-payment/
├── service-notification/
├── orchestrator-pure/
└── orchestrator-temporal/
```

### 참고: kingarthur core 모듈

| 모듈 | 기능 | 채택 여부 |
|------|------|----------|
| core-lock | 분산락 + 세마포어 | ✅ 채택 |
| core-stream | Redis Stream 추상화 | ✅ 채택 |
| core-observability | 메트릭 표준화 | ✅ 채택 |
| core-transaction | TransactionTemplate 추상화 | ❌ 직접 사용 |

---

## D017. 대기열 + 세마포어 조합 전략

**결정**: 트래픽 폭주 대응을 위해 Redis Queue + RSemaphore 조합 패턴 적용

### 배경

```
┌─────────────────────────────────────────────────────────────────────┐
│                    대기열 + 세마포어 조합 필요성                       │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [세마포어만 사용 시 문제]                                           │
│  ├── waitTime 내 permit 획득 실패 시 요청 거절 (503)                 │
│  ├── 트래픽 폭주 시 대량의 요청 실패                                 │
│  └── 사용자 경험 저하                                                │
│                                                                      │
│  [대기열 + 세마포어 조합 효과]                                       │
│  ├── 요청 거절 없이 버퍼링                                          │
│  ├── 외부 API Rate Limit 준수하며 순차 처리                         │
│  └── Temporal의 Task Queue + Worker 동작 원리 이해                  │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 적용 서비스

| 서비스 | 적용 | 이유 |
|--------|------|------|
| **Payment Service** | ✅ | PG API Rate Limit + 트래픽 폭주 대응 |
| Inventory Service | ❌ | 분산 락으로 충분, 즉시 응답 필요 |
| Order Service | ❌ | 동기 처리 필요 |
| Notification Service | ⚠️ | Phase 2-B Redis Stream에서 처리 |

### 구현 방식

```
┌─────────────────────────────────────────────────────────────────────┐
│                        아키텍처                                       │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [Producer]                                                         │
│  POST /payments → 대기열 적재 → 202 Accepted 반환                   │
│                                                                      │
│  [Consumer]                                                         │
│  대기열 폴링 → 세마포어 획득 → PG API 호출 → 결과 저장               │
│                                                                      │
│  [Client]                                                           │
│  GET /payments/{id}/status → 결과 조회 (폴링)                       │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Temporal과의 연관성

| 직접 구현 | Temporal |
|----------|----------|
| Redis List/Stream | Task Queue |
| RSemaphore | `maxConcurrentActivityExecutionSize` |
| Consumer Scheduler | Worker |
| 결과 저장/조회 | WorkflowClient Query |

**학습 의의**: 직접 구현의 복잡성을 체험한 후, Temporal이 이를 어떻게 자동화하는지 이해

### 관련 문서

- [04-1-queue-semaphore.md](../study/phase2a/04-1-queue-semaphore.md) - 대기열 + 세마포어 조합 학습

---

## D018. Temporal 보완 전략

**결정**: Phase 2 기술과 Temporal 조합 사용 (Temporal은 만능이 아님)

### 배경

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Temporal의 역할과 한계                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [Temporal이 해결하는 것 - 8가지]                                    │
│  ├── 상태 유실 → Event Sourcing                                     │
│  ├── 자동 재시도 → Retry Policy                                     │
│  ├── 중복 Workflow → Workflow ID 멱등성                             │
│  ├── Saga 보상 순서 → 내장 Saga 패턴                                │
│  ├── 순차 실행 → Workflow 내 Activity 순서 보장                     │
│  ├── At-least-once → Activity 재시도                                │
│  ├── 실행 이력 추적 → Event History                                 │
│  └── 타임아웃 처리 → Activity/Workflow 타임아웃                     │
│                                                                      │
│  [Temporal이 해결 못하는 것 - 6가지]                                 │
│  ├── 동시성 제어 → Workflow 외부 문제                               │
│  ├── 외부 서비스 멱등성 → 외부 시스템 영역                          │
│  ├── 최종 일관성 → 분산 시스템 근본 한계                            │
│  ├── 비즈니스 로직 → 개발자 영역                                    │
│  ├── 스키마 진화 → 데이터 영역                                      │
│  └── 테스트 복잡성 → 도구 한계                                      │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 보완 전략 매트릭스

| Temporal 미해결 문제 | 보완 기술 | Phase 2 학습 문서 |
|---------------------|----------|------------------|
| **동시성 제어** | 분산 락 (Redisson RLock), Atomic UPDATE | 04-distributed-lock |
| **외부 서비스 멱등성** | Idempotency Key 패턴 | 02-idempotency |
| **최종 일관성** | Saga 보상 + 재고 예약 패턴 | 01-saga-pattern |
| **비즈니스 로직** | Bean Validation + 도메인 검증 | 06-bean-validation |
| **스키마 진화** | Workflow.getVersion() + 하위 호환성 | 03-temporal-limitations |
| **테스트 복잡성** | TestWorkflowEnvironment + Testcontainers | - |

### 조합 패턴

```
┌─────────────────────────────────────────────────────────────────────┐
│                  Activity 내 보완 기술 적용 패턴                      │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  @Component                                                         │
│  public class InventoryActivitiesImpl implements InventoryActivities {
│                                                                      │
│      @Override                                                      │
│      public String reserveStock(String productId, int quantity) {   │
│          // 1. 분산 락으로 동시성 제어 (Temporal이 못 해주는 것)      │
│          RLock lock = redisson.getLock("inventory:" + productId);   │
│          try {                                                      │
│              if (lock.tryLock(5, 10, TimeUnit.SECONDS)) {          │
│                  // 2. 비즈니스 로직 검증                            │
│                  Inventory inv = inventoryRepository.findByProductId│
│                  if (inv.getQuantity() < quantity) {                │
│                      throw new InsufficientStockException();        │
│                  }                                                  │
│                  // 3. 원자적 업데이트                               │
│                  return doReserve(productId, quantity);             │
│              }                                                      │
│          } finally {                                                │
│              lock.unlock();                                         │
│          }                                                          │
│      }                                                              │
│  }                                                                  │
│                                                                      │
│  // Temporal의 역할: 이 Activity 실행 보장, 실패 시 재시도            │
│  // 분산 락의 역할: 동시 요청 간 충돌 방지                           │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 핵심 인사이트

```
┌─────────────────────────────────────────────────────────────────────┐
│                                                                      │
│  "Temporal은 실행 흐름의 안정성을 제공하지만,                         │
│   비즈니스 로직과 동시성 제어는 여전히 개발자 책임"                   │
│                                                                      │
│  Phase 2에서 배운 기술들은 Temporal 도입 후에도 필요하다:            │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────┐     │
│  │  Phase 2-A                    Temporal과 함께 사용          │     │
│  │  ─────────────────────────────────────────────────────────  │     │
│  │  분산 락 (Redisson)           Activity 내에서 동시성 제어   │     │
│  │  멱등성 Key                   외부 API 호출 시 필수         │     │
│  │  낙관적 락 (@Version)         DB 업데이트 충돌 감지         │     │
│  │  Saga 보상 패턴               Temporal Saga 클래스 활용     │     │
│  │  재고 예약 패턴               안전한 롤백 보장              │     │
│  └────────────────────────────────────────────────────────────┘     │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 의사결정 플로우

```
문제 발생 시:

Q1: 실행 흐름/상태 관리 문제?
    ├── YES → Temporal (Workflow, Activity, Retry)
    └── NO → Q2

Q2: 동시성/Race Condition 문제?
    ├── YES → 분산 락 또는 DB 락
    └── NO → Q3

Q3: 중복 요청/멱등성 문제?
    ├── YES → Idempotency Key 패턴
    └── NO → Q4

Q4: 일관성/정합성 문제?
    ├── YES → Saga + 예약 패턴 + Outbox
    └── NO → 비즈니스 로직 검토
```

### 관련 문서

| 문서 | 내용 |
|------|------|
| [00-problem-recognition.md](../study/phase2a/00-problem-recognition.md) | MSA/EDA 16가지 문제 인식 |
| [03-temporal-limitations.md](../study/phase3/03-temporal-limitations.md) | Temporal 한계와 보완 전략 상세 |

---

---

## D019. 테스트 전략 확장

**결정**: Contract Testing + Integration Testing 추가 (테스트 다이아몬드)

### 배경

```
┌─────────────────────────────────────────────────────────────────────┐
│                    테스트 피라미드 → 테스트 다이아몬드                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [기존 - 테스트 피라미드]                                            │
│  └── Unit Tests 많이, Integration 적게, E2E 최소                    │
│                                                                      │
│  [2025 트렌드 - 테스트 다이아몬드]                                   │
│  └── Netflix, Spotify 등 MSA 선도 기업 채택                         │
│  └── Integration + Contract Tests 중심                              │
│  └── Unit Tests는 기반, E2E는 핵심만                                │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 테스트 유형별 도구

| 유형 | 도구 | 용도 | 우선순위 |
|------|------|------|----------|
| **Contract Testing** | Pact, Spring Cloud Contract | 서비스 간 API 계약 검증 | 높음 |
| **Integration Testing** | Testcontainers | DB, Redis, MQ 연동 테스트 | 높음 |
| **Unit Testing** | JUnit 5, Mockito | 비즈니스 로직 검증 | 중간 |
| **E2E Testing** | REST Assured | 전체 플로우 검증 | 낮음 |

### Contract Testing 핵심

```
Consumer-Driven Contract:

1. Consumer (Orchestrator)가 계약 정의
   └── "Order Service는 POST /orders에 이 형식으로 응답해야 함"

2. Provider (Order Service)가 계약 검증
   └── 빌드 시 계약 충족 여부 자동 검증

3. 독립 배포 가능
   └── 계약만 지키면 서로 영향 없이 배포
```

### 관련 문서

- [10-contract-testing.md](../study/phase2a/10-contract-testing.md) - Contract Testing 학습

---

## D020. Saga Isolation 전략

**결정**: Semantic Lock + Reread Values 전략 적용

### 배경

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Saga 패턴의 Isolation 문제                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [Dirty Read 문제]                                                  │
│  ├── Saga A: 재고 100 → 90 예약 (진행 중)                            │
│  ├── Saga B: 재고 100 읽음 (A의 변경 전 값)                          │
│  └── Saga A 실패 롤백 → Saga B는 잘못된 데이터로 진행                │
│                                                                      │
│  [Lost Update 문제]                                                 │
│  ├── Saga A: 재고 100 → 90 예약                                     │
│  ├── Saga B: 재고 100 → 85 예약 (A 무시)                            │
│  └── 결과: 재고 부족인데 두 주문 모두 성공                           │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 해결 전략

| 전략 | 설명 | 구현 |
|------|------|------|
| **Semantic Lock** | 보상 가능 트랜잭션 중 "처리 중" 플래그 설정 | status = 'RESERVING' |
| **Reread Values** | 업데이트 전 데이터 변경 여부 재확인 | version 검증 |
| **Commutative Update** | 순서 무관하게 동일 결과 | 최종값 설정 방식 |

### 관련 문서

- [11-saga-isolation.md](../study/phase2a/11-saga-isolation.md) - Saga Isolation 심화

---

## D021. Redis 분산 락 심화 전략

**결정**: 10가지 함정 대응 + Redlock 고려

### 핵심 함정과 해결책

| # | 함정 | 해결책 |
|---|------|--------|
| 1 | SETNX + EXPIRE 비원자성 | SET key value NX EX seconds |
| 2 | Master-Slave 복제 문제 | Redlock 알고리즘 |
| 3 | 락 조기 만료 | Watch Dog 자동 연장 |
| 4 | 트랜잭션과 락 순서 | 커밋 후 락 해제 |
| 5 | Clock Drift | NTP 동기화 |
| 6 | 재진입 미지원 | Redisson RLock |
| 7 | 소유자 미검증 해제 | UUID 검증 |

### Redlock 적용 조건

```
┌─────────────────────────────────────────────────────────────────────┐
│  단일 Redis: RLock 충분                                              │
│  Redis Cluster/Sentinel: Redlock 고려                               │
│  학습 환경: RLock + Watch Dog (현재 선택)                            │
└─────────────────────────────────────────────────────────────────────┘
```

### 관련 문서

- [12-redis-lock-pitfalls.md](../study/phase2a/12-redis-lock-pitfalls.md) - 분산 락 함정과 해결책

---

## D022. 성능 테스트 전략

**결정**: k6 기반 부하 테스트

### 도구 선택

| 도구 | 특징 | 선택 이유 |
|------|------|----------|
| **k6** | JavaScript, 경량, Grafana 통합 | ✅ 현대적, CI/CD 친화적 |
| Gatling | Scala, 상세 리포트 | 대규모 엔터프라이즈 |
| JMeter | GUI, 범용 | QA 팀 중심 |

### 테스트 시나리오

```javascript
// Saga 부하 테스트
export const options = {
  stages: [
    { duration: '30s', target: 20 },   // Ramp up
    { duration: '1m', target: 100 },   // Peak
    { duration: '30s', target: 0 },    // Ramp down
  ],
};
```

### 관련 문서

- [09-performance-testing.md](../study/phase2b/09-performance-testing.md) - k6 부하 테스트

---

## D023. CI/CD 파이프라인 전략

**결정**: GitHub Actions + Docker

### 파이프라인 구성

```
┌─────────────────────────────────────────────────────────────────────┐
│                    CI/CD 파이프라인                                   │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  PR 생성/Push                                                        │
│       │                                                              │
│       ▼                                                              │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐                │
│  │  Build  │→│  Test   │→│ Contract│→│ Security│                   │
│  │ (Gradle)│  │(JUnit)  │  │  Test   │  │  Scan  │                  │
│  └─────────┘  └─────────┘  └─────────┘  └─────────┘                │
│                                              │                       │
│                                              ▼                       │
│                                    ┌─────────────────┐              │
│                                    │  Docker Build   │              │
│                                    │  + Push         │              │
│                                    └─────────────────┘              │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 보안 스캔 도구

| 도구 | 용도 |
|------|------|
| Trivy | 컨테이너 취약점 |
| CodeQL | 코드 보안 분석 |

### 관련 문서

- [01-github-actions.md](../study/devops/01-github-actions.md) - CI/CD 구축

---

## D024. 분산 추적 현대화

**결정**: Zipkin → Grafana Tempo 전환 고려

### 비교

| 항목 | Zipkin | Grafana Tempo |
|------|--------|---------------|
| 유지보수 | 자원봉사자 | Grafana Labs |
| 확장성 | 소규모 | 대규모 최적화 |
| 스토리지 | 다양한 백엔드 | 객체 스토리지 (S3) |
| 통합 | 독립적 | Grafana 스택 통합 |

### 결정 근거

```
현재 스택: Prometheus + Grafana + Loki
       → Tempo 추가 시 일관된 Observability 스택 완성
       → 단일 Grafana UI에서 Metrics + Logs + Traces 조회
```

### 관련 문서

- [05-opentelemetry-tempo.md](../study/phase2b/05-opentelemetry-tempo.md) - 분산 추적 (Tempo)

---

## D025. Virtual Threads 전략

**결정**: Spring Boot 3.5+ Virtual Threads 활성화

### 활성화 설정

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

### 효과

| 항목 | 개선 |
|------|------|
| I/O 바운드 | 성능 대폭 향상 |
| 스레드 풀 | 관리 간소화 |
| 동시 처리 | 수만 개 동시 요청 가능 |

### 주의사항

```
- synchronized 블록 주의 (pinning)
- ThreadLocal 사용 최소화
- 기존 스레드 풀 설정 검토 필요
```

---

## 관련 문서

- [MSA 아키텍처 선택 가이드](./MSA-ARCHITECTURE-GUIDE.md) - 환경별 아키텍처 비교
