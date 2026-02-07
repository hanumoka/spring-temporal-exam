# Temporal Activity 설계 완벽 가이드

> **목적**: Temporal이 해결하지 못하는 문제들을 Activity 레벨에서 어떻게 설계하고 구현해야 하는지 상세 가이드
> **대상**: Temporal 기본 개념을 이해한 개발자
> **전제**: Phase 2-A의 분산 락, 멱등성 개념 학습 완료

---

## 목차

1. [핵심 원칙: Temporal + Activity 책임 분리](#1-핵심-원칙-temporal--activity-책임-분리)
2. [멱등성 설계 완벽 가이드](#2-멱등성-설계-완벽-가이드)
3. [동시성 제어 설계 완벽 가이드](#3-동시성-제어-설계-완벽-가이드)
4. [Saga 격리 문제와 해결](#4-saga-격리-문제와-해결)
5. [서킷 브레이커와 Temporal 조합](#5-서킷-브레이커와-temporal-조합)
6. [실전 Activity 구현 템플릿](#6-실전-activity-구현-템플릿)
7. [체크리스트와 안티패턴](#7-체크리스트와-안티패턴)
8. [Outbox 패턴과 Kafka 이벤트 발행](#8-outbox-패턴과-kafka-이벤트-발행)

---

## 1. 핵심 원칙: Temporal + Activity 책임 분리

### 1.1 책임 분리 다이어그램

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      Temporal vs Activity 책임 분리                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                     Temporal Server 영역                               │  │
│  │                                                                        │  │
│  │   ✅ Workflow 실행 순서 보장                                          │  │
│  │   ✅ Activity 재시도 (Retry Policy)                                   │  │
│  │   ✅ 상태 저장 및 복구 (Event History)                                │  │
│  │   ✅ Saga 보상 순서 관리                                              │  │
│  │   ✅ Workflow ID 기반 중복 시작 방지                                   │  │
│  │   ✅ 타임아웃 관리                                                    │  │
│  │                                                                        │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                      │                                       │
│                                      │ Activity 호출                         │
│                                      ▼                                       │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                     Activity 구현체 영역 (개발자 책임)                  │  │
│  │                                                                        │  │
│  │   ❌ Temporal이 안 해줌 → 개발자가 직접 구현                           │  │
│  │                                                                        │  │
│  │   ┌─────────────────────────────────────────────────────────────────┐ │  │
│  │   │  1. 멱등성 (Idempotency)                                        │ │  │
│  │   │     - 같은 Activity가 여러 번 실행되어도 결과가 같아야 함         │ │  │
│  │   │     - 이중 결제, 이중 예약 방지                                  │ │  │
│  │   └─────────────────────────────────────────────────────────────────┘ │  │
│  │                                                                        │  │
│  │   ┌─────────────────────────────────────────────────────────────────┐ │  │
│  │   │  2. 동시성 제어 (Concurrency Control)                           │ │  │
│  │   │     - 여러 Workflow가 같은 리소스에 동시 접근 시 충돌 방지        │ │  │
│  │   │     - Race Condition, 오버셀링 방지                              │ │  │
│  │   └─────────────────────────────────────────────────────────────────┘ │  │
│  │                                                                        │  │
│  │   ┌─────────────────────────────────────────────────────────────────┐ │  │
│  │   │  3. 데이터 정합성 (Data Consistency)                            │ │  │
│  │   │     - Saga 중간 상태 격리                                        │ │  │
│  │   │     - Dirty Read / Lost Update 방지                              │ │  │
│  │   └─────────────────────────────────────────────────────────────────┘ │  │
│  │                                                                        │  │
│  │   ┌─────────────────────────────────────────────────────────────────┐ │  │
│  │   │  4. 비즈니스 검증 (Business Validation)                         │ │  │
│  │   │     - 도메인 규칙 검증                                           │ │  │
│  │   │     - 입력값 검증                                                │ │  │
│  │   └─────────────────────────────────────────────────────────────────┘ │  │
│  │                                                                        │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 왜 Temporal이 이것들을 해결 못 하는가?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         근본적인 이유                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Temporal의 관점:                                                           │
│  ─────────────────                                                          │
│                                                                              │
│  "나는 Workflow A가 Activity X를 호출하도록 했고,                            │
│   Activity X가 실패하면 재시도하도록 설정했어.                               │
│   Activity X 내부에서 뭘 하는지는 내가 알 바 아니야."                         │
│                                                                              │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                                                                        │  │
│  │  Temporal Server                                                       │  │
│  │       │                                                                │  │
│  │       │  "Activity X 실행해!"                                          │  │
│  │       ▼                                                                │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │                    Activity X (블랙박스)                         │  │  │
│  │  │                                                                  │  │  │
│  │  │   ┌───────────────────────────────────────────────────────────┐ │  │  │
│  │  │   │  결제 API 호출? DB 업데이트? 외부 서비스 호출?             │ │  │  │
│  │  │   │  Temporal은 모름! 개발자가 뭘 작성했는지 모름!             │ │  │  │
│  │  │   └───────────────────────────────────────────────────────────┘ │  │  │
│  │  │                                                                  │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  │       │                                                                │  │
│  │       │  "성공" 또는 "실패"                                            │  │
│  │       ▼                                                                │  │
│  │  Temporal: "실패했네, 재시도할게!"                                     │  │
│  │            (Activity 내부에서 이미 결제가 됐는지는 모름)                │  │
│  │                                                                        │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  결론:                                                                      │
│  ─────                                                                      │
│  • Temporal은 "호출"과 "재시도"만 담당                                      │
│  • Activity 내부 로직의 "안전성"은 개발자 책임                               │
│  • 각 Workflow는 서로의 존재를 모름 (동시성 제어 불가)                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. 멱등성 설계 완벽 가이드

### 2.1 멱등성이 필요한 시나리오

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Activity 재시도 시 발생하는 문제                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  [시나리오] 결제 Activity 실행 중 네트워크 타임아웃                          │
│                                                                              │
│  시간 ────────────────────────────────────────────────────────────────▶     │
│                                                                              │
│  Worker                                결제 서비스                           │
│    │                                        │                                │
│    │ ─── 1. 결제 요청 ───────────────────▶ │                                │
│    │     (100,000원, orderId: "O-123")     │                                │
│    │                                        │                                │
│    │                                        │ 2. 결제 처리                   │
│    │                                        │    (카드사 승인 완료)          │
│    │                                        │    DB: 결제 상태 = "완료"      │
│    │                                        │                                │
│    │ ◀── 3. 응답 반환 중... ─────────────── │                                │
│    │           │                            │                                │
│    │           💥 네트워크 끊김!            │                                │
│    │                                        │                                │
│    │ [Temporal 판단]                        │                                │
│    │ "타임아웃! Activity 실패로 간주"       │                                │
│    │ "RetryOptions에 따라 재시도!"          │                                │
│    │                                        │                                │
│    │ ─── 4. 결제 요청 (재시도) ───────────▶ │                                │
│    │     (100,000원, orderId: "O-123")     │                                │
│    │                                        │                                │
│    │                                        │ 5. 또 결제 처리???             │
│    │                                        │                                │
│  ══════════════════════════════════════════════════════════════════════════ │
│                                                                              │
│  멱등성 없이:                                                               │
│  → 200,000원 이중 결제! 고객 클레임!                                        │
│                                                                              │
│  멱등성 있으면:                                                             │
│  → "이미 처리됨" 반환, 정상 진행                                            │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Idempotency Key 설계 원칙

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     Idempotency Key 설계 5가지 원칙                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  원칙 1: 유일성 (Uniqueness)                                                │
│  ══════════════════════════                                                 │
│  • 같은 "논리적 작업"에 대해 항상 같은 키                                    │
│  • 다른 "논리적 작업"에 대해 항상 다른 키                                    │
│                                                                              │
│  ✅ 좋은 예: "payment-{orderId}-{attemptNumber}"                            │
│  ❌ 나쁜 예: UUID.randomUUID() (매번 달라짐!)                               │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  원칙 2: 결정성 (Determinism)                                               │
│  ════════════════════════════                                               │
│  • 같은 입력이면 항상 같은 키가 생성되어야 함                                │
│  • 시간, 랜덤 값에 의존하면 안 됨                                            │
│                                                                              │
│  ✅ 좋은 예: orderId + productId + quantity                                 │
│  ❌ 나쁜 예: orderId + System.currentTimeMillis()                           │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  원칙 3: 범위 한정 (Scoping)                                                │
│  ═══════════════════════════                                                │
│  • 키의 범위를 명확히 정의                                                   │
│  • 너무 넓으면: 다른 요청이 막힘                                            │
│  • 너무 좁으면: 중복 방지 안 됨                                             │
│                                                                              │
│  ✅ 좋은 예: "reserve-{productId}-{orderId}"                                │
│             (주문별로 상품 예약 1회)                                         │
│  ❌ 나쁜 예: "reserve-{productId}"                                          │
│             (모든 주문이 하나의 키 공유!)                                    │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  원칙 4: TTL 관리 (Time-To-Live)                                            │
│  ════════════════════════════════                                           │
│  • 멱등성 키를 영원히 저장? → 스토리지 낭비                                  │
│  • 너무 빨리 삭제? → 지연된 재시도 시 중복 처리                              │
│                                                                              │
│  권장: Activity 타임아웃 + 여유 시간                                        │
│  예) Activity timeout 5분 → 키 TTL 10분                                    │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  원칙 5: 결과 저장 (Result Caching)                                         │
│  ══════════════════════════════════                                         │
│  • 처리 결과를 키와 함께 저장                                               │
│  • 재시도 시 저장된 결과 반환                                               │
│                                                                              │
│  저장 내용:                                                                  │
│  {                                                                          │
│    "key": "payment-order123-1",                                             │
│    "result": { "paymentId": "PAY-456", "status": "SUCCESS" },               │
│    "createdAt": "2026-02-07T10:00:00",                                      │
│    "expiresAt": "2026-02-07T10:10:00"                                       │
│  }                                                                          │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.3 Temporal에서 Idempotency Key 생성 전략

```java
/**
 * Temporal Activity에서 Idempotency Key를 생성하는 3가지 전략
 */
public class IdempotencyKeyStrategies {

    // ═══════════════════════════════════════════════════════════════════════
    // 전략 1: Workflow ID + Activity ID 조합 (권장)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Temporal이 제공하는 컨텍스트 정보 활용
     *
     * 장점:
     * - Workflow 재시작해도 같은 키 유지
     * - Activity 재시도마다 같은 키
     * - Temporal이 관리하는 ID 사용으로 충돌 없음
     */
    public String strategy1_WorkflowActivityId(String businessKey) {
        ActivityExecutionContext ctx = Activity.getExecutionContext();
        ActivityInfo info = ctx.getInfo();

        // Workflow ID: "order-O123" (Workflow 시작 시 지정)
        // Activity ID: Temporal이 자동 부여 (Workflow 내에서 유일)
        return String.format("%s-%s-%s",
            businessKey,                    // 예: "payment"
            info.getWorkflowId(),          // 예: "order-O123"
            info.getActivityId()           // 예: "1" (첫 번째 Activity)
        );
        // 결과: "payment-order-O123-1"
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 전략 2: 비즈니스 키 기반 (특정 상황에 적합)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 비즈니스 의미가 있는 키 조합
     *
     * 사용 시점:
     * - 외부 시스템이 특정 포맷의 키를 요구할 때
     * - 비즈니스 규칙으로 중복 정의가 있을 때
     *   예: "같은 주문에 대해 결제는 1회만"
     */
    public String strategy2_BusinessKey(String orderId, String productId) {
        return String.format("reserve-%s-%s", orderId, productId);
        // 결과: "reserve-O123-P456"
        // 의미: 주문 O123의 상품 P456 예약
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 전략 3: 해시 기반 (복잡한 입력의 경우)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 복잡한 객체를 해시하여 키 생성
     *
     * 사용 시점:
     * - 입력 객체가 복잡하고 필드가 많을 때
     * - 키 길이 제한이 있을 때
     */
    public String strategy3_HashBased(Object request) {
        String json = objectMapper.writeValueAsString(request);
        String hash = DigestUtils.sha256Hex(json);
        return "activity-" + hash.substring(0, 16);
        // 결과: "activity-a1b2c3d4e5f6g7h8"
    }
}
```

### 2.4 멱등성 구현 패턴 - 완전한 코드

```java
/**
 * 멱등성 서비스 - Redis 기반 구현
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;

    // 기본 TTL: 10분
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    /**
     * 멱등성 보장 실행
     *
     * @param key 멱등성 키
     * @param operation 실행할 작업
     * @param ttl 결과 보관 시간
     * @return 작업 결과 (신규 또는 캐시된)
     */
    public <T> T executeIdempotent(
            String key,
            Supplier<T> operation,
            Duration ttl) {

        RBucket<String> bucket = redisson.getBucket("idempotency:" + key);

        // 1. 이미 처리된 요청인지 확인
        String cached = bucket.get();
        if (cached != null) {
            log.info("멱등성 히트: key={}", key);
            return deserialize(cached);
        }

        // 2. 락 획득 (동시 요청 방지)
        RLock lock = redisson.getLock("idempotency-lock:" + key);
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new IdempotencyLockException("멱등성 락 획득 실패: " + key);
            }

            // 3. 락 획득 후 다시 확인 (Double-Check Locking)
            cached = bucket.get();
            if (cached != null) {
                log.info("멱등성 히트 (락 후): key={}", key);
                return deserialize(cached);
            }

            // 4. 실제 작업 실행
            T result = operation.get();

            // 5. 결과 저장
            bucket.set(serialize(result), ttl.toMillis(), TimeUnit.MILLISECONDS);
            log.info("멱등성 저장: key={}, ttl={}ms", key, ttl.toMillis());

            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdempotencyException("멱등성 처리 중 인터럽트", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // 간단한 버전 (기본 TTL 사용)
    public <T> T executeIdempotent(String key, Supplier<T> operation) {
        return executeIdempotent(key, operation, DEFAULT_TTL);
    }

    private <T> String serialize(T obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IdempotencyException("직렬화 실패", e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T deserialize(String json) {
        try {
            return (T) objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IdempotencyException("역직렬화 실패", e);
        }
    }
}
```

### 2.5 Activity에서 멱등성 적용 예시

```java
/**
 * 결제 Activity - 멱등성 적용
 */
@Component
@RequiredArgsConstructor
public class PaymentActivitiesImpl implements PaymentActivities {

    private final PaymentClient paymentClient;
    private final IdempotencyService idempotencyService;

    @Override
    public PaymentResult processPayment(String orderId, BigDecimal amount) {
        // 1. 멱등성 키 생성 (Workflow ID + Activity ID)
        ActivityInfo info = Activity.getExecutionContext().getInfo();
        String idempotencyKey = String.format("payment-%s-%s",
            info.getWorkflowId(),
            info.getActivityId());

        // 2. 멱등성 보장 실행
        return idempotencyService.executeIdempotent(
            idempotencyKey,
            () -> {
                // 실제 결제 처리 (이 블록은 최대 1번만 실행됨)
                PaymentRequest request = PaymentRequest.builder()
                    .orderId(orderId)
                    .amount(amount)
                    .idempotencyKey(idempotencyKey)  // 외부 API에도 전달
                    .build();

                return paymentClient.charge(request);
            },
            Duration.ofMinutes(10)  // Activity 타임아웃 + 여유
        );
    }
}
```

### 2.6 멱등성 DB 스키마 (DB 기반 구현 시)

```sql
-- 멱등성 키 저장 테이블
CREATE TABLE idempotency_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL,

    -- 결과 저장
    result_type VARCHAR(100) NOT NULL,     -- 결과 클래스명
    result_json JSON NOT NULL,              -- 결과 JSON

    -- 메타데이터
    workflow_id VARCHAR(255),               -- Temporal Workflow ID
    activity_id VARCHAR(255),               -- Temporal Activity ID

    -- 시간 관리
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,          -- TTL

    -- 인덱스
    UNIQUE KEY uk_idempotency_key (idempotency_key),
    INDEX idx_expires_at (expires_at)       -- 만료 정리용
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 만료된 레코드 정리 (스케줄러로 실행)
-- DELETE FROM idempotency_records WHERE expires_at < NOW();
```

---

## 3. 동시성 제어 설계 완벽 가이드

### 3.1 동시성 문제가 발생하는 시나리오

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Temporal에서 동시성 문제 발생 시나리오                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  [시나리오] 동시 주문으로 인한 오버셀링                                      │
│                                                                              │
│  재고: 상품 A = 1개                                                          │
│                                                                              │
│  고객 1                        고객 2                                        │
│    │                             │                                           │
│    │ "상품 A 1개 주문"           │ "상품 A 1개 주문"                         │
│    ▼                             ▼                                           │
│  ┌─────────────┐            ┌─────────────┐                                 │
│  │ Workflow 1  │            │ Workflow 2  │                                 │
│  │ (주문 1)    │            │ (주문 2)    │                                 │
│  └──────┬──────┘            └──────┬──────┘                                 │
│         │                          │                                         │
│    시간 ────────────────────────────────────────────────────────▶           │
│         │                          │                                         │
│         │ t=0: 재고 확인          │ t=1: 재고 확인                           │
│         │   SELECT quantity       │   SELECT quantity                        │
│         │   결과: 1개             │   결과: 1개                              │
│         │   "재고 있음!"          │   "재고 있음!"                           │
│         │                          │                                         │
│         │ t=2: 재고 예약          │                                         │
│         │   UPDATE quantity = 0   │                                         │
│         │   ✅ 성공               │                                         │
│         │                          │                                         │
│         │                          │ t=3: 재고 예약                          │
│         │                          │   UPDATE quantity = -1                  │
│         │                          │   ❌ 오버셀링! (또는 예외)              │
│         │                          │                                         │
│  ══════════════════════════════════════════════════════════════════════════ │
│                                                                              │
│  문제: Workflow 2는 "재고 있음"을 확인했는데 예약 실패                       │
│       더 심각한 경우: 검증 없이 -1로 업데이트됨                              │
│                                                                              │
│  Temporal은 왜 못 막나?                                                     │
│  → 각 Workflow는 독립 실행, 서로의 존재를 모름                               │
│  → Temporal은 "외부 DB"의 동시 접근을 제어하지 않음                          │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 동시성 제어 3가지 패턴

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       동시성 제어 3가지 패턴 비교                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  패턴 1: 분산 락 (Distributed Lock)                                  │   │
│  │                                                                      │   │
│  │  동작: 작업 전 락 획득 → 작업 수행 → 락 해제                         │   │
│  │                                                                      │   │
│  │  Workflow 1 ──락 획득──▶ [작업] ──락 해제──▶                        │   │
│  │                   │                                                  │   │
│  │  Workflow 2 ──락 시도──│ (대기) ──락 획득──▶ [작업] ──락 해제──▶    │   │
│  │                   │                                                  │   │
│  │  장점: 구현 간단, 확실한 순차 처리                                   │   │
│  │  단점: 락 대기 시간, 데드락 가능성                                   │   │
│  │  사용: 재고 예약, 결제 처리 등 임계 구역 보호                        │   │
│  │                                                                      │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  패턴 2: DB Atomic UPDATE (Compare-and-Set)                          │   │
│  │                                                                      │   │
│  │  동작: 조건부 UPDATE로 원자적 변경                                   │   │
│  │                                                                      │   │
│  │  UPDATE inventory                                                    │   │
│  │  SET quantity = quantity - 1                                         │   │
│  │  WHERE product_id = 'P1' AND quantity >= 1                          │   │
│  │                                                                      │   │
│  │  → 영향받은 row = 0이면 재고 부족                                    │   │
│  │                                                                      │   │
│  │  장점: 락 없이 원자적, 성능 좋음                                     │   │
│  │  단점: 복잡한 로직에는 부적합                                        │   │
│  │  사용: 단순 카운터 증감, 상태 전이                                   │   │
│  │                                                                      │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  패턴 3: 낙관적 락 (Optimistic Lock with Version)                    │   │
│  │                                                                      │   │
│  │  동작: 버전 체크 후 업데이트, 충돌 시 재시도                         │   │
│  │                                                                      │   │
│  │  1. SELECT * FROM inventory WHERE id = 1                            │   │
│  │     → quantity: 10, version: 5                                      │   │
│  │                                                                      │   │
│  │  2. UPDATE inventory                                                 │   │
│  │     SET quantity = 9, version = 6                                   │   │
│  │     WHERE id = 1 AND version = 5                                    │   │
│  │                                                                      │   │
│  │  → 영향받은 row = 0이면 다른 트랜잭션이 먼저 수정                    │   │
│  │  → Temporal이 재시도하거나 Activity에서 재시도                       │   │
│  │                                                                      │   │
│  │  장점: 락 없이 충돌 감지, 읽기 성능 좋음                             │   │
│  │  단점: 충돌 시 재시도 필요                                           │   │
│  │  사용: 읽기 많고 쓰기 적을 때, 충돌 적을 때                          │   │
│  │                                                                      │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.3 분산 락 구현 - 완전한 코드

```java
/**
 * 재고 Activity - 분산 락 적용
 */
@Component
@RequiredArgsConstructor
public class InventoryActivitiesImpl implements InventoryActivities {

    private final RedissonClient redisson;
    private final InventoryRepository inventoryRepository;
    private final ReservationRepository reservationRepository;
    private final IdempotencyService idempotencyService;

    // 락 설정
    private static final long LOCK_WAIT_TIME = 5;      // 락 대기 최대 5초
    private static final long LOCK_LEASE_TIME = 30;    // 락 보유 최대 30초

    @Override
    public ReservationResult reserveStock(String productId, int quantity, String orderId) {
        // 1. 멱등성 키 생성
        ActivityInfo info = Activity.getExecutionContext().getInfo();
        String idempotencyKey = String.format("reserve-%s-%s",
            info.getWorkflowId(), productId);

        // 2. 멱등성 보장 + 분산 락 조합
        return idempotencyService.executeIdempotent(
            idempotencyKey,
            () -> reserveStockWithLock(productId, quantity, orderId),
            Duration.ofMinutes(10)
        );
    }

    /**
     * 분산 락을 사용한 재고 예약
     */
    private ReservationResult reserveStockWithLock(
            String productId, int quantity, String orderId) {

        // 락 키: 상품별로 락 (다른 상품은 병렬 처리 가능)
        String lockKey = "inventory:lock:" + productId;
        RLock lock = redisson.getLock(lockKey);

        try {
            // 락 획득 시도
            boolean acquired = lock.tryLock(
                LOCK_WAIT_TIME,
                LOCK_LEASE_TIME,
                TimeUnit.SECONDS
            );

            if (!acquired) {
                // 락 획득 실패 → Temporal이 재시도하도록 예외 발생
                throw new LockAcquisitionException(
                    "재고 락 획득 실패: productId=" + productId);
            }

            // ════════════════════════════════════════════════════════════
            // 임계 구역 (Critical Section) - 이 안에서는 동시 접근 없음
            // ════════════════════════════════════════════════════════════

            // 1. 재고 조회
            Inventory inventory = inventoryRepository
                .findByProductId(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

            // 2. 재고 검증
            if (inventory.getQuantity() < quantity) {
                throw new InsufficientStockException(
                    String.format("재고 부족: 요청=%d, 현재=%d",
                        quantity, inventory.getQuantity()));
            }

            // 3. 재고 차감 (예약 상태로)
            inventory.decreaseQuantity(quantity);
            inventoryRepository.save(inventory);

            // 4. 예약 레코드 생성
            Reservation reservation = Reservation.builder()
                .orderId(orderId)
                .productId(productId)
                .quantity(quantity)
                .status(ReservationStatus.RESERVED)
                .build();
            reservationRepository.save(reservation);

            return ReservationResult.success(reservation.getId());

            // ════════════════════════════════════════════════════════════

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ActivityException("락 대기 중 인터럽트", e);
        } finally {
            // 락 해제 (반드시 현재 스레드가 보유 중인 경우만)
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void releaseStock(String reservationId) {
        // 보상 트랜잭션도 멱등성 + 락 필요!
        ActivityInfo info = Activity.getExecutionContext().getInfo();
        String idempotencyKey = String.format("release-%s-%s",
            info.getWorkflowId(), reservationId);

        idempotencyService.executeIdempotent(idempotencyKey, () -> {
            Reservation reservation = reservationRepository
                .findById(reservationId)
                .orElseThrow();

            String lockKey = "inventory:lock:" + reservation.getProductId();
            RLock lock = redisson.getLock(lockKey);

            try {
                if (lock.tryLock(LOCK_WAIT_TIME, LOCK_LEASE_TIME, TimeUnit.SECONDS)) {
                    // 이미 취소된 경우 스킵 (멱등성)
                    if (reservation.getStatus() == ReservationStatus.CANCELLED) {
                        return null;
                    }

                    // 재고 복구
                    Inventory inventory = inventoryRepository
                        .findByProductId(reservation.getProductId())
                        .orElseThrow();
                    inventory.increaseQuantity(reservation.getQuantity());
                    inventoryRepository.save(inventory);

                    // 예약 상태 변경
                    reservation.cancel();
                    reservationRepository.save(reservation);

                    return null;
                }
                throw new LockAcquisitionException("재고 해제 락 획득 실패");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ActivityException("락 대기 중 인터럽트", e);
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        });
    }
}
```

### 3.4 DB Atomic UPDATE 패턴

```java
/**
 * 락 없이 DB 원자적 UPDATE 사용
 * 단순한 카운터 증감에 적합
 */
@Repository
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    /**
     * 원자적 재고 차감
     *
     * @return 영향받은 row 수 (0이면 재고 부족)
     */
    @Modifying
    @Query("""
        UPDATE Inventory i
        SET i.quantity = i.quantity - :amount,
            i.updatedAt = CURRENT_TIMESTAMP
        WHERE i.productId = :productId
          AND i.quantity >= :amount
        """)
    int decreaseStock(@Param("productId") String productId,
                      @Param("amount") int amount);

    /**
     * 원자적 재고 증가 (보상용)
     */
    @Modifying
    @Query("""
        UPDATE Inventory i
        SET i.quantity = i.quantity + :amount,
            i.updatedAt = CURRENT_TIMESTAMP
        WHERE i.productId = :productId
        """)
    int increaseStock(@Param("productId") String productId,
                      @Param("amount") int amount);
}

/**
 * Activity에서 사용
 */
@Component
public class InventoryActivitiesImpl implements InventoryActivities {

    @Override
    @Transactional
    public ReservationResult reserveStock(String productId, int quantity, String orderId) {
        // 멱등성 처리 (생략)

        // 원자적 UPDATE
        int affected = inventoryRepository.decreaseStock(productId, quantity);

        if (affected == 0) {
            // 재고 부족 (비즈니스 예외 - 재시도 안 함)
            throw new InsufficientStockException(productId);
        }

        // 예약 레코드 생성
        Reservation reservation = reservationRepository.save(
            Reservation.create(orderId, productId, quantity));

        return ReservationResult.success(reservation.getId());
    }
}
```

### 3.5 락 키 설계 가이드

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           락 키 설계 가이드                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  원칙: "락의 범위 = 보호하려는 리소스의 범위"                                 │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════    │
│  예시 1: 상품별 재고                                                        │
│  ═══════════════════════════════════════════════════════════════════════    │
│                                                                              │
│  리소스: 상품 A의 재고, 상품 B의 재고                                        │
│  락 키: "inventory:lock:{productId}"                                        │
│                                                                              │
│  ✅ 상품 A 예약과 상품 B 예약은 병렬 처리 가능                               │
│  ✅ 상품 A에 대한 동시 예약은 순차 처리                                      │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════    │
│  예시 2: 사용자별 포인트                                                    │
│  ═══════════════════════════════════════════════════════════════════════    │
│                                                                              │
│  리소스: 사용자 A의 포인트, 사용자 B의 포인트                                │
│  락 키: "point:lock:{userId}"                                               │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════    │
│  예시 3: 주문별 상태 변경                                                   │
│  ═══════════════════════════════════════════════════════════════════════    │
│                                                                              │
│  리소스: 주문의 상태                                                        │
│  락 키: "order:lock:{orderId}"                                              │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════    │
│  안티패턴                                                                   │
│  ═══════════════════════════════════════════════════════════════════════    │
│                                                                              │
│  ❌ 너무 넓은 범위                                                          │
│     락 키: "inventory:lock" (전체 재고에 대한 단일 락)                       │
│     문제: 모든 상품 예약이 순차 처리됨 → 병목                               │
│                                                                              │
│  ❌ 너무 좁은 범위                                                          │
│     락 키: "inventory:lock:{productId}:{orderId}"                           │
│     문제: 같은 상품에 대한 동시 접근 보호 안 됨                              │
│                                                                              │
│  ❌ 잘못된 대상                                                             │
│     락 키: "order:lock:{orderId}" (주문 락으로 재고 보호 시도)               │
│     문제: 다른 주문에서 같은 상품 접근 시 보호 안 됨                         │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Saga 격리 문제와 해결

### 4.1 Saga의 격리 문제란?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Saga 패턴의 격리 문제                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  2PC (Two-Phase Commit) vs Saga                                             │
│  ═════════════════════════════                                              │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  2PC: 모든 참가자가 "준비" 상태로 대기 → 한번에 "커밋"               │   │
│  │       → 중간 상태가 외부에 노출 안 됨                                │   │
│  │       → 강한 격리 (Isolation) 보장                                  │   │
│  │                                                                      │   │
│  │  Saga: 각 단계가 즉시 커밋됨                                         │   │
│  │       → 중간 상태가 외부에 노출됨                                    │   │
│  │       → 격리 없음 (No Isolation)                                    │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════    │
│  문제 1: Dirty Read (더티 리드)                                             │
│  ═══════════════════════════════════════════════════════════════════════    │
│                                                                              │
│  Saga A: 주문 생성 → 재고 예약 → 결제 실패! → 보상 시작                     │
│                                      ↑                                       │
│  Saga B: ─────────────────────── 재고 조회 ─────────────────────────▶      │
│                                  "재고 0개"                                  │
│                                  (실제로는 A가 보상해서 곧 복구될 예정)      │
│                                                                              │
│  결과: B는 "재고 없음"으로 판단했지만, A의 보상 후 재고가 생김              │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════    │
│  문제 2: Lost Update (손실된 업데이트)                                      │
│  ═══════════════════════════════════════════════════════════════════════    │
│                                                                              │
│  Saga A: 재고 10 → 재고 5로 변경 (5개 예약)                                 │
│                           ↓                                                  │
│  Saga B: 재고 10 조회 → 재고 3으로 변경 (7개 예약)                          │
│                                                                              │
│  결과: A의 변경이 무시됨! (Lost Update)                                     │
│        재고가 3이 되어야 하는데 10-7=3만 적용                               │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════    │
│  문제 3: Non-Repeatable Read (비반복 읽기)                                  │
│  ═══════════════════════════════════════════════════════════════════════    │
│                                                                              │
│  Saga A: 재고 조회 (10개) → ... (처리 중) ... → 재고 재조회 (5개?!)         │
│                                     ↑                                        │
│  Saga B: ─────────────── 재고 5개로 변경 ────────────────────▶              │
│                                                                              │
│  결과: A의 두 조회 결과가 다름                                              │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Semantic Lock 패턴

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Semantic Lock 패턴 해결                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  핵심 아이디어:                                                             │
│  ═══════════════                                                            │
│  "데이터에 '처리 중' 상태를 표시하여 다른 Saga가 알 수 있게 한다"            │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                                                                      │   │
│  │  기존 재고 테이블:                                                   │   │
│  │  ┌──────────────────────────────────────────────────────────────┐   │   │
│  │  │  product_id  │  quantity  │                                   │   │   │
│  │  │─────────────────────────────                                  │   │   │
│  │  │  P1          │  10        │  ← 누군가 처리 중인지 모름         │   │   │
│  │  └──────────────────────────────────────────────────────────────┘   │   │
│  │                                                                      │   │
│  │  Semantic Lock 적용 후:                                             │   │
│  │  ┌──────────────────────────────────────────────────────────────┐   │   │
│  │  │  product_id  │  quantity  │  reserved_qty  │  lock_holder    │   │   │
│  │  │──────────────────────────────────────────────────────────────│   │   │
│  │  │  P1          │  10        │  5             │  order-123      │   │   │
│  │  │              │            │  ↑             │  ↑              │   │   │
│  │  │              │            │  예약 수량      │  누가 예약했는지 │   │   │
│  │  └──────────────────────────────────────────────────────────────┘   │   │
│  │                                                                      │   │
│  │  가용 재고 = quantity - reserved_qty = 10 - 5 = 5                   │   │
│  │                                                                      │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  동작 흐름:                                                                 │
│  ══════════                                                                 │
│                                                                              │
│  1. 예약 시:                                                                │
│     reserved_qty += 요청수량                                                │
│     lock_holder = orderId                                                   │
│                                                                              │
│  2. 확정 시 (Saga 성공):                                                    │
│     quantity -= reserved_qty                                                │
│     reserved_qty = 0                                                        │
│     lock_holder = null                                                      │
│                                                                              │
│  3. 취소 시 (Saga 실패, 보상):                                              │
│     reserved_qty = 0                                                        │
│     lock_holder = null                                                      │
│     (quantity는 그대로 - 실제 차감은 안 했으니까)                            │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 4.3 Semantic Lock 구현

```java
/**
 * Semantic Lock이 적용된 재고 엔티티
 */
@Entity
@Table(name = "inventory")
public class Inventory {

    @Id
    private Long id;

    private String productId;

    // 실제 재고 (확정된 수량)
    private int quantity;

    // 예약된 수량 (Saga 진행 중)
    private int reservedQuantity = 0;

    // 예약자 정보 (디버깅/추적용)
    private String lockHolder;

    // 버전 (낙관적 락)
    @Version
    private Long version;

    /**
     * 가용 재고 계산
     */
    public int getAvailableQuantity() {
        return quantity - reservedQuantity;
    }

    /**
     * 재고 예약 (Semantic Lock 획득)
     */
    public void reserve(int amount, String orderId) {
        if (getAvailableQuantity() < amount) {
            throw new InsufficientStockException(
                "가용 재고 부족: 요청=" + amount + ", 가용=" + getAvailableQuantity());
        }
        this.reservedQuantity += amount;
        this.lockHolder = orderId;
    }

    /**
     * 예약 확정 (Saga 성공)
     */
    public void confirmReservation(int amount) {
        if (this.reservedQuantity < amount) {
            throw new IllegalStateException("예약 수량보다 많은 확정 시도");
        }
        this.quantity -= amount;
        this.reservedQuantity -= amount;
        if (this.reservedQuantity == 0) {
            this.lockHolder = null;
        }
    }

    /**
     * 예약 취소 (보상)
     */
    public void cancelReservation(int amount) {
        this.reservedQuantity -= amount;
        if (this.reservedQuantity <= 0) {
            this.reservedQuantity = 0;
            this.lockHolder = null;
        }
    }
}
```

```java
/**
 * Semantic Lock이 적용된 Activity
 */
@Component
public class InventoryActivitiesImpl implements InventoryActivities {

    @Override
    @Transactional
    public ReservationResult reserveStock(String productId, int quantity, String orderId) {
        // 멱등성 + 분산 락 (생략)

        Inventory inventory = inventoryRepository.findByProductIdWithLock(productId)
            .orElseThrow(() -> new ProductNotFoundException(productId));

        // Semantic Lock: 예약 (실제 차감은 나중에)
        inventory.reserve(quantity, orderId);
        inventoryRepository.save(inventory);

        // 예약 레코드 생성
        Reservation reservation = Reservation.builder()
            .orderId(orderId)
            .productId(productId)
            .quantity(quantity)
            .status(ReservationStatus.PENDING)  // 아직 확정 전
            .build();

        return ReservationResult.success(reservationRepository.save(reservation).getId());
    }

    @Override
    @Transactional
    public void confirmReservation(String reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
            .orElseThrow();

        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            return;  // 이미 확정됨 (멱등성)
        }

        Inventory inventory = inventoryRepository
            .findByProductIdWithLock(reservation.getProductId())
            .orElseThrow();

        // Semantic Lock 해제 + 실제 차감
        inventory.confirmReservation(reservation.getQuantity());
        reservation.confirm();

        inventoryRepository.save(inventory);
        reservationRepository.save(reservation);
    }

    @Override
    @Transactional
    public void cancelReservation(String reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
            .orElseThrow();

        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            return;  // 이미 취소됨 (멱등성)
        }

        Inventory inventory = inventoryRepository
            .findByProductIdWithLock(reservation.getProductId())
            .orElseThrow();

        // Semantic Lock 해제 (차감 없이)
        inventory.cancelReservation(reservation.getQuantity());
        reservation.cancel();

        inventoryRepository.save(inventory);
        reservationRepository.save(reservation);
    }
}
```

### 4.4 Workflow에서 Semantic Lock 사용

```java
/**
 * Semantic Lock을 활용한 주문 Workflow
 */
public class OrderWorkflowImpl implements OrderWorkflow {

    @Override
    public OrderResult processOrder(OrderRequest request) {
        Saga saga = new Saga(new Saga.Options.Builder().build());

        try {
            // Step 1: 주문 생성
            String orderId = activities.createOrder(request);
            saga.addCompensation(() -> activities.cancelOrder(orderId));

            // Step 2: 재고 "예약" (Semantic Lock)
            // → 실제 차감이 아닌 reserved_qty 증가
            String reservationId = activities.reserveStock(
                request.productId(),
                request.quantity(),
                orderId
            );
            saga.addCompensation(() -> activities.cancelReservation(reservationId));
            // → 보상 시 reserved_qty만 해제, quantity는 그대로

            // Step 3: 결제
            String paymentId = activities.processPayment(orderId, request.amount());
            saga.addCompensation(() -> activities.refundPayment(paymentId));

            // Step 4: 모든 것 성공! 예약 확정
            // → reserved_qty를 quantity에서 실제 차감
            activities.confirmReservation(reservationId);
            activities.confirmOrder(orderId);

            return OrderResult.success(orderId);

        } catch (Exception e) {
            saga.compensate();
            return OrderResult.failure(e.getMessage());
        }
    }
}
```

---

## 5. 서킷 브레이커와 Temporal 조합

### 5.1 Temporal에서 서킷 브레이커가 필요한가?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                 Temporal과 서킷 브레이커: 언제 필요한가?                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  대부분의 경우: 서킷 브레이커 불필요 ✅                              │   │
│  │                                                                      │   │
│  │  이유:                                                               │   │
│  │  • Temporal RetryOptions가 재시도 횟수 제한                         │   │
│  │  • Temporal 타임아웃이 무한 대기 방지                               │   │
│  │  • Activity 실패 시 Workflow가 적절히 처리                          │   │
│  │                                                                      │   │
│  │  ActivityOptions.newBuilder()                                        │   │
│  │      .setStartToCloseTimeout(Duration.ofMinutes(5))                 │   │
│  │      .setRetryOptions(RetryOptions.newBuilder()                     │   │
│  │          .setMaximumAttempts(5)  // ← 이미 재시도 제한               │   │
│  │          .build())                                                   │   │
│  │      .build();                                                       │   │
│  │                                                                      │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  서킷 브레이커가 필요한 경우 ⚠️                                     │   │
│  │                                                                      │   │
│  │  1. 외부 서비스가 "완전히" 죽은 경우                                │   │
│  │     ─────────────────────────────────                                │   │
│  │     • 문제: Temporal은 계속 재시도 → 리소스 낭비                    │   │
│  │     • 해결: 서킷 브레이커로 빠른 실패 (fail-fast)                   │   │
│  │                                                                      │   │
│  │  2. 외부 서비스 호출 비용이 비싼 경우                               │   │
│  │     ───────────────────────────────                                  │   │
│  │     • 문제: 매 재시도마다 비용 발생                                 │   │
│  │     • 해결: 서킷 열려 있으면 호출 자체를 안 함                       │   │
│  │                                                                      │   │
│  │  3. 빠른 응답이 필요한 경우                                         │   │
│  │     ────────────────────────                                         │   │
│  │     • 문제: 재시도 대기 시간이 너무 길어짐                          │   │
│  │     • 해결: 서킷 열리면 즉시 fallback 응답                          │   │
│  │                                                                      │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.2 서킷 브레이커 + Temporal 조합 패턴

```java
/**
 * 서킷 브레이커가 적용된 Activity
 */
@Component
@RequiredArgsConstructor
public class ExternalApiActivitiesImpl implements ExternalApiActivities {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final ExternalApiClient externalApiClient;

    @Override
    public ApiResponse callExternalApi(ApiRequest request) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry
            .circuitBreaker("externalApi");

        // 서킷 상태 확인
        if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
            // 서킷이 열려 있으면 즉시 실패
            // → Temporal이 재시도하되, 서킷이 닫힐 때까지 빠르게 실패
            throw new CircuitBreakerOpenException(
                "서킷 브레이커 열림 - 외부 API 일시 불가");
        }

        try {
            return circuitBreaker.executeSupplier(() ->
                externalApiClient.call(request)
            );
        } catch (CallNotPermittedException e) {
            // 서킷이 열려서 호출 거부됨
            throw new CircuitBreakerOpenException("서킷 브레이커 열림", e);
        }
    }
}

/**
 * Resilience4j 설정
 */
@Configuration
public class CircuitBreakerConfig {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .failureRateThreshold(50)           // 50% 실패 시 서킷 열림
            .slowCallRateThreshold(80)          // 80% 느린 호출 시 서킷 열림
            .slowCallDurationThreshold(Duration.ofSeconds(5))
            .waitDurationInOpenState(Duration.ofSeconds(30))  // 30초 후 half-open
            .permittedNumberOfCallsInHalfOpenState(3)
            .minimumNumberOfCalls(5)
            .build();

        return CircuitBreakerRegistry.of(config);
    }
}
```

---

## 6. 실전 Activity 구현 템플릿

### 6.1 완전한 Activity 구현 템플릿

```java
/**
 * 모든 보호 메커니즘이 적용된 Activity 템플릿
 *
 * 체크리스트:
 * ✅ 멱등성 (Idempotency)
 * ✅ 분산 락 (Distributed Lock)
 * ✅ 비즈니스 검증
 * ✅ 예외 처리
 * ✅ 로깅
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryActivitiesImpl implements InventoryActivities {

    private final RedissonClient redisson;
    private final InventoryRepository inventoryRepository;
    private final ReservationRepository reservationRepository;
    private final IdempotencyService idempotencyService;

    // 설정값
    private static final long LOCK_WAIT_SECONDS = 5;
    private static final long LOCK_LEASE_SECONDS = 30;
    private static final Duration IDEMPOTENCY_TTL = Duration.ofMinutes(10);

    @Override
    public ReservationResult reserveStock(
            String productId,
            int quantity,
            String orderId) {

        // 1. 컨텍스트 정보 추출
        ActivityInfo info = Activity.getExecutionContext().getInfo();
        String workflowId = info.getWorkflowId();
        String activityId = info.getActivityId();

        log.info("재고 예약 시작: productId={}, quantity={}, orderId={}, " +
                "workflowId={}, activityId={}",
            productId, quantity, orderId, workflowId, activityId);

        // 2. 멱등성 키 생성
        String idempotencyKey = String.format("reserve-%s-%s", workflowId, productId);

        // 3. 멱등성 보장 실행
        return idempotencyService.executeIdempotent(
            idempotencyKey,
            () -> doReserveStock(productId, quantity, orderId),
            IDEMPOTENCY_TTL
        );
    }

    private ReservationResult doReserveStock(
            String productId,
            int quantity,
            String orderId) {

        // 4. 분산 락 획득
        String lockKey = "inventory:lock:" + productId;
        RLock lock = redisson.getLock(lockKey);

        try {
            boolean acquired = lock.tryLock(
                LOCK_WAIT_SECONDS,
                LOCK_LEASE_SECONDS,
                TimeUnit.SECONDS
            );

            if (!acquired) {
                log.warn("재고 락 획득 실패: productId={}", productId);
                throw new LockAcquisitionException(
                    "재고 락 획득 실패: " + productId);
            }

            log.debug("재고 락 획득 성공: productId={}", productId);

            // ═══════════════════════════════════════════════════════════════
            // 임계 구역 시작
            // ═══════════════════════════════════════════════════════════════

            // 5. 비즈니스 검증
            Inventory inventory = inventoryRepository
                .findByProductId(productId)
                .orElseThrow(() -> {
                    log.error("상품을 찾을 수 없음: productId={}", productId);
                    return new ProductNotFoundException(productId);
                });

            if (inventory.getAvailableQuantity() < quantity) {
                log.warn("재고 부족: productId={}, 요청={}, 가용={}",
                    productId, quantity, inventory.getAvailableQuantity());
                throw new InsufficientStockException(
                    String.format("재고 부족: 요청=%d, 가용=%d",
                        quantity, inventory.getAvailableQuantity()));
            }

            // 6. 상태 변경 (Semantic Lock)
            inventory.reserve(quantity, orderId);
            inventoryRepository.save(inventory);

            // 7. 예약 레코드 생성
            Reservation reservation = Reservation.builder()
                .orderId(orderId)
                .productId(productId)
                .quantity(quantity)
                .status(ReservationStatus.PENDING)
                .build();
            reservationRepository.save(reservation);

            log.info("재고 예약 완료: reservationId={}, productId={}, quantity={}",
                reservation.getId(), productId, quantity);

            return ReservationResult.success(reservation.getId());

            // ═══════════════════════════════════════════════════════════════
            // 임계 구역 끝
            // ═══════════════════════════════════════════════════════════════

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("락 대기 중 인터럽트: productId={}", productId, e);
            throw new ActivityException("락 대기 중 인터럽트", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("재고 락 해제: productId={}", productId);
            }
        }
    }

    @Override
    public void confirmReservation(String reservationId) {
        ActivityInfo info = Activity.getExecutionContext().getInfo();
        String idempotencyKey = String.format("confirm-%s-%s",
            info.getWorkflowId(), reservationId);

        idempotencyService.executeIdempotent(
            idempotencyKey,
            () -> {
                doConfirmReservation(reservationId);
                return null;
            },
            IDEMPOTENCY_TTL
        );
    }

    @Transactional
    protected void doConfirmReservation(String reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
            .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        // 멱등성: 이미 확정된 경우 스킵
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            log.info("이미 확정된 예약: reservationId={}", reservationId);
            return;
        }

        String lockKey = "inventory:lock:" + reservation.getProductId();
        RLock lock = redisson.getLock(lockKey);

        try {
            if (lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS)) {
                Inventory inventory = inventoryRepository
                    .findByProductId(reservation.getProductId())
                    .orElseThrow();

                inventory.confirmReservation(reservation.getQuantity());
                reservation.confirm();

                inventoryRepository.save(inventory);
                reservationRepository.save(reservation);

                log.info("예약 확정 완료: reservationId={}", reservationId);
            } else {
                throw new LockAcquisitionException("확정 락 획득 실패");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ActivityException("락 대기 중 인터럽트", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void cancelReservation(String reservationId) {
        // 보상 트랜잭션도 동일한 패턴 적용
        ActivityInfo info = Activity.getExecutionContext().getInfo();
        String idempotencyKey = String.format("cancel-%s-%s",
            info.getWorkflowId(), reservationId);

        idempotencyService.executeIdempotent(
            idempotencyKey,
            () -> {
                doCancelReservation(reservationId);
                return null;
            },
            IDEMPOTENCY_TTL
        );
    }

    @Transactional
    protected void doCancelReservation(String reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
            .orElse(null);

        // 멱등성: 없거나 이미 취소된 경우 스킵
        if (reservation == null ||
            reservation.getStatus() == ReservationStatus.CANCELLED) {
            log.info("취소할 예약 없음 또는 이미 취소됨: reservationId={}", reservationId);
            return;
        }

        String lockKey = "inventory:lock:" + reservation.getProductId();
        RLock lock = redisson.getLock(lockKey);

        try {
            if (lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS)) {
                Inventory inventory = inventoryRepository
                    .findByProductId(reservation.getProductId())
                    .orElseThrow();

                inventory.cancelReservation(reservation.getQuantity());
                reservation.cancel();

                inventoryRepository.save(inventory);
                reservationRepository.save(reservation);

                log.info("예약 취소 완료: reservationId={}", reservationId);
            } else {
                throw new LockAcquisitionException("취소 락 획득 실패");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ActivityException("락 대기 중 인터럽트", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

---

## 7. 체크리스트와 안티패턴

### 7.1 Activity 구현 체크리스트

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      Activity 구현 체크리스트                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ☐ 멱등성 (Idempotency)                                                     │
│  ═══════════════════════                                                    │
│  ☐ Idempotency Key 생성 전략 결정                                           │
│  ☐ 중복 실행 시 같은 결과 반환 확인                                          │
│  ☐ 외부 API 호출 시 idempotencyKey 전달                                     │
│  ☐ 보상 트랜잭션에도 멱등성 적용                                             │
│                                                                              │
│  ☐ 동시성 제어 (Concurrency)                                                │
│  ═══════════════════════════                                                │
│  ☐ 공유 리소스 접근 시 락 사용 여부 결정                                     │
│  ☐ 락 키 범위가 적절한지 확인                                               │
│  ☐ 락 타임아웃 설정 (획득 대기, 보유 시간)                                   │
│  ☐ 락 해제 보장 (finally 블록)                                              │
│  ☐ 데드락 가능성 검토                                                       │
│                                                                              │
│  ☐ 비즈니스 검증 (Validation)                                               │
│  ═══════════════════════════                                                │
│  ☐ 입력값 검증 (@Valid, Bean Validation)                                    │
│  ☐ 도메인 규칙 검증 (재고 >= 0, 금액 > 0 등)                                │
│  ☐ 상태 전이 규칙 검증                                                       │
│                                                                              │
│  ☐ 예외 처리 (Exception Handling)                                           │
│  ═══════════════════════════════                                            │
│  ☐ 재시도 가능한 예외 vs 불가능한 예외 구분                                  │
│  ☐ RetryOptions.setDoNotRetry()에 비즈니스 예외 등록                        │
│  ☐ 예외 메시지에 충분한 정보 포함                                            │
│                                                                              │
│  ☐ 로깅 (Logging)                                                           │
│  ═════════════════                                                          │
│  ☐ 시작/종료 로그                                                           │
│  ☐ 주요 결정 포인트 로그                                                     │
│  ☐ 에러 로그 (스택 트레이스 포함)                                            │
│  ☐ Workflow ID, Activity ID 포함                                           │
│                                                                              │
│  ☐ 테스트 (Testing)                                                         │
│  ═══════════════════                                                        │
│  ☐ 단위 테스트 (Activity 로직)                                              │
│  ☐ 멱등성 테스트 (같은 입력 2번 실행)                                        │
│  ☐ 동시성 테스트 (여러 스레드에서 동시 실행)                                 │
│  ☐ 실패 시나리오 테스트                                                      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 7.2 안티패턴

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Activity 안티패턴                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ❌ 안티패턴 1: 멱등성 없이 외부 API 호출                                    │
│  ════════════════════════════════════════                                   │
│                                                                              │
│  // 나쁜 예                                                                  │
│  public String processPayment(String orderId, BigDecimal amount) {          │
│      return paymentClient.charge(orderId, amount);  // 재시도 시 이중 결제!  │
│  }                                                                           │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  ❌ 안티패턴 2: 락 없이 읽기-수정-쓰기                                       │
│  ═══════════════════════════════════                                        │
│                                                                              │
│  // 나쁜 예                                                                  │
│  public void reserveStock(String productId, int quantity) {                 │
│      Inventory inv = repository.findById(productId);  // 읽기               │
│      if (inv.getQuantity() >= quantity) {             // 체크               │
│          inv.setQuantity(inv.getQuantity() - quantity); // 수정             │
│          repository.save(inv);                          // 쓰기             │
│      }                                                                      │
│  }                                                                           │
│  // 문제: 읽기~쓰기 사이에 다른 Workflow가 개입 가능                         │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  ❌ 안티패턴 3: 매번 새로운 랜덤 키 생성                                     │
│  ═══════════════════════════════════════                                    │
│                                                                              │
│  // 나쁜 예                                                                  │
│  String key = UUID.randomUUID().toString();  // 재시도마다 다른 키!          │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  ❌ 안티패턴 4: 락 해제 누락                                                 │
│  ═══════════════════════════                                                │
│                                                                              │
│  // 나쁜 예                                                                  │
│  RLock lock = redisson.getLock(key);                                        │
│  lock.lock();                                                               │
│  doSomething();  // 예외 발생 시 락 영원히 잠김!                             │
│  lock.unlock();                                                              │
│                                                                              │
│  // 좋은 예                                                                  │
│  try {                                                                      │
│      lock.lock();                                                           │
│      doSomething();                                                         │
│  } finally {                                                                │
│      if (lock.isHeldByCurrentThread()) {                                   │
│          lock.unlock();                                                     │
│      }                                                                      │
│  }                                                                           │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  ❌ 안티패턴 5: 보상 트랜잭션에 멱등성 미적용                                 │
│  ═════════════════════════════════════════                                  │
│                                                                              │
│  // 나쁜 예: 보상도 재시도될 수 있음!                                        │
│  public void refundPayment(String paymentId) {                              │
│      paymentClient.refund(paymentId);  // 이중 환불 위험!                    │
│  }                                                                           │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  ❌ 안티패턴 6: Temporal이 모든 것을 해결한다고 가정                         │
│  ══════════════════════════════════════════════                             │
│                                                                              │
│  "Temporal 쓰니까 재시도, 복구 다 알아서 해주겠지"                           │
│  → Activity 내부의 안전성은 개발자 책임!                                    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 정리

### 핵심 메시지

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              핵심 메시지                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. Temporal은 "Workflow 실행"을 책임지고,                                  │
│     개발자는 "Activity 내부 안전성"을 책임진다.                              │
│                                                                              │
│  2. 모든 Activity는 멱등성을 보장해야 한다.                                  │
│     → 재시도는 언제든 발생할 수 있다.                                        │
│                                                                              │
│  3. 공유 리소스 접근 시 동시성 제어가 필수다.                                │
│     → 각 Workflow는 서로의 존재를 모른다.                                    │
│                                                                              │
│  4. Saga의 격리 문제를 인식하고 대응해야 한다.                               │
│     → Semantic Lock, 예약 패턴 활용.                                        │
│                                                                              │
│  5. Phase 2에서 배운 기술들은 Temporal과 함께 사용된다.                      │
│     → 분산 락, 멱등성, 낙관적 락 등은 여전히 필요하다.                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 8. Outbox 패턴과 Kafka 이벤트 발행

### 8.1 Outbox 패턴이란?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Outbox 패턴의 원래 목적                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  [문제 상황] DB 저장 + 이벤트 발행의 원자성                                 │
│                                                                              │
│  public void createOrder(OrderRequest request) {                            │
│      orderRepository.save(order);              // ✅ 성공                   │
│      kafkaTemplate.send("orders", event);      // 💥 실패 또는 크래시!      │
│  }                                                                           │
│                                                                              │
│  결과:                                                                       │
│  • DB에는 주문이 저장됨                                                     │
│  • Kafka에는 이벤트가 발행 안 됨                                            │
│  • 다른 서비스는 이 주문을 모름!                                            │
│                                                                              │
│  [Outbox 패턴 해결책]                                                       │
│                                                                              │
│  @Transactional                                                             │
│  public void createOrder(OrderRequest request) {                            │
│      // 같은 트랜잭션에서 둘 다 저장                                        │
│      orderRepository.save(order);                                           │
│      outboxRepository.save(new OutboxEvent("OrderCreated", ...));           │
│  }                                                                           │
│                                                                              │
│  // 별도 폴러가 outbox 테이블을 읽어서 Kafka에 발행                         │
│  @Scheduled                                                                  │
│  public void publishOutboxEvents() {                                        │
│      List<OutboxEvent> events = outboxRepository.findUnpublished();         │
│      for (OutboxEvent event : events) {                                     │
│          kafkaTemplate.send(event.getTopic(), event.getPayload());          │
│          event.markPublished();                                             │
│      }                                                                       │
│  }                                                                           │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 8.2 Temporal에서 Outbox 패턴이 불필요한 이유

> **결론**: Temporal의 재시도 메커니즘이 Outbox 패턴을 대체합니다.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Temporal의 재시도가 Outbox를 대체                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  [Temporal Activity에서 DB + Kafka]                                         │
│                                                                              │
│  @Override                                                                  │
│  public void createOrderAndPublish(OrderRequest request) {                  │
│      // 1. DB 저장 (멱등성 보장)                                            │
│      Order order = orderService.createIdempotent(request);                  │
│                                                                              │
│      // 2. Kafka 발행                                                       │
│      kafkaTemplate.send("orders", new OrderCreatedEvent(order));            │
│  }                                                                           │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  시나리오 1: Kafka 발행 실패                                         │   │
│  │                                                                      │   │
│  │  DB 저장 ✅ → Kafka 발행 ❌ → Activity 실패                          │   │
│  │                                    ↓                                 │   │
│  │                           Temporal 재시도                            │   │
│  │                                    ↓                                 │   │
│  │  DB 저장 (멱등성으로 스킵) → Kafka 발행 ✅ → Activity 성공           │   │
│  │                                                                      │   │
│  │  결과: 최종적으로 둘 다 성공! ✅                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  시나리오 2: DB 저장 후 크래시                                       │   │
│  │                                                                      │   │
│  │  DB 저장 ✅ → 💥 서버 크래시 (Kafka 발행 전)                         │   │
│  │                                    ↓                                 │   │
│  │                    서버 재시작 + Temporal 복구                       │   │
│  │                                    ↓                                 │   │
│  │  Activity 재실행 (Event History에 완료 기록 없음)                    │   │
│  │                                    ↓                                 │   │
│  │  DB 저장 (멱등성으로 스킵) → Kafka 발행 ✅ → Activity 성공           │   │
│  │                                                                      │   │
│  │  결과: 최종적으로 둘 다 성공! ✅                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  시나리오 3: Kafka 발행 후 크래시 (주의!)                            │   │
│  │                                                                      │   │
│  │  DB 저장 ✅ → Kafka 발행 ✅ → 💥 크래시 (응답 반환 전)               │   │
│  │                                    ↓                                 │   │
│  │                    서버 재시작 + Temporal 복구                       │   │
│  │                                    ↓                                 │   │
│  │  Activity 재실행                                                     │   │
│  │                                    ↓                                 │   │
│  │  DB 저장 (멱등성으로 스킵) → Kafka 발행 (중복!)                      │   │
│  │                                                                      │   │
│  │  ⚠️ Kafka 이벤트 중복 발행 가능!                                     │   │
│  │  → Consumer 쪽에서 멱등성 처리 필요                                  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 8.3 비교: Outbox vs Temporal 재시도

| 항목 | Outbox 패턴 | Temporal + 멱등성 |
|------|-------------|-------------------|
| DB + 이벤트 원자성 | ✅ 같은 트랜잭션 | ✅ 재시도로 보장 |
| 추가 테이블 | ❌ outbox 테이블 필요 | ✅ 불필요 |
| 폴러/스케줄러 | ❌ 별도 구현 필요 | ✅ 불필요 |
| 코드 복잡도 | 높음 | 낮음 |
| 이벤트 순서 보장 | ✅ 가능 | ⚠️ 추가 처리 필요 |
| 이벤트 중복 가능성 | 낮음 | 있음 (Consumer 처리) |
| 지연 시간 | 폴링 주기에 의존 | 즉시 |

### 8.4 권장 구현 패턴

```java
/**
 * Temporal + Kafka 권장 패턴
 * Producer와 Consumer 양쪽에서 멱등성 보장
 */
@Component
@RequiredArgsConstructor
public class OrderActivitiesImpl implements OrderActivities {

    private final OrderRepository orderRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final IdempotencyService idempotencyService;

    @Override
    public OrderResult createOrderAndPublish(OrderRequest request) {
        ActivityInfo info = Activity.getExecutionContext().getInfo();
        String baseKey = info.getWorkflowId() + "-" + info.getActivityId();

        // 1. DB 저장 (Producer 멱등성)
        Order order = idempotencyService.executeIdempotent(
            "order-create-" + baseKey,
            () -> orderRepository.save(new Order(request))
        );

        // 2. Kafka 발행 (발행 자체도 멱등하게)
        String eventKey = "order-event-" + baseKey;
        idempotencyService.executeIdempotent(
            eventKey,
            () -> {
                OrderCreatedEvent event = new OrderCreatedEvent(
                    order.getId(),
                    order.getProductId(),
                    order.getQuantity(),
                    eventKey  // 이벤트에 멱등성 키 포함
                );
                kafkaTemplate.send("order-events", event);
                return null;
            }
        );

        return OrderResult.success(order.getId());
    }
}

/**
 * Consumer 쪽 멱등성 처리
 */
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final ProcessedEventRepository processedEventRepository;
    private final InventoryService inventoryService;

    @KafkaListener(topics = "order-events")
    public void handleOrderCreated(OrderCreatedEvent event) {
        // 이미 처리된 이벤트인지 확인
        if (processedEventRepository.existsByKey(event.getIdempotencyKey())) {
            log.info("이미 처리된 이벤트 스킵: {}", event.getIdempotencyKey());
            return;
        }

        // 이벤트 처리
        inventoryService.handleOrderCreated(event);

        // 처리 완료 기록
        processedEventRepository.save(new ProcessedEvent(event.getIdempotencyKey()));
    }
}
```

### 8.5 Outbox 패턴이 여전히 유용한 경우

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Outbox가 여전히 필요한 경우                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. 이벤트 순서 보장이 필수인 경우                                          │
│     ─────────────────────────────────                                       │
│     • 같은 엔티티에 대한 이벤트가 순서대로 처리되어야 할 때                  │
│     • 예: UserCreated → UserUpdated → UserDeleted                          │
│     • Outbox 테이블의 auto-increment ID로 순서 보장                         │
│                                                                              │
│  2. 대량 이벤트 발행 시 성능                                                │
│     ────────────────────────                                                │
│     • 배치로 여러 이벤트를 한번에 발행할 때                                  │
│     • CDC(Change Data Capture) 도구와 연동할 때                             │
│     • 예: Debezium + Kafka Connect                                          │
│                                                                              │
│  3. 기존 Outbox 인프라가 있는 경우                                          │
│     ───────────────────────────────                                         │
│     • 이미 Outbox 기반으로 구축된 시스템                                    │
│     • Temporal 도입 시 점진적 마이그레이션                                   │
│                                                                              │
│  4. 외부 시스템과의 통합                                                    │
│     ─────────────────────                                                   │
│     • Temporal 외부에서 이벤트를 발행해야 하는 경우                         │
│     • 비-Temporal 서비스와의 통합                                           │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 8.6 정리

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              핵심 결론                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Q: Activity에서 Kafka 이벤트를 발행할 때 Outbox 패턴이 필요한가?           │
│                                                                              │
│  A: ❌ 대부분의 경우 불필요                                                 │
│                                                                              │
│  이유:                                                                       │
│  • Temporal 재시도가 "최종 일관성" 보장                                     │
│  • Activity 실패 시 Temporal이 재시도 → 결국 Kafka 발행 성공               │
│  • Outbox 테이블, 폴러 코드 불필요                                          │
│                                                                              │
│  대신 필요한 것:                                                            │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                                                                      │   │
│  │  Producer 측 (Activity)                                             │   │
│  │  • DB 저장 멱등성                                                    │   │
│  │  • Kafka 발행 멱등성 (선택적)                                        │   │
│  │                                                                      │   │
│  │  Consumer 측 (Kafka Listener)                                       │   │
│  │  • 이벤트 처리 멱등성 (필수!)                                        │   │
│  │  • 중복 이벤트 감지 및 스킵                                          │   │
│  │                                                                      │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  삭제 가능한 것:                                                            │
│  • outbox 테이블                                                            │
│  • OutboxRepository                                                         │
│  • 폴러/스케줄러 코드                                                       │
│  • Outbox 관련 설정                                                         │
│                                                                              │
│  ★ Temporal이 "재시도"를 보장하므로, 최종적으로 이벤트는 발행됨             │
│  ★ 단, 이벤트가 중복 발행될 수 있으므로 Consumer 멱등성 필수                │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 정리

### 핵심 메시지

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              핵심 메시지                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. Temporal은 "Workflow 실행"을 책임지고,                                  │
│     개발자는 "Activity 내부 안전성"을 책임진다.                              │
│                                                                              │
│  2. 모든 Activity는 멱등성을 보장해야 한다.                                  │
│     → 재시도는 언제든 발생할 수 있다.                                        │
│                                                                              │
│  3. 공유 리소스 접근 시 동시성 제어가 필수다.                                │
│     → 각 Workflow는 서로의 존재를 모른다.                                    │
│                                                                              │
│  4. Saga의 격리 문제를 인식하고 대응해야 한다.                               │
│     → Semantic Lock, 예약 패턴 활용.                                        │
│                                                                              │
│  5. Phase 2에서 배운 기술들은 Temporal과 함께 사용된다.                      │
│     → 분산 락, 멱등성, 낙관적 락 등은 여전히 필요하다.                       │
│                                                                              │
│  6. Outbox 패턴은 Temporal 재시도로 대체 가능하다.                           │
│     → 단, Consumer 측 멱등성은 필수.                                        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 관련 문서

- [00-temporal-deep-dive.md](./00-temporal-deep-dive.md) - Temporal 기본 개념
- [03-temporal-limitations.md](./03-temporal-limitations.md) - Temporal 한계 개요
- [Phase 2-A 분산 락](../phase2a/08-distributed-lock.md) - 분산 락 상세
- [Phase 2-A Saga Isolation](../phase2a/11-saga-isolation.md) - Saga 격리 문제

