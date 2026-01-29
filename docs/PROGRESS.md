# 프로젝트 진행 현황

## 현재 상태

- **현재 Phase**: Phase 2-A - 동기 REST 기반 Saga
- **마지막 업데이트**: 2026-01-30
- **Spring Boot**: 3.5.9
- **목표 완료일**: 2026-02-01 (일)

---

## 집중 일정 (4일 완료 목표)

> 기간: 2026-01-29 (목) ~ 2026-02-01 (일)

### Day 1 - 1/29 (목) : Phase 1 완료

| 시간 | 항목 | 상태 |
|------|------|------|
| 오전 | Docker Compose 인프라 구성 | ✅ |
| 점심 | Flyway DB 마이그레이션 | ✅ |
| 오후 | Spring Profiles, 데이터 모델 설계 | ✅ |
| 저녁 | 서비스 스켈레톤 생성 | ✅ |

### Day 2 - 1/30 (금) : Phase 2-A 전반

| 시간 | 항목 | 상태 |
|------|------|------|
| 오전 | Saga 패턴 이해, 서비스 API 설계 | ✅ |
| 점심 | Fake PG 구현 | ⬜ |
| 오후 | 오케스트레이터 REST 호출, 보상 트랜잭션 | ✅ |
| 저녁 | 멱등성 처리, Resilience4j | ⬜ |

### Day 3 - 1/31 (토) : Phase 2-A 완료 + Phase 2-B

| 시간 | 항목 | 상태 |
|------|------|------|
| 오전 | 분산 락, 낙관적 락, Bean Validation | ⬜ |
| 점심 | 예외 처리, MDC 로깅, TransactionTemplate | ⬜ |
| 오후 | Redis 기초, Redis Stream, Redisson | ⬜ |
| 저녁 | Notification 서비스, Outbox 패턴 | ⬜ |

### Day 4 - 2/1 (일) : Phase 2-B 완료 + Phase 3

| 시간 | 항목 | 상태 |
|------|------|------|
| 오전 | OpenTelemetry/Zipkin | ⬜ |
| 점심 | Prometheus/Grafana, Loki, Alertmanager | ⬜ |
| 오후 | Temporal 개념 + 로컬 인프라 + Spring 연동 | ⬜ |
| 저녁 | Workflow/Activity, Saga → Temporal 전환 | ⬜ |

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
| 3 | Docker Compose 인프라 구성 | ✅ 완료 | 04-docker-compose |
| 4 | Flyway DB 마이그레이션 설정 | ✅ 완료 | 02-flyway |
| 5 | Spring Profiles 환경별 설정 | ✅ 완료 | 03-spring-profiles |
| 6 | 데이터 모델 설계 | ✅ 완료 | - |
| 7 | 각 서비스 모듈 스켈레톤 생성 | ✅ 완료 | - |

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

**Step 3: Docker Compose 인프라 구성 (2026-01-29)**

| 단계 | 항목 | 상태 |
|------|------|------|
| 3-1 | docker-compose.yml 생성 | ✅ 완료 |
| 3-2 | MySQL 컨테이너 설정 (healthcheck 포함) | ✅ 완료 |
| 3-3 | Redis 컨테이너 설정 (healthcheck 포함) | ✅ 완료 |
| 3-4 | init.sql (DB 초기화 스크립트) | ✅ 완료 |
| 3-5 | 연결 테스트 (MySQL, Redis) | ✅ 완료 |

**인프라 구성:**
| 서비스 | 이미지 | 호스트 포트 | 용도 |
|--------|--------|-------------|------|
| MySQL | mysql:8.0 | 21306 | 데이터베이스 |
| Redis | redis:7-alpine | 21379 | 캐시, 분산 락, MQ |

**생성된 데이터베이스:**
- `order_db` - 주문 서비스
- `inventory_db` - 재고 서비스
- `payment_db` - 결제 서비스

**학습 메모:**
- Docker Healthcheck 개념 심화 학습 (04-docker-compose.md 문서 업데이트)
- `depends_on` + `condition: service_healthy` 조합으로 서비스 시작 순서 보장
- 포트 충돌 시 호스트 포트 변경으로 해결 (22xxx → 21xxx)

**Step 4: Flyway DB 마이그레이션 설정 (2026-01-29)**

| 단계 | 항목 | 상태 |
|------|------|------|
| 4-1 | service-order에 Flyway 의존성 추가 | ✅ 완료 |
| 4-2 | application.yml 생성 (datasource, jpa, flyway) | ✅ 완료 |
| 4-3 | db/migration 폴더 생성 | ✅ 완료 |
| 4-4 | V1__create_orders_table.sql 작성 | ✅ 완료 |
| 4-5 | V2__create_order_items_table.sql 작성 | ✅ 완료 |
| 4-6 | 마이그레이션 실행 및 테이블 생성 확인 | ✅ 완료 |
| 4-7 | Flyway 로깅 설정 추가 | ✅ 완료 |

**생성된 테이블 (order_db):**
| 테이블 | 용도 |
|--------|------|
| orders | 주문 |
| order_items | 주문 상품 (orders와 1:N) |
| flyway_schema_history | Flyway 마이그레이션 이력 |

**학습 메모:**
- `ddl-auto: validate` - Flyway가 DDL 관리, Hibernate는 검증만
- `baseline-on-migrate: true` - 기존 DB에 Flyway 최초 적용 시 필요
- MySQL InnoDB에서 FK 선언 시 인덱스 자동 생성 → 중복 인덱스 불필요
- 파일명 규칙: `V{버전}__{설명}.sql` (언더스코어 2개 필수)

**Step 5: Spring Profiles 환경별 설정 (2026-01-29)**

| 단계 | 항목 | 상태 |
|------|------|------|
| 5-1 | application.yml 공통 설정으로 리팩토링 | ✅ 완료 |
| 5-2 | application-local.yml 생성 (로컬 환경) | ✅ 완료 |
| 5-3 | Profile 활성화 확인 | ✅ 완료 |

**설정 파일 구조 (service-order):**
| 파일 | 용도 |
|------|------|
| application.yml | 공통 설정 (포트, JPA 기본, Flyway) |
| application-local.yml | 로컬 환경 (DB 접속, 로깅 레벨) |

**학습 메모:**
- `${SPRING_PROFILES_ACTIVE:local}` - 환경변수 없으면 local 기본값
- 공통 설정 로드 → 활성 Profile 설정으로 덮어씀
- `open-in-view: false` - OSIV 비활성화 (성능 best practice)
- `default_batch_fetch_size: 100` - N+1 문제 완화

**Step 6: 데이터 모델 설계 (2026-01-29)**

| 단계 | 항목 | 상태 |
|------|------|------|
| 6-1 | service-inventory Flyway + Profiles 설정 | ✅ 완료 |
| 6-2 | service-payment Flyway + Profiles 설정 | ✅ 완료 |
| 6-3 | inventory_db 테이블 생성 (products, inventories) | ✅ 완료 |
| 6-4 | payment_db 테이블 생성 (payments) | ✅ 완료 |

**서비스별 포트:**
| 서비스 | 포트 | DB |
|--------|------|-----|
| service-order | 8081 | order_db |
| service-inventory | 8082 | inventory_db |
| service-payment | 8083 | payment_db |

**생성된 테이블:**
| DB | 테이블 | 용도 |
|----|--------|------|
| order_db | orders | 주문 |
| order_db | order_items | 주문 상품 (orders 1:N) |
| inventory_db | products | 상품 마스터 |
| inventory_db | inventories | 재고 수량 (products 1:1) |
| payment_db | payments | 결제 정보 |

**학습 메모:**
- MSA에서 서비스 간 FK 없음 (DB 독립성 원칙)
- `payments.order_id`는 논리적 참조 (값만 저장, 애플리케이션에서 정합성 보장)
- `version` 컬럼 - 낙관적 락용 (Phase 2-A에서 학습)
- `reserved_quantity` - Saga 패턴에서 재고 예약용

**Step 7: 각 서비스 모듈 스켈레톤 생성 (2026-01-29)**

| 단계 | 항목 | 상태 |
|------|------|------|
| 7-1 | service-order Entity 생성 (Order, OrderItem, OrderStatus) | ✅ 완료 |
| 7-2 | service-order Repository, Service, Controller 생성 | ✅ 완료 |
| 7-3 | service-inventory Entity 생성 (Product, Inventory) | ✅ 완료 |
| 7-4 | service-inventory Repository, Service, Controller 생성 | ✅ 완료 |
| 7-5 | service-payment Entity 생성 (Payment, PaymentStatus) | ✅ 완료 |
| 7-6 | service-payment Repository, Service, Controller 생성 | ✅ 완료 |
| 7-7 | 코드 검토 및 컴파일 오류 수정 | ✅ 완료 |

**서비스별 생성된 클래스:**

| 서비스 | Entity | Repository | Service | Controller |
|--------|--------|------------|---------|------------|
| order | Order, OrderItem, OrderStatus | OrderRepository | OrderService | OrderController |
| inventory | Product, Inventory | ProductRepository, InventoryRepository | InventoryService | InventoryController |
| payment | Payment, PaymentStatus | PaymentRepository | PaymentService | PaymentController |

**Saga 패턴 준비 메서드:**

| 서비스 | 메서드 | 용도 |
|--------|--------|------|
| inventory | reserve() | 재고 예약 (Saga Step) |
| inventory | confirmReservation() | 예약 확정 (결제 완료 후) |
| inventory | cancelReservation() | 예약 취소 (보상 트랜잭션) |
| payment | approve() | 결제 승인 |
| payment | confirm() | 결제 확정 |
| payment | refund() | 환불 (보상 트랜잭션) |
| order | confirmOrder() | 주문 확정 |
| order | cancelOrder() | 주문 취소 |

**학습 메모:**
- JPA 관계 매핑: `@ManyToOne(fetch = LAZY)` - 성능 최적화
- 낙관적 락: `@Version` - 동시성 제어 (Phase 2-A에서 활용)
- BusinessException 사용법: `ErrorCode.XXX.toErrorInfo()` 패턴
- Entity 메서드로 비즈니스 로직 캡슐화 (DDD 접근)

**Phase 1 완료!** 🎉

---

### Phase 2-A 상세 진행 (2026-01-30)

**Step 1: Saga 패턴 이해 + 서비스 API 설계**

| 단계 | 항목 | 상태 |
|------|------|------|
| 1-1 | Saga 패턴 개념 학습 (Orchestration vs Choreography) | ✅ 완료 |
| 1-2 | 즉시 차감 vs 예약 패턴 이해 | ✅ 완료 |
| 1-3 | 정방향/보상 트랜잭션 개념 이해 | ✅ 완료 |
| 1-4 | 각 서비스 Saga API 엔드포인트 점검 | ✅ 완료 |
| 1-5 | Orchestrator DTO 생성 (Request/Result) | ✅ 완료 |
| 1-6 | Service Client 생성 (RestClient 사용) | ✅ 완료 |

**학습 내용:**
- 즉시 차감 방식의 5가지 문제점 (결제 실패 시 원복 불가, 동시성, 부분 실패, 멱등성, 장애 복구)
- 예약(Reserve) 패턴으로 문제 해결
- 정방향 = 비즈니스 목표 달성 순서, 보상 = 역순으로 되돌림
- RestTemplate → RestClient 마이그레이션 (Spring Boot 3.2+ 권장)

**생성된 파일 (orchestrator-pure):**

| 경로 | 파일 | 역할 |
|------|------|------|
| dto/ | OrderSagaRequest.java | Saga 요청 DTO |
| dto/ | OrderSagaResult.java | Saga 결과 DTO |
| client/ | OrderServiceClient.java | Order 서비스 호출 |
| client/ | InventoryServiceClient.java | Inventory 서비스 호출 |
| client/ | PaymentServiceClient.java | Payment 서비스 호출 |
| config/ | RestClientConfig.java | RestClient Bean 등록 |

**기술 선택:**
- HTTP Client: RestClient (Spring 6.1+, RestTemplate deprecated 예정)
- URI 템플릿 변수 바인딩으로 가독성 향상

**Step 2: Saga Orchestrator 구현**

| 단계 | 항목 | 상태 |
|------|------|------|
| 2-1 | OrderSagaOrchestrator 구현 (정방향 T1~T6) | ✅ 완료 |
| 2-2 | 보상 트랜잭션 구현 (역순 C3~C1) | ✅ 완료 |
| 2-3 | SagaController API 엔드포인트 생성 | ✅ 완료 |
| 2-4 | application.yml 설정 (port 8080) | ✅ 완료 |
| 2-5 | HTTP 테스트 파일 작성 (IntelliJ) | ✅ 완료 |
| 2-6 | Jackson 역직렬화 오류 수정 | ✅ 완료 |

**Saga Flow:**
```
정방향 (성공 시):
T1: 주문 생성 → T2: 재고 예약 → T3: 결제 생성/승인
→ T4: 주문 확정 → T5: 재고 확정 → T6: 결제 확정

보상 (실패 시, 역순):
C3: 결제 환불 ← C2: 재고 예약 취소 ← C1: 주문 취소
```

**생성된 파일:**

| 경로 | 파일 | 역할 |
|------|------|------|
| saga/ | OrderSagaOrchestrator.java | Saga 핵심 로직 (정방향 + 보상) |
| controller/ | SagaController.java | POST /api/saga/order |
| resources/ | application.yml | 포트 8080, 로깅 설정 |
| httptest/ | *.http | IntelliJ HTTP Client 테스트 |

**서비스 포트 정리:**

| 서비스 | 포트 |
|--------|------|
| orchestrator-pure | 8080 |
| service-order | 8081 |
| service-inventory | 8082 |
| service-payment | 8083 |

**트러블슈팅:**
- Jackson 역직렬화 오류: `ApiResponse`, `ErrorInfo`에 `@Setter` 추가 필요
- DTO에 `@NoArgsConstructor` + `@Setter` 조합으로 해결

---

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
| 1 | Saga 패턴 이해 + 서비스 도메인/API 설계 | ✅ 완료 | 01-saga-pattern |
| 2 | Fake PG 구현체 작성 | 대기 | [D015](./architecture/DECISIONS.md#d015-외부-서비스-시뮬레이션-전략) |
| 3 | 오케스트레이터 REST 호출 구현 | ✅ 완료 | 01-saga-pattern |
| 4 | 보상 트랜잭션 구현 | ✅ 완료 | 01-saga-pattern |
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
