# Activity 설계 가이드

> **목적**: Temporal이 해결하지 못하는 문제들을 Activity 레벨에서 설계하는 실전 가이드
> **전제**: Temporal 기본 개념 + Phase 2-A 분산 락/멱등성 학습 완료

---

## 목차

1. [Temporal vs Activity 책임 분리](#1-temporal-vs-activity-책임-분리)
2. [멱등성 설계](#2-멱등성-설계)
3. [동시성 제어](#3-동시성-제어)
4. [Saga 격리: Semantic Lock](#4-saga-격리-semantic-lock)
5. [서킷 브레이커 + Temporal](#5-서킷-브레이커--temporal)
6. [실전 Activity 구현 템플릿](#6-실전-activity-구현-템플릿)
7. [체크리스트와 안티패턴](#7-체크리스트와-안티패턴)
8. [Outbox 패턴 vs Temporal](#8-outbox-패턴-vs-temporal)

---

## 1. Temporal vs Activity 책임 분리

### 핵심 다이어그램

```
┌──────────────────────────────────────────────────────────────────┐
│                  Temporal vs Activity 책임 분리                    │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  Temporal Server 영역 (자동)                                │  │
│  │                                                             │  │
│  │  - Workflow 실행 순서 보장                                  │  │
│  │  - Activity 재시도 (Retry Policy)                           │  │
│  │  - 상태 저장 및 복구 (Event History)                        │  │
│  │  - Saga 보상 순서 관리                                      │  │
│  │  - Workflow ID 기반 중복 시작 방지                           │  │
│  │  - 타임아웃 관리                                            │  │
│  └──────────────────────────┬─────────────────────────────────┘  │
│                              │ Activity 호출                      │
│                              v                                    │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  Activity 구현체 영역 (개발자 책임)                          │  │
│  │                                                             │  │
│  │  1. 멱등성       - 이중 결제/예약 방지                      │  │
│  │  2. 동시성 제어  - Race Condition, 오버셀링 방지            │  │
│  │  3. 데이터 정합성 - Saga 중간 상태 격리                     │  │
│  │  4. 비즈니스 검증 - 도메인 규칙, 입력값 검증                │  │
│  └────────────────────────────────────────────────────────────┘  │
│                                                                   │
│  핵심: Temporal은 "호출"과 "재시도"만 담당                        │
│        Activity 내부 로직의 "안전성"은 개발자 책임                 │
│        각 Workflow는 서로의 존재를 모름 (동시성 제어 불가)         │
│                                                                   │
└──────────────────────────────────────────────────────────────────┘
```

---

## 2. 멱등성 설계

### 2.1 왜 필요한가?

```
Worker                            결제 서비스
  │ ── 1. 결제 요청 ───────────> │
  │    (100,000원, orderId: O-123) │ 2. 카드사 승인 완료
  │ <── 3. 응답 반환 중... ────── │
  │         X 네트워크 끊김!      │
  │                                │
  │ [Temporal: 타임아웃! 재시도!]  │
  │ ── 4. 결제 요청 (재시도) ───> │ 5. 또 결제???
  │                                │
  멱등성 없이: 200,000원 이중 결제!
  멱등성 있으면: "이미 처리됨" 반환
```

### 2.2 Idempotency Key 5원칙

| 원칙 | 설명 | 좋은 예 | 나쁜 예 |
|------|------|---------|---------|
| 유일성 | 같은 작업 = 같은 키 | `payment-{orderId}` | `UUID.randomUUID()` |
| 결정성 | 같은 입력 = 같은 키 | `orderId + productId` | `orderId + currentTimeMillis()` |
| 범위 한정 | 너무 넓지도, 좁지도 않게 | `reserve-{productId}-{orderId}` | `reserve-{productId}` (전체 공유) |
| TTL 관리 | Activity 타임아웃 + 여유 | timeout 5분 -> TTL 10분 | 영원히 저장 |
| 결과 저장 | 키와 함께 결과 캐싱 | `{key, result, expiresAt}` | 키만 저장 |

### 2.3 Temporal에서 키 생성 전략

```java
// 전략 1: Workflow ID + Activity ID (권장)
ActivityInfo info = Activity.getExecutionContext().getInfo();
String key = String.format("%s-%s-%s",
    "payment",              // 비즈니스 접두사
    info.getWorkflowId(),   // "order-O123"
    info.getActivityId()    // "1"
);
// 결과: "payment-order-O123-1"

// 전략 2: 비즈니스 키 기반 (외부 시스템 요구 시)
String key = String.format("reserve-%s-%s", orderId, productId);

// 전략 3: 해시 기반 (복잡한 입력)
String hash = DigestUtils.sha256Hex(objectMapper.writeValueAsString(request));
String key = "activity-" + hash.substring(0, 16);
```

### 2.4 멱등성 서비스 구현 (Redis)

```java
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    public <T> T executeIdempotent(String key, Supplier<T> operation, Duration ttl) {
        RBucket<String> bucket = redisson.getBucket("idempotency:" + key);

        // 1. 캐시 확인
        String cached = bucket.get();
        if (cached != null) return deserialize(cached);

        // 2. 락 획득 (동시 요청 방지)
        RLock lock = redisson.getLock("idempotency-lock:" + key);
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new IdempotencyLockException("락 획득 실패: " + key);
            }

            // 3. Double-Check
            cached = bucket.get();
            if (cached != null) return deserialize(cached);

            // 4. 실행 + 결과 저장
            T result = operation.get();
            bucket.set(serialize(result), ttl.toMillis(), TimeUnit.MILLISECONDS);
            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdempotencyException("인터럽트", e);
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }
}
```

### 2.5 Activity에서 적용 예시

```java
@Override
public PaymentResult processPayment(String orderId, BigDecimal amount) {
    ActivityInfo info = Activity.getExecutionContext().getInfo();
    String idempotencyKey = String.format("payment-%s-%s",
        info.getWorkflowId(), info.getActivityId());

    return idempotencyService.executeIdempotent(
        idempotencyKey,
        () -> {
            PaymentRequest request = PaymentRequest.builder()
                .orderId(orderId).amount(amount)
                .idempotencyKey(idempotencyKey)  // 외부 API에도 전달
                .build();
            return paymentClient.charge(request);
        },
        Duration.ofMinutes(10)
    );
}
```

---

## 3. 동시성 제어

### 3.1 3가지 패턴 비교

```
┌──────────────────────────────────────────────────────────────────┐
│                     동시성 제어 3가지 패턴                         │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│  패턴 1: 분산 락 (Distributed Lock)                               │
│  ─────────────────────────────────                                │
│  Workflow 1 ──락 획득──> [작업] ──락 해제──>                      │
│  Workflow 2 ──락 시도──| (대기) ──락 획득──> [작업] ──락 해제──>  │
│  장점: 확실한 순차 처리  |  단점: 대기 시간, 데드락 가능          │
│  사용: 재고 예약, 결제 등 임계 구역 보호                          │
│                                                                   │
│  패턴 2: DB Atomic UPDATE (Compare-and-Set)                       │
│  ──────────────────────────────────────────                        │
│  UPDATE inventory SET quantity = quantity - 1                     │
│  WHERE product_id = 'P1' AND quantity >= 1                       │
│  -> affected rows = 0이면 재고 부족                               │
│  장점: 락 없이 원자적, 성능 좋음  |  단점: 복잡한 로직 부적합     │
│  사용: 단순 카운터 증감, 상태 전이                                │
│                                                                   │
│  패턴 3: 낙관적 락 (Optimistic Lock + @Version)                   │
│  ─────────────────────────────────────────────                     │
│  SELECT ... -> version: 5                                        │
│  UPDATE ... SET version = 6 WHERE version = 5                    │
│  -> affected rows = 0이면 충돌 -> Temporal 재시도                 │
│  장점: 읽기 성능 좋음  |  단점: 충돌 시 재시도 필요               │
│  사용: 읽기 많고 쓰기 적을 때                                     │
│                                                                   │
└──────────────────────────────────────────────────────────────────┘
```

### 3.2 분산 락 구현

```java
@Component
@RequiredArgsConstructor
public class InventoryActivitiesImpl implements InventoryActivities {

    private final RedissonClient redisson;
    private final InventoryRepository inventoryRepository;
    private final IdempotencyService idempotencyService;

    @Override
    public ReservationResult reserveStock(String productId, int quantity, String orderId) {
        ActivityInfo info = Activity.getExecutionContext().getInfo();
        String idempotencyKey = String.format("reserve-%s-%s", info.getWorkflowId(), productId);

        return idempotencyService.executeIdempotent(idempotencyKey,
            () -> reserveStockWithLock(productId, quantity, orderId),
            Duration.ofMinutes(10));
    }

    private ReservationResult reserveStockWithLock(String productId, int quantity, String orderId) {
        String lockKey = "inventory:lock:" + productId;  // 상품별 락
        RLock lock = redisson.getLock(lockKey);
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new LockAcquisitionException("재고 락 획득 실패: " + productId);
            }
            // === 임계 구역 ===
            Inventory inventory = inventoryRepository.findByProductId(productId).orElseThrow();
            if (inventory.getQuantity() < quantity)
                throw new InsufficientStockException("재고 부족");
            inventory.decreaseQuantity(quantity);
            inventoryRepository.save(inventory);
            // === 임계 구역 끝 ===
            return ReservationResult.success(/* reservationId */);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ActivityException("락 대기 중 인터럽트", e);
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }
}
```

### 3.3 DB Atomic UPDATE 패턴

```java
@Repository
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    @Modifying
    @Query("""
        UPDATE Inventory i SET i.quantity = i.quantity - :amount, i.updatedAt = CURRENT_TIMESTAMP
        WHERE i.productId = :productId AND i.quantity >= :amount
        """)
    int decreaseStock(@Param("productId") String productId, @Param("amount") int amount);
}

// Activity에서 사용
int affected = inventoryRepository.decreaseStock(productId, quantity);
if (affected == 0) throw new InsufficientStockException(productId);
```

### 3.4 락 키 설계 원칙

```
원칙: "락의 범위 = 보호하려는 리소스의 범위"

좋은 예:
  "inventory:lock:{productId}"  -> 상품별 (다른 상품은 병렬)
  "point:lock:{userId}"        -> 사용자별
  "order:lock:{orderId}"       -> 주문별

나쁜 예:
  "inventory:lock"                     -> 전체 락 = 병목!
  "inventory:lock:{productId}:{orderId}" -> 너무 좁음 = 보호 안 됨
  "order:lock:{orderId}"로 재고 보호    -> 대상 불일치
```

---

## 4. Saga 격리: Semantic Lock

### 4.1 문제: Saga의 격리 부재

2PC는 중간 상태가 외부에 노출되지 않지만, Saga는 각 단계가 즉시 커밋된다.

| 문제 | 설명 |
|------|------|
| Dirty Read | Saga A가 보상 중인데, Saga B가 중간 상태를 읽음 |
| Lost Update | Saga A, B가 같은 값을 읽고 각자 수정 -> 한쪽 변경 손실 |
| Non-Repeatable Read | 같은 Saga 내에서 두 번 조회 시 결과가 다름 |

### 4.2 Semantic Lock 패턴

```
핵심: 데이터에 "처리 중" 상태를 표시하여 다른 Saga가 인식하게 한다

기존 재고 테이블:
  product_id | quantity |
  P1         | 10       | <- 누군가 처리 중인지 모름

Semantic Lock 적용 후:
  product_id | quantity | reserved_qty | lock_holder |
  P1         | 10       | 5            | order-123   |

  가용 재고 = quantity - reserved_qty = 5

동작:
  예약 시:   reserved_qty += 요청수량, lock_holder = orderId
  확정 시:   quantity -= amount, reserved_qty -= amount, lock_holder = null
  취소 시:   reserved_qty -= amount, lock_holder = null (quantity 그대로)
```

### 4.3 엔티티 구현

```java
@Entity
public class Inventory {
    private String productId;
    private int quantity;
    private int reservedQuantity = 0;
    private String lockHolder;
    @Version private Long version;

    public int getAvailableQuantity() {
        return quantity - reservedQuantity;
    }

    public void reserve(int amount, String orderId) {
        if (getAvailableQuantity() < amount)
            throw new InsufficientStockException("가용 재고 부족");
        this.reservedQuantity += amount;
        this.lockHolder = orderId;
    }

    public void confirmReservation(int amount) {
        this.quantity -= amount;
        this.reservedQuantity -= amount;
        if (this.reservedQuantity == 0) this.lockHolder = null;
    }

    public void cancelReservation(int amount) {
        this.reservedQuantity -= amount;
        if (this.reservedQuantity <= 0) {
            this.reservedQuantity = 0;
            this.lockHolder = null;
        }
    }
}
```

### 4.4 Workflow에서 사용

```java
public class OrderWorkflowImpl implements OrderWorkflow {
    @Override
    public OrderResult processOrder(OrderRequest request) {
        Saga saga = new Saga(new Saga.Options.Builder().build());
        try {
            String orderId = activities.createOrder(request);
            saga.addCompensation(() -> activities.cancelOrder(orderId));

            // Semantic Lock: reserved_qty 증가 (실제 차감 아님)
            String reservationId = activities.reserveStock(
                request.productId(), request.quantity(), orderId);
            saga.addCompensation(() -> activities.cancelReservation(reservationId));

            String paymentId = activities.processPayment(orderId, request.amount());
            saga.addCompensation(() -> activities.refundPayment(paymentId));

            // 성공! reserved_qty -> quantity 실제 차감
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

## 5. 서킷 브레이커 + Temporal

### 5.1 필요한가?

**대부분의 경우 불필요하다.** Temporal의 RetryOptions + 타임아웃이 이미 재시도/대기 제한을 제공한다.

**서킷 브레이커가 필요한 3가지 경우:**

| 상황 | 문제 | 해결 |
|------|------|------|
| 외부 서비스 완전 장애 | Temporal이 계속 재시도 -> 리소스 낭비 | 서킷 열림 -> 빠른 실패 |
| 호출 비용이 비쌈 | 매 재시도마다 비용 발생 | 서킷 열리면 호출 자체 안 함 |
| 빠른 응답 필요 | 재시도 대기 시간이 너무 김 | 서킷 열리면 즉시 fallback |

### 5.2 조합 패턴

```java
@Component
@RequiredArgsConstructor
public class ExternalApiActivitiesImpl implements ExternalApiActivities {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final ExternalApiClient externalApiClient;

    @Override
    public ApiResponse callExternalApi(ApiRequest request) {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("externalApi");

        if (cb.getState() == CircuitBreaker.State.OPEN) {
            // 서킷 열림 -> 빠른 실패 -> Temporal이 backoff 재시도
            throw new CircuitBreakerOpenException("외부 API 일시 불가");
        }

        try {
            return cb.executeSupplier(() -> externalApiClient.call(request));
        } catch (CallNotPermittedException e) {
            throw new CircuitBreakerOpenException("서킷 브레이커 열림", e);
        }
    }
}
```

---

## 6. 실전 Activity 구현 템플릿

모든 보호 메커니즘이 적용된 완전한 템플릿:

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryActivitiesImpl implements InventoryActivities {

    private final RedissonClient redisson;
    private final InventoryRepository inventoryRepository;
    private final ReservationRepository reservationRepository;
    private final IdempotencyService idempotencyService;

    private static final long LOCK_WAIT_SECONDS = 5;
    private static final long LOCK_LEASE_SECONDS = 30;
    private static final Duration IDEMPOTENCY_TTL = Duration.ofMinutes(10);

    // ======================================================================
    // 정방향: 재고 예약
    // ======================================================================
    @Override
    public ReservationResult reserveStock(String productId, int quantity, String orderId) {
        // 1. 컨텍스트 추출 + 로깅
        ActivityInfo info = Activity.getExecutionContext().getInfo();
        log.info("재고 예약 시작: productId={}, workflowId={}", productId, info.getWorkflowId());

        // 2. 멱등성 보장 실행
        String idempotencyKey = String.format("reserve-%s-%s", info.getWorkflowId(), productId);
        return idempotencyService.executeIdempotent(
            idempotencyKey,
            () -> doReserveStock(productId, quantity, orderId),
            IDEMPOTENCY_TTL);
    }

    private ReservationResult doReserveStock(String productId, int quantity, String orderId) {
        // 3. 분산 락 획득
        RLock lock = redisson.getLock("inventory:lock:" + productId);
        try {
            if (!lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS)) {
                throw new LockAcquisitionException("재고 락 획득 실패: " + productId);
            }

            // === 임계 구역 시작 ===

            // 4. 비즈니스 검증
            Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

            if (inventory.getAvailableQuantity() < quantity) {
                throw new InsufficientStockException(
                    String.format("재고 부족: 요청=%d, 가용=%d",
                        quantity, inventory.getAvailableQuantity()));
            }

            // 5. Semantic Lock 적용
            inventory.reserve(quantity, orderId);
            inventoryRepository.save(inventory);

            // 6. 예약 레코드 생성
            Reservation reservation = Reservation.builder()
                .orderId(orderId).productId(productId)
                .quantity(quantity).status(ReservationStatus.PENDING)
                .build();
            reservationRepository.save(reservation);

            log.info("재고 예약 완료: reservationId={}", reservation.getId());
            return ReservationResult.success(reservation.getId());

            // === 임계 구역 끝 ===
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ActivityException("락 대기 중 인터럽트", e);
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    // ======================================================================
    // 확정 (Saga 성공)
    // ======================================================================
    @Override
    public void confirmReservation(String reservationId) {
        ActivityInfo info = Activity.getExecutionContext().getInfo();
        idempotencyService.executeIdempotent(
            String.format("confirm-%s-%s", info.getWorkflowId(), reservationId),
            () -> { doConfirmReservation(reservationId); return null; },
            IDEMPOTENCY_TTL);
    }

    @Transactional
    protected void doConfirmReservation(String reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) return; // 멱등성

        RLock lock = redisson.getLock("inventory:lock:" + reservation.getProductId());
        try {
            if (lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS)) {
                Inventory inventory = inventoryRepository
                    .findByProductId(reservation.getProductId()).orElseThrow();
                inventory.confirmReservation(reservation.getQuantity());
                reservation.confirm();
                inventoryRepository.save(inventory);
                reservationRepository.save(reservation);
            } else {
                throw new LockAcquisitionException("확정 락 획득 실패");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ActivityException("인터럽트", e);
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    // ======================================================================
    // 보상 (Saga 실패) - 보상도 멱등성 + 락 필수!
    // ======================================================================
    @Override
    public void cancelReservation(String reservationId) {
        ActivityInfo info = Activity.getExecutionContext().getInfo();
        idempotencyService.executeIdempotent(
            String.format("cancel-%s-%s", info.getWorkflowId(), reservationId),
            () -> { doCancelReservation(reservationId); return null; },
            IDEMPOTENCY_TTL);
    }

    @Transactional
    protected void doCancelReservation(String reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId).orElse(null);
        if (reservation == null || reservation.getStatus() == ReservationStatus.CANCELLED) return;

        RLock lock = redisson.getLock("inventory:lock:" + reservation.getProductId());
        try {
            if (lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS)) {
                Inventory inventory = inventoryRepository
                    .findByProductId(reservation.getProductId()).orElseThrow();
                inventory.cancelReservation(reservation.getQuantity());
                reservation.cancel();
                inventoryRepository.save(inventory);
                reservationRepository.save(reservation);
            } else {
                throw new LockAcquisitionException("취소 락 획득 실패");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ActivityException("인터럽트", e);
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }
}
```

---

## 7. 체크리스트와 안티패턴

### 7.1 Activity 구현 체크리스트

**멱등성:**
- [ ] Idempotency Key 생성 전략 결정 (Workflow ID + Activity ID 권장)
- [ ] 중복 실행 시 같은 결과 반환 확인
- [ ] 외부 API 호출 시 idempotencyKey 전달
- [ ] 보상 트랜잭션에도 멱등성 적용

**동시성 제어:**
- [ ] 공유 리소스 접근 시 락/atomic update 결정
- [ ] 락 키 범위 = 보호 리소스 범위 확인
- [ ] 락 타임아웃 설정 (대기 + 보유)
- [ ] 락 해제 보장 (finally 블록)
- [ ] 데드락 가능성 검토

**예외 처리:**
- [ ] 재시도 가능 vs 불가능 예외 구분
- [ ] `setDoNotRetry()`에 비즈니스 예외 등록
- [ ] 예외 메시지에 충분한 정보 포함

**로깅:**
- [ ] 시작/종료 + Workflow ID/Activity ID 포함
- [ ] 주요 결정 포인트 로그
- [ ] 에러 로그 (스택 트레이스)

**테스트:**
- [ ] 단위 테스트 (Activity 로직)
- [ ] 멱등성 테스트 (같은 입력 2번)
- [ ] 동시성 테스트 (멀티스레드)
- [ ] 실패 시나리오 테스트

### 7.2 안티패턴 6가지

| 안티패턴 | 나쁜 예 | 문제 |
|----------|---------|------|
| 1. 멱등성 없이 외부 호출 | `paymentClient.charge(orderId, amount)` | 재시도 시 이중 결제 |
| 2. 락 없이 읽기-수정-쓰기 | `find -> check -> set -> save` | 중간에 다른 Workflow 개입 |
| 3. 랜덤 멱등성 키 | `UUID.randomUUID()` | 재시도마다 다른 키 |
| 4. 락 해제 누락 | `lock(); do(); unlock();` (try-finally 없음) | 예외 시 락 영구 잠김 |
| 5. 보상에 멱등성 미적용 | `paymentClient.refund(id)` 직접 호출 | 이중 환불 |
| 6. Temporal 만능 가정 | "Temporal이 알아서 해주겠지" | Activity 내부 안전성은 개발자 책임 |

---

## 8. Outbox 패턴 vs Temporal

### 8.1 Outbox 패턴이란?

DB 저장과 이벤트 발행의 원자성 문제를 해결하는 패턴:

```
문제:  orderRepo.save(order);     // 성공
       kafka.send("orders", event); // 실패! -> DB와 이벤트 불일치

해결:  @Transactional {
         orderRepo.save(order);
         outboxRepo.save(event);   // 같은 트랜잭션
       }
       // 별도 폴러가 outbox -> Kafka 발행
```

### 8.2 Temporal에서 Outbox가 불필요한 이유

```
시나리오 1: Kafka 실패
  DB 저장 OK -> Kafka 실패 -> Activity 실패
  -> Temporal 재시도 -> DB (멱등성 스킵) -> Kafka OK

시나리오 2: DB 후 크래시
  DB 저장 OK -> 크래시 (Kafka 전)
  -> Temporal 복구 -> 재실행 -> DB (스킵) -> Kafka OK

시나리오 3: Kafka 후 크래시 (주의!)
  DB OK -> Kafka OK -> 크래시 (응답 전)
  -> Temporal 재실행 -> DB (스킵) -> Kafka 중복 발행!
  -> Consumer 쪽에서 멱등성 처리 필요
```

### 8.3 비교표

| 항목 | Outbox 패턴 | Temporal + 멱등성 |
|------|-------------|-------------------|
| DB+이벤트 원자성 | 같은 트랜잭션 | 재시도로 보장 |
| 추가 테이블 | outbox 테이블 필요 | 불필요 |
| 폴러/스케줄러 | 별도 구현 필요 | 불필요 |
| 코드 복잡도 | 높음 | 낮음 |
| 이벤트 순서 보장 | 가능 | 추가 처리 필요 |
| 이벤트 중복 | 낮음 | 있음 (Consumer 처리) |
| 지연 시간 | 폴링 주기 의존 | 즉시 |

### 8.4 결론

> **Temporal 사용 시 Outbox 패턴은 대부분 불필요하다.**

대신 필요한 것:
- **Producer (Activity)**: DB 저장 멱등성
- **Consumer (Kafka Listener)**: 이벤트 처리 멱등성 (필수!)

Outbox가 여전히 유용한 경우:
- 이벤트 순서 보장이 필수일 때 (CDC/Debezium 연동)
- 대량 배치 이벤트 발행
- 비-Temporal 서비스와의 통합

---

## 핵심 정리

```
1. Temporal은 "Workflow 실행"을 책임지고,
   개발자는 "Activity 내부 안전성"을 책임진다.

2. 모든 Activity는 멱등성을 보장해야 한다.
   -> 재시도는 언제든 발생할 수 있다.

3. 공유 리소스 접근 시 동시성 제어가 필수다.
   -> 각 Workflow는 서로의 존재를 모른다.

4. Saga의 격리 문제 -> Semantic Lock 패턴으로 대응한다.

5. Phase 2에서 배운 분산 락, 멱등성, 낙관적 락은
   Temporal 환경에서도 여전히 필요하다.
```

---

> **다음 학습**: [12-limitations-combo.md](./12-limitations-combo.md) - Temporal 한계와 조합 패턴
