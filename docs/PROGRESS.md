# 프로젝트 진행 현황

## 현재 상태

- **현재 Phase**: Phase 2-A - 동기 REST 기반 Saga
- **마지막 업데이트**: 2026-02-04
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
| 오후 | Resilience4j (재시도/타임아웃/서킷브레이커) | 03-resilience4j | ✅ 완료 |
| 저녁 | 분산 락 (RLock) + 세마포어 (RSemaphore) | 04-distributed-lock | ➡️ Day 2로 이월 (RLock 완료) |

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

**✅ Day 1 구현 완료 내역**:

| 구현 항목 | 파일 | 설명 |
|----------|------|------|
| PaymentGateway 인터페이스 | `service-payment/.../gateway/PaymentGateway.java` | 1단계/2단계 결제 패턴 지원 |
| FakePaymentGateway | `service-payment/.../gateway/FakePaymentGateway.java` | 지연/실패율 시뮬레이션 |
| @Idempotent 어노테이션 | `common/.../idempotency/Idempotent.java` | required 옵션 포함 (IETF 표준) |
| IdempotencyService | `common/.../idempotency/IdempotencyService.java` | Redis 기반 캐시 관리 |
| IdempotencyAspect | `common/.../idempotency/IdempotencyAspect.java` | AOP로 중복 요청 처리 |
| Resilience4j 설정 | `orchestrator-pure/.../application.yml` | Retry, CircuitBreaker 설정 |
| PaymentServiceClient | `orchestrator-pure/.../client/PaymentServiceClient.java` | @CircuitBreaker, @Retry 적용 |
| InventoryServiceClient | `orchestrator-pure/.../client/InventoryServiceClient.java` | @CircuitBreaker, @Retry 적용 |
| OrderServiceClient | `orchestrator-pure/.../client/OrderServiceClient.java` | @CircuitBreaker, @Retry 적용 |
| HTTP 테스트 파일 | `http/idempotency-test.http`, `http/resilience4j-test.http` | IntelliJ HTTP Client용 |

**학습 포인트 정리**:

*Step 1-2 (멱등성):*
- Idempotency Key는 **클라이언트(FE/호출 서버)가 생성** (업계 표준)
- `required=true`: Key 없으면 400 Bad Request (IETF 표준)
- 결제/주문 같은 중요 API는 Key **필수**로 설정

*Step 3 (Resilience4j):*
- **Retry**: 일시적 장애 자동 복구 (maxAttempts, waitDuration, exponentialBackoff)
- **CircuitBreaker**: 연쇄 장애 방지 (CLOSED → OPEN → HALF_OPEN → CLOSED)
- **Fallback**: 모든 재시도 실패 또는 서킷 OPEN 시 대체 처리
- 적용 순서: CircuitBreaker → Retry → 실제 호출 (OPEN이면 재시도 없이 즉시 실패)
- 결제 서비스는 더 민감한 설정 (failureRateThreshold=40%, waitDuration=30s)

---

### Day 2 - 2/3 (월) : Phase 2-A 심화 ★ 순서 재조정

> 목표: **Saga Isolation 문제 인식 → 해결책(락) 구현** 순서로 학습

| 순서 | 항목 | 학습 문서 | 구분 | 상태 |
|------|------|----------|------|------|
| 1 | 분산 락 (RLock) + Watchdog | 04-distributed-lock | 필수 | ✅ 완료 |
| 2 | **Saga Isolation 핵심** (Dirty Read, Lost Update) | 11-saga-isolation, 04-2-lock-strategy | 필수 | ✅ 완료 |
| 3 | 낙관적 락 (@Version) + GlobalExceptionHandler | 05-optimistic-lock | 필수 | ✅ 완료 |
| 4 | Semantic Lock 구현 | 04-2-lock-strategy | 필수 | ✅ 완료 |
| 5 | **Redis Lock 핵심 함정** ★ 보강 | 12-redis-lock-pitfalls | 필수 | ⬜ |
| 6 | 세마포어 (RSemaphore) - PG 호출 제한 | 04-distributed-lock | 필수 | ⬜ |
| 7 | 대기열 + 세마포어 조합 (버퍼링 패턴) | 04-1-queue-semaphore | ⭐선택 | ⬜ |

**Step 3 상세 (2026-02-03 완료)**:
- @Version 필드: ✅ 이미 구현됨 (Inventory, Order, Payment)
- OptimisticLockTest.java: ✅ 이미 존재 (service-inventory/src/test)
- SQL 로그 설정: ✅ application-local.yml에 설정됨
- GlobalExceptionHandler: ✅ **신규 생성 완료**
- RuntimeException → BusinessException 교체: ✅ 완료
- ComponentScan 추가: ✅ 완료 (Order, Inventory, Payment Application)

**🔄 순서 변경 이유**:
```
기존: 분산락 → 세마포어 → 대기열 → 낙관적락 → Saga Isolation → 함정
개선: 분산락 → Saga Isolation(문제인식) → 낙관적락 → Semantic Lock → 함정 → 세마포어

"왜 락이 필요한가?" 를 먼저 이해한 후 구현으로 진행
```

**핵심 학습 포인트**:
- **Saga는 ACD만 보장** (Isolation 없음) - 이 한계가 락 필요성의 근거
- 낙관적 락(@Version)으로 Lost Update 해결
- 분산 락(RLock)으로 다중 인스턴스 환경 해결
- Semantic Lock으로 빠른 응답 + 정보 제공
- 세마포어로 외부 API 동시 호출 제한
- **@Transactional + RLock 순서 주의** (핵심 함정)
- **예외 처리 계층화**: BusinessException(400) → OptimisticLock(409) → Exception(500)

**✅ Day 2 구현 완료 내역**:

| 구현 항목 | 파일 | 설명 |
|----------|------|------|
| 분산 락 헬퍼 메소드 | `service-inventory/.../service/InventoryService.java` | `executeWithLock()` - 중복 코드 제거 |
| RLock + Watchdog | `service-inventory/.../service/InventoryService.java` | `tryLock(5, TimeUnit.SECONDS)` - 자동 락 연장 |
| 4개 메소드 락 적용 | `service-inventory/.../service/InventoryService.java` | reserveStock, confirmReservation, cancelReservation, addStock |
| @Transactional(timeout=30) | `service-inventory/.../service/InventoryService.java` | Watchdog 무한 락 방지 안전장치 |
| 락 전략 통합 가이드 | `docs/study/phase2a/04-2-lock-strategy.md` | RLock + Semantic Lock + @Version 관계 정리 |
| Saga Isolation 문서 보강 | `docs/study/phase2a/11-saga-isolation.md` | Semantic Lock 실제 가치 섹션 추가 |
| 05-optimistic-lock 문서 보강 | `docs/study/phase2a/05-optimistic-lock.md` | 현재 구현 상태 섹션 추가 (2026-02-03) |
| GlobalExceptionHandler | `common/.../exception/GlobalExceptionHandler.java` | 전역 예외 처리 (BusinessException, OptimisticLock, 기타) |
| ErrorCode 확장 | `common/.../exception/ErrorCode.java` | LOCK_ACQUISITION_FAILED, SERVICE_UNAVAILABLE 등 추가 |
| RuntimeException 제거 | 각 ServiceClient, InventoryService | BusinessException으로 교체 (표준화된 에러 처리) |
| ComponentScan 추가 | Order/Inventory/Payment Application | GlobalExceptionHandler 스캔 설정 |
| **Semantic Lock 필드** | `service-inventory/.../entity/Inventory.java` | reservationStatus, sagaId, lockAcquiredAt |
| **ReservationStatus enum** | `service-inventory/.../entity/ReservationStatus.java` | AVAILABLE, RESERVING, RESERVED |
| **Semantic Lock 메소드** | `service-inventory/.../entity/Inventory.java` | acquireSemanticLock, releaseSemanticLockOnSuccess/Failure, validateSagaOwnership |
| **sagaId 전달** | `orchestrator-pure/.../saga/OrderSagaOrchestrator.java` | generateSagaId() + 모든 inventory 호출에 sagaId 전달 |
| **sagaId 파라미터** | `InventoryServiceClient, InventoryController` | cancelReservation에 sagaId 추가 |
| **DB 마이그레이션** | `V3__add_semantic_lock_fields.sql` | reservation_status, saga_id, lock_acquired_at 컬럼 |

**학습 포인트 정리**:

*Step 1 (분산 락 - RLock):*
- **tryLock(waitTime, TimeUnit)**: leaseTime 생략 시 Watchdog 자동 활성화
- **Watchdog**: 기본 30초 락 + 자동 연장 (10초마다 갱신)
- **@Transactional(timeout=30)**: Watchdog 무한 락 방지 안전장치
- **Thread.currentThread().interrupt()**: InterruptedException 후 인터럽트 플래그 복원 (graceful shutdown 지원)
- **lock.isHeldByCurrentThread()**: 다른 스레드 락 해제 방지
- **Runnable + Lambda**: 헬퍼 메소드로 중복 코드 제거 (`() -> { ... }`)
- **Effectively final**: 람다에서 접근하는 변수는 재할당 불가

*Step 2 (Saga Isolation + 락 전략):*
- **RLock 범위 설계가 핵심**: Saga 전체 vs 각 단계만
- **선택 A (RLock 전체)**: 단순하지만 블로킹 대기 (3초+), Semantic Lock 불필요
- **선택 B (RLock 최소)**: 빠른 응답, Semantic Lock 필요
- **Semantic Lock**: RLock이 없는 구간을 보호하기 위한 보완책
  - 처리량 향상 X, 빠른 응답 O, 비즈니스 로직 적용 O
  - "재고 부족" vs "다른 주문 처리 중" 정보 구분 가능
  - 반드시 RLock 안에서 설정/해제
- **낙관적 락 (@Version)**: RLock 실패 시 최후 방어선
- **세 가지 락의 역할**:
  - RLock: 동시 접근 차단 (물리적)
  - Semantic Lock: 작업 중 정보 제공 (논리적)
  - @Version: 충돌 감지 (최후 방어선)
- **업계 표준 일치 확인** (2026-02-03 웹 검색 검증):
  - Microsoft Azure Architecture: Semantic Lock = "application-level lock" countermeasure
  - microservices.io: Versioning, Reread Value, Semantic Lock 모두 표준 countermeasure
  - 학술 근거: 1998년 Lars Frank & Torben Zahle 논문
- **단일 Redis vs Redlock 판단**:
  - 현재: 단일 Redis + @Version (적절)
  - 이유: "효율성" 목적 (중복 방지), @Version이 최후 방어선
  - Redlock 필요 시: 금융 거래 등 "정확성" 필수 케이스

*Step 3 (낙관적 락 + GlobalExceptionHandler):*
- **예외 처리 계층 설계**:
  - BusinessException (400): 비즈니스 규칙 위반 (재고 부족, 주문 없음)
  - OptimisticLockingFailureException (409): 동시성 충돌 (클라이언트 재시도 필요)
  - Exception (500): 시스템 오류 (내부 메시지 숨김)
- **RuntimeException 핸들러 불필요 이유**:
  - BusinessException이 RuntimeException 하위 클래스
  - 인프라 오류는 500으로 처리하는 것이 적절
- **ErrorCode 표준화**: 모든 예외에 코드+메시지 구조 적용
- **ComponentScan 필요성**: common 모듈의 @RestControllerAdvice는 명시적 스캔 필요

*Step 4 (Semantic Lock):*
- **RLock 해제 ~ 트랜잭션 커밋 사이 GAP 보호**: 핵심 존재 이유
- **상태 전이**: AVAILABLE → RESERVING → RESERVED → AVAILABLE
  - RESERVING: 예약 작업 중 (RLock 내)
  - RESERVED: 예약 완료, 확정 대기 (RLock 해제 후)
- **sagaId**: Saga 실행마다 고유 ID 생성 (SAGA-XXXXXXXX 형식)
- **소유권 검증**: 다른 Saga가 작업 중인 리소스 접근 차단
- **버그 주의**: acquireSemanticLock()에서 RESERVED 상태도 체크 필수
  - RESERVING만 체크하면 예약 완료 후 ~ 확정 전 구간에서 다른 Saga 침범 가능

**📊 Day 2 현재 구현 상태 분석** (2026-02-03 코드 검토 완료):

| 항목 | 위치 | 상태 | 비고 |
|------|------|------|------|
| RLock + Watchdog | InventoryService | ✅ 완료 | executeWithLock() 헬퍼 |
| @Version 필드 | Inventory, Order, Payment 엔티티 | ✅ 있음 | 낙관적 락 구현됨 |
| OptimisticLockTest | service-inventory/src/test | ✅ 있음 | 동시 수정 테스트 코드 존재 |
| SQL 로그 설정 | application-local.yml | ✅ 있음 | show-sql: true, format_sql: true |
| Resilience4j | 각 ServiceClient | ✅ 완료 | Retry + CircuitBreaker |
| 멱등성 | IdempotencyService | ✅ 완료 | Redis 기반 |
| **GlobalExceptionHandler** | common/exception | ✅ 완료 | BusinessException, OptimisticLock 처리 |
| **Semantic Lock 필드** | Inventory 엔티티 | ✅ 완료 | reservationStatus, sagaId, lockAcquiredAt |
| 세마포어 | PaymentService | ❌ 없음 | PG 호출 제한 필요 |

**🔧 Day 2 남은 구현 작업**: (2026-02-03 재조정)

*Step 3 (낙관적 락 @Version):* ✅ 완료
```
[완료된 항목]
├── @Version 필드: Inventory, Order, Payment 엔티티 (기존)
├── OptimisticLockTest.java: service-inventory/src/test (기존)
├── SQL 로그 설정: application-local.yml (기존)
├── GlobalExceptionHandler.java 신규 생성
│   ├── BusinessException 처리 (400 Bad Request)
│   ├── OptimisticLockingFailureException 처리 (409 Conflict)
│   └── 기타 Exception 처리 (500 Internal Server Error)
├── ErrorCode 확장 (LOCK_ACQUISITION_FAILED, SERVICE_UNAVAILABLE 등)
├── RuntimeException → BusinessException 교체 (전체 MSA)
└── ComponentScan 추가 (Order, Inventory, Payment Application)
```

*Step 4 (Semantic Lock 구현):* ✅ 완료 (2026-02-04)
```
[완료된 항목]
├── V3__add_semantic_lock_fields.sql: reservation_status, saga_id, lock_acquired_at 컬럼
├── ReservationStatus enum: AVAILABLE, RESERVING, RESERVED
├── Inventory 엔티티 Semantic Lock 메소드:
│   ├── acquireSemanticLock(sagaId) - RESERVING/RESERVED 상태 체크 후 락 획득
│   ├── releaseSemanticLockOnSuccess(sagaId) - 성공 시 RESERVED로 전환
│   ├── releaseSemanticLockOnFailure(sagaId) - 실패 시 AVAILABLE로 복귀
│   ├── validateSagaOwnership(sagaId) - Saga 소유권 검증
│   └── clearSemanticLock() - 확정 시 완전 해제
├── InventoryService: sagaId 파라미터 사용 (reserveStock, confirmReservation, cancelReservation)
├── InventoryController: sagaId 필수 파라미터
├── InventoryServiceClient: cancelReservation에 sagaId 추가
└── OrderSagaOrchestrator: generateSagaId() + 모든 inventory 호출에 sagaId 전달

[핵심 버그 수정]
acquireSemanticLock()에서 RESERVING만 체크 → RESERVING + RESERVED 모두 체크
(RESERVED 상태에서 다른 Saga 접근 방지)
```

*Step 5 (Redis Lock 핵심 함정 - 보강됨):* ★ 중요
```
웹 검색 결과 발견된 핵심 함정 추가:

1. @Transactional + RLock 순서 문제 (★ 핵심)
   - Spring AOP가 트랜잭션을 먼저 시작
   - 락 해제 시점에 트랜잭션이 아직 커밋되지 않음
   - 해결: 락을 트랜잭션 밖에서 관리 (방법 1 권장)
   - 현재 코드: @Version이 방어하지만 개선 권장

2. 단일 Redis vs Redlock 판단 근거
   - 효율성 목적: 단일 Redis + @Version (현재)
   - 정확성 목적: Redlock 또는 Zookeeper/etcd

3. 기존 10가지 함정 요약 학습
```

*Step 6 (세마포어 구현 계획):*
```
1. PaymentService에 RSemaphore 적용:
   - semaphore:pg 키로 동시 10개 PG 호출 제한
   - tryAcquire(5, TimeUnit.SECONDS) 패턴

2. PaymentThrottledException 추가:
   - PG 호출 제한 초과 시 예외
```

---

### Day 3 - 2/4 (화) : Phase 2-A 완료 + 테스트 ★ 필수/선택 구분

> 목표: 서비스 간 계약 검증 + 디버깅 준비

| 순서 | 항목 | 학습 문서 | 구분 | 상태 |
|------|------|----------|------|------|
| 1 | MDC 로깅 (traceId 기본 설정) | 08-mdc-logging | 필수 | ⬜ |
| 2 | **Contract Testing** (Pact) | 10-contract-testing | 필수 | ⬜ |
| 3 | Bean Validation 입력 검증 | 06-bean-validation | ⭐선택 | ⬜ |
| 4 | 글로벌 예외 처리 | 07-exception-handling | ⭐선택 | ⬜ |
| 5 | TransactionTemplate (프로그래밍 방식) | 09-transaction-template | ⭐선택 | ⬜ |

**🔄 변경 사항**:
- MDC 로깅을 앞으로 이동 (분산 추적의 기본, 디버깅 필수)
- Bean Validation, 예외 처리, TransactionTemplate은 **선택** (Spring 기본 지식)
- Contract Testing은 **필수** (MSA 독립 배포의 핵심)

**핵심 학습 포인트**:
- 서비스 간 API 계약 검증으로 독립 배포 가능
- traceId로 분산 환경 요청 추적 준비

---

### Day 4 - 2/5 (수) : Phase 2-B 전반 ★ CDC 추가

> 목표: Redis 심화 + 이벤트 신뢰성 + CDC 체험

| 순서 | 항목 | 학습 문서 | 구분 | 상태 |
|------|------|----------|------|------|
| 1 | Redis 기초 (자료구조, 명령어) | 01-redis-basics | 필수 | ⬜ |
| 2 | Redis Stream (Consumer Group) | 02-redis-stream | ⭐선택 | ⬜ |
| 3 | Redisson 심화 (Pending List, Phantom Key) | 03-redisson | ⭐선택 | ⬜ |
| 4 | **Outbox 패턴 (Polling 방식)** | 04-outbox-pattern | 필수 | ⬜ |
| 5 | Notification 서비스 구현 | - | 필수 | ⬜ |
| 6 | **CDC (Debezium) 전환** | 04-1-cdc-debezium | ⭐선택 | ⬜ |

**🆕 CDC(Debezium) 학습 경로**:
```
[필수] Outbox 패턴 (Polling 방식)
   │
   │  "폴링의 한계 체험"
   │  - 주기적 SELECT 쿼리 부하
   │  - 폴링 주기만큼 지연
   │
   ▼
[선택] Debezium CDC 전환
   │
   │  "같은 문제, 다른 해결책"
   │  - MySQL binlog 실시간 캡처
   │  - Debezium Server + Redis Stream Sink
   │  - Outbox Event Router SMT
   │
   ▼
[비교] Polling vs CDC 장단점 이해
```

**핵심 학습 포인트**:
- Redis Stream이 Kafka 대안으로 어떻게 동작하는지
- Outbox 패턴이 분산 트랜잭션을 어떻게 보완하는지
- **CDC(Debezium)**: Polling의 한계 → binlog 기반 실시간 캡처

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

## 일정 요약 ★ 2026-02-03 재조정

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    7일 학습 일정 요약 (재조정됨)                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Day 1 (2/2 일): Phase 2-A 핵심 ✅                                          │
│  ├── 멱등성, Resilience4j, 분산 락(RLock)                                   │
│  └── "재시도의 전제조건" 이해                                                │
│                                                                             │
│  Day 2 (2/3 월): Phase 2-A 심화 ★ 순서 재조정 + 웹 검색 검증                │
│  ├── [필수] Saga Isolation → 낙관적 락 → Semantic Lock → Lock 함정 → 세마포어│
│  ├── 핵심: "락이 왜 필요한가?" 문제 인식 후 해결책 구현                      │
│  ├── 검증: 웹 검색으로 업계 표준 일치 확인 (Microsoft, microservices.io)    │
│  └── [선택] 대기열+세마포어 조합 (시간 여유 시)                              │
│                                                                             │
│  Day 3 (2/4 화): Phase 2-A 완료 + 테스트 ★ 필수/선택 구분                   │
│  ├── [필수] MDC 로깅, Contract Testing                                      │
│  └── [선택] Bean Validation, 예외 처리, TransactionTemplate                 │
│                                                                             │
│  Day 4 (2/5 수): Phase 2-B 전반                                             │
│  ├── Redis 기초, Stream, Redisson                                          │
│  └── Outbox 패턴, Notification 서비스                                       │
│                                                                             │
│  Day 5 (2/6 목): Phase 2-B 후반 (Observability)                             │
│  ├── OpenTelemetry + Grafana Tempo (분산 추적)                              │
│  └── [선택] Prometheus/Grafana, Loki, Alertmanager                         │
│                                                                             │
│  Day 6 (2/7 금): Phase 3 + DevOps                                           │
│  ├── Temporal 개념 + 인프라 + Spring 연동                                   │
│  └── GitHub Actions CI/CD                                                  │
│                                                                             │
│  Day 7 (2/8 토): Phase 3 완료 + 성능 테스트                                 │
│  ├── Saga → Temporal 전환, 한계 실습                                        │
│  └── k6 성능 테스트, [선택] Virtual Threads                                 │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 학습 경로 가이드: 필수 vs 선택 ★ 2026-02-03 재조정

> 시간 제약이 있을 때 우선순위를 명확히 하기 위한 분류입니다.

### 핵심 경로 (필수 - 19개 항목)

Temporal의 가치를 체감하기 위해 반드시 거쳐야 하는 학습 경로입니다.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    핵심 학습 경로 (재조정됨)                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Phase 1 (전체 필수)                                                        │
│  └── 01-gradle ~ 04-docker-compose (4개)                                    │
│                                                                             │
│  Phase 2-A (9개 필수) ★ 순서 재조정                                         │
│  ├── 00-problem-recognition ← MSA/EDA 문제 인식 종합                        │
│  ├── 01-saga-pattern        ← Saga 핵심 (오케스트레이션)                    │
│  ├── 02-idempotency         ← 재시도의 전제조건                             │
│  ├── 03-resilience4j        ← 재시도/타임아웃/서킷브레이커                  │
│  ├── 04-distributed-lock    ← 분산 락 + 세마포어                            │
│  ├── 11-saga-isolation      ← ★ 분산락 직후! (락 필요성 이해)               │
│  ├── 05-optimistic-lock     ← Lost Update 해결책                            │
│  ├── 08-mdc-logging         ← ★ 앞으로 이동 (디버깅 기본)                   │
│  └── 10-contract-testing    ← 서비스 간 계약 검증                           │
│                                                                             │
│  ⚠️ 12-redis-lock-pitfalls는 "심화"로 재분류 (핵심만 Day 2에서 학습)        │
│                                                                             │
│  Phase 2-B (4개 필수)                                                       │
│  ├── 01-redis-basics        ← Redis 기초 (다른 주제의 전제)                  │
│  ├── 04-outbox-pattern      ← 이벤트 발행 신뢰성 (중요!)                    │
│  ├── 05-opentelemetry-tempo ← 분산 추적 (Grafana 스택)                      │
│  └── 09-performance-testing ← k6 부하 테스트                                │
│                                                                             │
│  Phase 3 (전체 필수)                                                        │
│  ├── 01-temporal-concepts   ← Temporal 핵심 개념                            │
│  ├── 02-temporal-spring     ← Spring 연동 + Saga 전환                       │
│  └── 03-temporal-limitations← Temporal 한계와 보완 전략                     │
│                                                                             │
│  DevOps (1개 필수)                                                          │
│  └── 01-github-actions      ← CI/CD 파이프라인                              │
│                                                                             │
│  ═══════════════════════════════════════════════════════════════════════    │
│  총 19개 항목 → "MSA 어려움 → Temporal 해결 + 한계 인식" 체감               │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 선택 항목 (심화/부가 - 17개) ★ CDC 추가

기본 지식이 있거나 시간이 부족하면 건너뛸 수 있습니다.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    선택 학습 항목 (CDC 추가)                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Phase 2-A 선택 (5개)                                                       │
│  ├── 04-1-queue-semaphore    ← 대기열+세마포어 조합 (심화)                  │
│  ├── 06-bean-validation      ← 입력 검증 (Spring 기본 지식)                 │
│  ├── 07-exception-handling   ← 예외 처리 (Spring 기본 지식)                 │
│  ├── 09-transaction-template ← 트랜잭션 템플릿 (Spring 심화)                │
│  └── 12-redis-lock-pitfalls  ← 심화로 이동 (핵심만 Day 2에서)               │
│                                                                             │
│  Phase 2-B 선택 (6개) ★ CDC 추가                                            │
│  ├── 02-redis-stream         ← Redis Stream (MQ 구현)                       │
│  ├── 03-redisson             ← Redisson 심화                                │
│  ├── 04-1-cdc-debezium       ← 🆕 CDC (Polling → Debezium 전환)             │
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
│  CDC는 Outbox Polling 학습 후 "같은 문제, 다른 해결책" 비교 학습 권장        │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 보강된 학습 문서 목록 (신규 9개) ★ CDC 추가

| Phase | 문서 | 내용 | 우선순위 | 비고 |
|-------|------|------|----------|------|
| 2-A | `11-saga-isolation.md` | Saga Dirty Read, Lost Update | **필수** | ★ 분산락 직후 학습 |
| 2-A | `10-contract-testing.md` | Pact 기반 계약 테스트 | 필수 | |
| 2-A | `12-redis-lock-pitfalls.md` | 10가지 함정과 해결책 | 핵심만 필수 | 심화는 선택 |
| 2-B | `04-1-cdc-debezium.md` | 🆕 Polling → CDC 전환 | **선택** | Outbox 학습 후 |
| 2-B | `05-opentelemetry-tempo.md` | Zipkin → Grafana Tempo | 필수 | |
| 2-B | `09-performance-testing.md` | k6 부하 테스트 | 필수 | |
| DevOps | `01-github-actions.md` | CI/CD 파이프라인 | 필수 | |
| 고급 | `event-sourcing-cqrs.md` | 언제 쓰고 언제 안 쓰는지 | 선택 | |
| 고급 | `api-gateway-auth.md` | Spring Cloud Gateway + JWT | 선택 | |

---

## 학습 순서 (권장) ★ 2026-02-03 재조정

### Phase 2-A (동기 REST 기반 Saga) - 필수 9개 + 선택 5개

```
[필수 경로 - 문제 인식 → 해결책 순서]

00-problem-recognition → 01-saga-pattern → 02-idempotency → 03-resilience4j
→ 04-distributed-lock → 11-saga-isolation → 05-optimistic-lock
→ 08-mdc-logging → 10-contract-testing → 12-redis-lock-pitfalls

[선택 경로 - 시간 여유 시]
→ 04-1-queue-semaphore (심화)
→ 06-bean-validation, 07-exception-handling, 09-transaction-template (Spring 기본)
```

> **핵심 변경 (2026-02-03)**:
> - `11-saga-isolation`을 `04-distributed-lock` 직후로 이동 (락 필요성 이해)
> - `05-optimistic-lock`은 Saga Isolation 이후 (Lost Update 해결책으로)
> - `08-mdc-logging`을 앞으로 이동 (디버깅 기본)
> - Bean Validation, 예외 처리, TransactionTemplate은 **선택**으로 변경

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

## Phase 2-A: 동기 REST 기반 Saga ★ 순서 재조정

> **외부 서비스 시뮬레이션**: Fake 구현체 사용 ([D015 참조](./architecture/DECISIONS.md#d015-외부-서비스-시뮬레이션-전략))

### 진행 현황 (재조정된 순서)

| # | 항목 | 상태 | 학습 문서 | 구분 | 비고 |
|---|------|------|----------|------|------|
| 0 | MSA/EDA 문제 인식 종합 | ✅ 완료 | 00-problem-recognition | 필수 | |
| 1 | Saga 패턴 이해 + 서비스 도메인/API 설계 | ✅ 완료 | 01-saga-pattern | 필수 | |
| 2 | Fake PG 구현체 작성 | ✅ 완료 | [D015](./architecture/DECISIONS.md#d015) | 필수 | 1단계/2단계 결제 패턴 지원 |
| 3 | 오케스트레이터 REST 호출 구현 | ✅ 완료 | 01-saga-pattern | 필수 | |
| 4 | 보상 트랜잭션 구현 | ✅ 완료 | 01-saga-pattern | 필수 | |
| 5 | 멱등성 처리 (Idempotency Key) | ✅ 완료 | 02-idempotency | 필수 | AOP + Redis 기반 |
| 6 | Resilience4j 재시도/타임아웃 | ✅ 완료 | 03-resilience4j | 필수 | Retry + CircuitBreaker + Fallback |
| 7 | 재고 차감 분산 락 (RLock) | ✅ 완료 | 04-distributed-lock | 필수 | Watchdog + 헬퍼 메소드 |
| **8** | **Saga Isolation (Dirty Read, Lost Update)** | 대기 | 11-saga-isolation | **필수** | ★ 순서 변경: 분산락 직후 |
| **9** | **낙관적 락 (JPA @Version)** | 대기 | 05-optimistic-lock | **필수** | Lost Update 해결책 |
| 10 | PG 호출 제한 세마포어 (RSemaphore) | 대기 | 04-distributed-lock | 필수 | |
| 11 | Redis Lock 핵심 함정 (요약) | 대기 | 12-redis-lock-pitfalls | 필수 | 핵심만 학습 |
| 12 | MDC 로깅 (traceId) | 대기 | 08-mdc-logging | 필수 | ★ 앞으로 이동 |
| 13 | **Contract Testing** (Pact) | 대기 | 10-contract-testing | 필수 | 서비스 간 계약 |
| --- | --- 아래는 선택 항목 --- | --- | --- | --- | --- |
| 14 | 대기열 + 세마포어 조합 (버퍼링) | 대기 | 04-1-queue-semaphore | ⭐선택 | 심화 |
| 15 | Bean Validation 입력 검증 | 대기 | 06-bean-validation | ⭐선택 | Spring 기본 |
| 16 | 글로벌 예외 처리 | 대기 | 07-exception-handling | ⭐선택 | Spring 기본 |
| 17 | TransactionTemplate 적용 | 대기 | 09-transaction-template | ⭐선택 | Spring 심화 |
| 18 | Redis 분산 락 10가지 함정 (심화) | 대기 | 12-redis-lock-pitfalls | ⭐선택 | 전체 내용 |

---

## Phase 2-B: MQ + Redis + Observability ★ CDC 추가

> **외부 서비스 시뮬레이션**: Fake 구현체 사용 ([D015 참조](./architecture/DECISIONS.md#d015))

### 진행 현황 (필수/선택 구분)

| # | 항목 | 상태 | 학습 문서 | 구분 | 비고 |
|---|------|------|----------|------|------|
| 1 | Redis 기초 학습 | 대기 | 01-redis-basics | 필수 | |
| 2 | **Outbox 패턴 (Polling 방식)** | 대기 | 04-outbox-pattern | 필수 | 이중 쓰기 해결 |
| 3 | Notification 서비스 구현 | 대기 | - | 필수 | |
| 4 | Fake SMS/Email 구현체 작성 | 대기 | [D015](./architecture/DECISIONS.md#d015) | 필수 | |
| 5 | OpenTelemetry + **Grafana Tempo** | 대기 | 05-opentelemetry-tempo | 필수 | 분산 추적 |
| 6 | **k6 성능 테스트** | 대기 | 09-performance-testing | 필수 | |
| --- | --- 아래는 선택 항목 --- | --- | --- | --- | --- |
| 7 | Redis Stream 학습 | 대기 | 02-redis-stream | ⭐선택 | MQ 심화 |
| 8 | Redisson 심화 | 대기 | 03-redisson | ⭐선택 | |
| 9 | **🆕 CDC (Debezium) 전환** | 대기 | 04-1-cdc-debezium | ⭐선택 | Polling → CDC |
| 10 | Micrometer + Prometheus 연동 | 대기 | 06-prometheus-grafana | ⭐선택 | 운영 |
| 11 | Grafana 대시보드 구성 | 대기 | 06-prometheus-grafana | ⭐선택 | 운영 |
| 12 | Loki 로그 수집 연동 | 대기 | 07-loki | ⭐선택 | 운영 |
| 13 | Alertmanager 장애 알림 설정 | 대기 | 08-alertmanager | ⭐선택 | 운영 |

### 🆕 CDC(Debezium) 학습 가이드

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Outbox 패턴 학습 경로                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [Step 1] Polling 방식 구현 (필수)                                   │
│  ├── Outbox 테이블 설계 + Spring Scheduler                          │
│  ├── 이중 쓰기 문제 해결 원리 이해                                   │
│  └── 한계 체험: 폴링 주기 지연, DB 부하                              │
│                                                                      │
│  [Step 2] CDC 전환 (선택)                                            │
│  ├── MySQL binlog 활성화                                            │
│  ├── Debezium Server 설정 (Redis Stream Sink)                       │
│  ├── Outbox Event Router SMT 적용                                   │
│  └── Polling 코드 제거 → CDC로 대체                                  │
│                                                                      │
│  [비교] 같은 문제, 다른 해결책                                       │
│  ├── Polling: 단순, 추가 인프라 없음, 지연 있음                      │
│  └── CDC: 복잡, Debezium 필요, 실시간                                │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

**CDC 적용 시 추가 인프라**:
```yaml
# docker-compose.yml
services:
  debezium:
    image: debezium/server:3.2
    environment:
      - DEBEZIUM_SINK_TYPE=redis
      - DEBEZIUM_SINK_REDIS_ADDRESS=redis:6379
    depends_on: [mysql, redis]
```

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
