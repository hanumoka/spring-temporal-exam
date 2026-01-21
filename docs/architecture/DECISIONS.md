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
| D011 | ORM 전략 | JPA + MyBatis 병행 학습 |
| D012 | 트랜잭션 관리 | TransactionTemplate (프로그래밍 방식) |

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

**결정**: JPA + MyBatis 병행 학습

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
| [04-optimistic-lock.md](../study/phase2a/04-optimistic-lock.md) | 낙관적 락 구현 | `WHERE version = ?`, CAS 패턴 |
| [05-idempotency.md](../study/phase2a/05-idempotency.md) | 멱등성 보장 | `INSERT IGNORE`, `ON DUPLICATE KEY UPDATE` |
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

## 관련 문서

- [MSA 아키텍처 선택 가이드](./MSA-ARCHITECTURE-GUIDE.md) - 환경별 아키텍처 비교
