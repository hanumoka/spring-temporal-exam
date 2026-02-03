# Saga Isolation (Saga 격리 문제)

## 개요

### What (무엇인가)
Saga 패턴은 ACID 트랜잭션과 달리 Isolation(격리성)을 보장하지 않습니다. 여러 Saga가 동시에 실행될 때 데이터 불일치 문제가 발생할 수 있습니다.

### Why (왜 중요한가)

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Saga의 근본적 한계                                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ACID 트랜잭션:                                                      │
│  ┌─────────────────────────────────────────┐                        │
│  │  BEGIN TRANSACTION                       │                        │
│  │  UPDATE inventory SET qty = qty - 10     │  ← 락 보유             │
│  │  UPDATE payment SET status = 'PAID'      │                        │
│  │  COMMIT                                   │  ← 동시 접근 차단      │
│  └─────────────────────────────────────────┘                        │
│                                                                      │
│  Saga 패턴:                                                          │
│  ┌─────────────────────────────────────────┐                        │
│  │  T1: 재고 예약 (COMMIT)                  │  ← 즉시 커밋          │
│  │  T2: 결제 처리 (COMMIT)                  │  ← 중간에 다른 Saga    │
│  │  T3: 주문 확정 (COMMIT)                  │     접근 가능!         │
│  └─────────────────────────────────────────┘                        │
│                                                                      │
│  문제: 각 단계가 독립 커밋 → 중간 상태 노출                          │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 1. Saga Isolation 문제 유형

### 1.1 Dirty Read (오염된 읽기)

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Dirty Read 문제                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  시간  │  Saga A                    │  Saga B                        │
│  ──────┼────────────────────────────┼────────────────────────────── │
│  T1    │  재고 읽기: 100개          │                                │
│  T2    │  재고 예약: 100→90 (COMMIT)│                                │
│  T3    │                            │  재고 읽기: 90개 ◀── A의 중간상태│
│  T4    │  결제 실패!                │  재고 예약: 90→80 (COMMIT)     │
│  T5    │  재고 복구: 90→100 (보상)  │                                │
│  T6    │                            │  결제 성공                     │
│  T7    │                            │  주문 확정                     │
│  ──────┼────────────────────────────┼────────────────────────────── │
│                                                                      │
│  결과: Saga A 실패로 재고 100 복구                                   │
│        Saga B는 90 기준으로 80 예약 → 실제 재고 100인데 80 표시      │
│        데이터 불일치!                                                │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 1.2 Lost Update (갱신 손실)

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Lost Update 문제                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  시간  │  Saga A                    │  Saga B                        │
│  ──────┼────────────────────────────┼────────────────────────────── │
│  T1    │  재고 읽기: 100개          │  재고 읽기: 100개              │
│  T2    │  재고 계산: 100-10 = 90    │  재고 계산: 100-15 = 85        │
│  T3    │  재고 저장: 90 (COMMIT)    │                                │
│  T4    │                            │  재고 저장: 85 (COMMIT)        │
│  ──────┼────────────────────────────┼────────────────────────────── │
│                                                                      │
│  기대 결과: 100 - 10 - 15 = 75                                       │
│  실제 결과: 85 (Saga A의 변경 손실!)                                 │
│                                                                      │
│  원인: 두 Saga가 같은 초기값(100)을 읽고 각자 계산                   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 1.3 Non-Repeatable Read (반복 불가능한 읽기)

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Non-Repeatable Read 문제                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  시간  │  Saga A                    │  Saga B                        │
│  ──────┼────────────────────────────┼────────────────────────────── │
│  T1    │  재고 읽기: 100개          │                                │
│  T2    │  비즈니스 로직 처리 중...  │  재고 변경: 100→50 (COMMIT)    │
│  T3    │  재고 다시 읽기: 50개 ◀── 값이 변경됨!                      │
│  ──────┼────────────────────────────┼────────────────────────────── │
│                                                                      │
│  문제: Saga A 실행 중 같은 데이터가 다르게 읽힘                      │
│        의사결정에 일관성 없는 데이터 사용                            │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 2. 해결 전략

### 2.1 Semantic Lock (의미적 잠금)

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Semantic Lock 전략                                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  개념: 데이터에 "처리 중" 플래그를 설정하여 다른 Saga 접근 제어       │
│                                                                      │
│  예약 상태 활용:                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  inventory 테이블                                            │   │
│  │  ┌────────┬──────────┬───────────────────┬────────────────┐ │   │
│  │  │ id     │ quantity │ reserved_quantity │ status         │ │   │
│  │  ├────────┼──────────┼───────────────────┼────────────────┤ │   │
│  │  │ 1      │ 100      │ 10                │ RESERVING      │ │   │
│  │  │        │          │                   │ (처리 중 플래그)│ │   │
│  │  └────────┴──────────┴───────────────────┴────────────────┘ │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  다른 Saga가 RESERVING 상태 데이터 접근 시:                          │
│  ├── 대기 (Wait)                                                    │
│  ├── 재시도 (Retry)                                                 │
│  └── 거절 (Reject)                                                  │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

#### 구현 예시

```java
@Entity
public class Inventory {

    @Id
    private Long id;

    private Long productId;
    private int quantity;
    private int reservedQuantity;

    @Enumerated(EnumType.STRING)
    private ReservationStatus status;  // AVAILABLE, RESERVING, RESERVED

    @Version
    private Long version;

    public enum ReservationStatus {
        AVAILABLE,   // 예약 가능
        RESERVING,   // 예약 진행 중 (Semantic Lock)
        RESERVED     // 예약 완료
    }
}

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    @Transactional
    public ReservationResult reserve(Long productId, int quantity, String sagaId) {
        Inventory inventory = inventoryRepository.findByProductIdWithLock(productId)
            .orElseThrow(() -> new ProductNotFoundException(productId));

        // Semantic Lock 체크
        if (inventory.getStatus() == ReservationStatus.RESERVING) {
            throw new ResourceBusyException("다른 Saga가 처리 중입니다. 재시도하세요.");
        }

        // Semantic Lock 설정
        inventory.setStatus(ReservationStatus.RESERVING);
        inventory.setSagaId(sagaId);
        inventoryRepository.save(inventory);

        // 실제 예약 로직
        if (inventory.getAvailableQuantity() < quantity) {
            // 실패 시 락 해제
            inventory.setStatus(ReservationStatus.AVAILABLE);
            inventory.setSagaId(null);
            inventoryRepository.save(inventory);
            throw new InsufficientStockException(productId, quantity);
        }

        inventory.reserve(quantity);
        inventory.setStatus(ReservationStatus.RESERVED);
        inventoryRepository.save(inventory);

        return new ReservationResult(true, inventory.getReservedQuantity());
    }

    @Transactional
    public void confirmReservation(Long productId, String sagaId) {
        Inventory inventory = inventoryRepository.findByProductId(productId)
            .orElseThrow();

        // Saga ID 검증 (다른 Saga의 예약 확정 방지)
        if (!sagaId.equals(inventory.getSagaId())) {
            throw new InvalidSagaException("해당 Saga의 예약이 아닙니다.");
        }

        inventory.confirmReservation();
        inventory.setStatus(ReservationStatus.AVAILABLE);
        inventory.setSagaId(null);
        inventoryRepository.save(inventory);
    }
}
```

### 2.2 Reread Values (값 재확인)

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Reread Values 전략                                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  개념: 업데이트 전 데이터가 변경되지 않았는지 재확인                  │
│                                                                      │
│  시간  │  Saga A                                                     │
│  ──────┼──────────────────────────────────────────────────────────  │
│  T1    │  재고 읽기: 100개, version=1                                │
│  T2    │  비즈니스 로직 처리...                                      │
│  T3    │  재고 다시 읽기: version 확인                               │
│        │  ├── version=1 (동일) → 계속 진행                          │
│        │  └── version≠1 (변경됨) → Saga 재시작                      │
│  T4    │  재고 업데이트 (version=2로 변경)                           │
│  ──────┼──────────────────────────────────────────────────────────  │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

#### 구현 예시

```java
@Service
@RequiredArgsConstructor
public class SagaOrchestrator {

    private final InventoryService inventoryService;

    public SagaResult execute(SagaRequest request) {
        // 1. 초기 상태 읽기
        InventorySnapshot initialSnapshot = inventoryService.getSnapshot(request.getProductId());

        // 2. 비즈니스 로직 처리
        processBusinessLogic(request);

        // 3. 업데이트 전 재확인
        InventorySnapshot currentSnapshot = inventoryService.getSnapshot(request.getProductId());

        if (!initialSnapshot.getVersion().equals(currentSnapshot.getVersion())) {
            // 데이터가 변경됨 - Saga 재시작 또는 실패 처리
            throw new StaleDataException("데이터가 변경되었습니다. 재시도하세요.");
        }

        // 4. 안전하게 업데이트
        inventoryService.reserve(request.getProductId(), request.getQuantity());

        return SagaResult.success();
    }
}
```

### 2.3 Commutative Updates (교환 가능한 업데이트)

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Commutative Updates 전략                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  개념: 순서와 무관하게 동일한 결과가 나오도록 설계                    │
│                                                                      │
│  [나쁜 예 - 순서 의존적]                                             │
│  Saga A: quantity = quantity - 10  (100 → 90)                        │
│  Saga B: quantity = quantity - 15  (90 → 75)                         │
│  결과: 75                                                            │
│                                                                      │
│  Saga B 먼저: quantity = quantity - 15  (100 → 85)                   │
│  Saga A 이후: quantity = quantity - 10  (85 → 75)                    │
│  결과: 75 (동일!)                                                    │
│                                                                      │
│  ───────────────────────────────────────────────────────────────    │
│                                                                      │
│  [좋은 예 - 원자적 연산]                                             │
│  UPDATE inventory                                                    │
│  SET quantity = quantity - :amount,                                  │
│      reserved_quantity = reserved_quantity + :amount                 │
│  WHERE product_id = :productId                                       │
│    AND quantity >= :amount  -- 동시성 안전                           │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

#### 구현 예시

```java
@Repository
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    // 원자적 예약 - 순서 무관하게 안전
    @Modifying
    @Query("""
        UPDATE Inventory i
        SET i.quantity = i.quantity - :amount,
            i.reservedQuantity = i.reservedQuantity + :amount,
            i.version = i.version + 1
        WHERE i.productId = :productId
          AND i.quantity >= :amount
        """)
    int atomicReserve(@Param("productId") Long productId,
                      @Param("amount") int amount);
}

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    @Transactional
    public boolean reserve(Long productId, int quantity) {
        int updated = inventoryRepository.atomicReserve(productId, quantity);

        if (updated == 0) {
            throw new InsufficientStockException(productId, quantity);
        }

        return true;
    }
}
```

### 2.4 Version File (버전 파일)

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Version File 전략                                 │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  개념: 모든 작업을 로그로 기록하고, 올바른 순서로 적용 보장           │
│                                                                      │
│  Saga Event Log:                                                    │
│  ┌───────┬────────────────┬─────────┬──────────┬────────────────┐  │
│  │ seq   │ saga_id        │ action  │ amount   │ status         │  │
│  ├───────┼────────────────┼─────────┼──────────┼────────────────┤  │
│  │ 1     │ SAGA-001       │ RESERVE │ 10       │ PENDING        │  │
│  │ 2     │ SAGA-002       │ RESERVE │ 15       │ PENDING        │  │
│  │ 3     │ SAGA-001       │ CONFIRM │ 10       │ COMPLETED      │  │
│  │ 4     │ SAGA-002       │ CANCEL  │ 15       │ COMPENSATED    │  │
│  └───────┴────────────────┴─────────┴──────────┴────────────────┘  │
│                                                                      │
│  순서 보장: seq로 정렬하여 순차 적용                                 │
│  충돌 해결: 같은 리소스에 대한 작업 시 seq 순서대로 처리             │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 3. 전략 조합 적용

### 3.1 이 프로젝트 적용

```
┌─────────────────────────────────────────────────────────────────────┐
│                    서비스별 Isolation 전략                           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [Inventory Service]                                                 │
│  ├── Semantic Lock: status = 'RESERVING' 플래그                     │
│  ├── Commutative Update: 원자적 UPDATE 쿼리                         │
│  └── 분산 락: RLock으로 동시 접근 차단 (04-distributed-lock)         │
│                                                                      │
│  [Order Service]                                                     │
│  ├── Reread Values: version 확인 후 업데이트                        │
│  └── 낙관적 락: @Version으로 충돌 감지                              │
│                                                                      │
│  [Payment Service]                                                   │
│  ├── 멱등성 Key: 중복 결제 방지                                     │
│  └── 세마포어: 동시 PG 호출 제한                                    │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2 Saga Orchestrator 개선

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderSagaOrchestrator {

    private final OrderServiceClient orderClient;
    private final InventoryServiceClient inventoryClient;
    private final PaymentServiceClient paymentClient;
    private final SagaEventLogger sagaEventLogger;

    public OrderSagaResult execute(OrderSagaRequest request) {
        String sagaId = UUID.randomUUID().toString();
        MDC.put("sagaId", sagaId);

        try {
            // 1. 주문 생성 (Semantic Lock 시작)
            Long orderId = createOrderWithLock(request, sagaId);

            // 2. 재고 예약 (원자적 업데이트 + Semantic Lock)
            reserveStockWithRetry(request, sagaId);

            // 3. 결제 처리 (멱등성 Key)
            processPaymentIdempotent(orderId, request, sagaId);

            // 4. 확정 (Semantic Lock 해제)
            confirmAll(orderId, sagaId);

            return OrderSagaResult.success(orderId);

        } catch (StaleDataException e) {
            log.warn("데이터 변경 감지, Saga 재시작: {}", sagaId);
            // 재시도 로직
            throw new SagaRetryException(e);

        } catch (ResourceBusyException e) {
            log.warn("리소스 사용 중, 잠시 후 재시도: {}", sagaId);
            throw new SagaRetryException(e);

        } catch (Exception e) {
            log.error("Saga 실패, 보상 시작: {}", sagaId, e);
            compensate(sagaId);
            throw e;
        } finally {
            MDC.remove("sagaId");
        }
    }

    private void reserveStockWithRetry(OrderSagaRequest request, String sagaId) {
        int maxRetries = 3;
        int attempt = 0;

        while (attempt < maxRetries) {
            try {
                inventoryClient.reserve(request.getProductId(),
                                        request.getQuantity(),
                                        sagaId);
                return;
            } catch (ResourceBusyException | StaleDataException e) {
                attempt++;
                if (attempt >= maxRetries) {
                    throw e;
                }
                sleep(100 * attempt);  // 백오프
            }
        }
    }
}
```

---

## 4. 핵심 학습 포인트

### 4.1 Saga Isolation 문제 인식

```
Saga를 설계할 때 반드시 고려해야 할 질문:

1. 두 Saga가 동시에 같은 데이터에 접근하면?
2. 한 Saga가 실패하고 보상 중일 때 다른 Saga가 접근하면?
3. 데이터 읽기와 쓰기 사이에 다른 Saga가 변경하면?
```

### 4.2 해결 전략 선택 기준

| 전략 | 적합한 상황 |
|------|------------|
| **Semantic Lock** | 긴 작업, 명확한 상태 전이 |
| **Reread Values** | 낙관적 동시성, 충돌 드문 경우 |
| **Commutative Update** | 순서 무관한 연산 |
| **Version File** | 감사 추적, 재처리 필요 |

### 4.3 Temporal과의 관계

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Temporal과 Saga Isolation                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Temporal이 해결하는 것:                                             │
│  ├── Saga 실행 흐름 관리                                            │
│  ├── 실패 시 자동 재시도                                            │
│  └── 보상 트랜잭션 순서 보장                                        │
│                                                                      │
│  Temporal이 해결 못하는 것 (직접 구현 필요):                         │
│  ├── 동시 Saga 간 데이터 충돌                                       │
│  ├── Dirty Read 방지                                                │
│  └── Lost Update 방지                                               │
│                                                                      │
│  결론: Temporal 사용해도 Saga Isolation 전략은 필요!                 │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

---

## 5. Semantic Lock의 실제 가치 (주의사항)

### 5.1 잘못된 이해

```
❌ 잘못된 이해:
"Saga A가 실패할 거였으니 Saga B도 가능했음"

문제:
├── Saga B는 A가 성공할지 실패할지 미리 알 수 없음
├── A가 성공하면 가용 재고 부족이 맞음
└── B가 거절된 것은 논리적으로 올바른 판단일 수 있음
```

### 5.2 올바른 이해

```
✓ 올바른 이해:
Semantic Lock은 "처리량 향상"이 아닌 "정보 제공"

┌─────────────────────────────────────────────────────────────────┐
│  Semantic Lock 없을 때                                           │
├─────────────────────────────────────────────────────────────────┤
│  Saga B: 가용 재고 5개 확인 → 10개 주문 → "재고 부족" 에러      │
│                                                                 │
│  B가 아는 정보:                                                 │
│  └── "재고가 부족하다"                                          │
│  └── 왜 부족한지 모름 (진짜 없는 건지? 누가 쓰고 있는 건지?)    │
│                                                                 │
│  B의 대응:                                                      │
│  └── "품절입니다" → 포기                                        │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  Semantic Lock 있을 때                                           │
├─────────────────────────────────────────────────────────────────┤
│  Saga B: status = RESERVING 확인 → "다른 주문 처리 중"          │
│                                                                 │
│  B가 아는 정보:                                                 │
│  └── "누군가 작업 중이다"                                       │
│  └── "일시적 상황일 수 있다"                                    │
│                                                                 │
│  B의 대응 선택:                                                 │
│  ├── "다른 주문 처리 중입니다. 잠시 후 재시도해주세요"          │
│  └── 또는 잠시 대기 후 자동 재시도                              │
└─────────────────────────────────────────────────────────────────┘
```

### 5.3 핵심 정리

```
Semantic Lock의 실제 가치:

1. 정보의 질 향상
   └── "재고 부족" vs "다른 주문 처리 중"
   └── 원인을 구분할 수 있음

2. 대응 방식 선택 가능
   └── 진짜 부족 → "품절" 안내
   └── 작업 중 → "잠시 후 재시도" 안내

3. 사용자 경험 개선
   └── "품절입니다" (포기 유도)
   └── "잠시 후 다시 시도해주세요" (재시도 유도)
```

---

---

## 6. 업계 표준 Countermeasures (2026-02-03 웹 검색 검증)

### 6.1 Microsoft Azure Architecture Center

```
┌─────────────────────────────────────────────────────────────────┐
│  Saga Pattern - Countermeasures                                  │
│  Source: learn.microsoft.com/azure/architecture/patterns/saga   │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. Semantic Lock                                               │
│     └── "application-level lock that indicates a record is      │
│          not committed and has potential to change"             │
│                                                                 │
│  2. Commutative Updates                                         │
│     └── "Design updates to be applied in any order"             │
│                                                                 │
│  3. Pessimistic View                                            │
│     └── "Reorder saga steps to minimize dirty reads"            │
│                                                                 │
│  4. Reread Value                                                │
│     └── "Verify data unchanged before overwriting"              │
│                                                                 │
│  5. Versioning                                                  │
│     └── "Conditional updates based on version field"            │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 6.2 microservices.io (Chris Richardson)

```
┌─────────────────────────────────────────────────────────────────┐
│  Saga Isolation Countermeasures                                  │
│  Source: microservices.io/patterns/data/saga.html               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  "Saga developers must use countermeasures - design techniques  │
│   that implement isolation"                                     │
│                                                                 │
│  핵심 인용:                                                     │
│  "The use of PENDING state is an example of what is known as    │
│   a semantic lock counter-measure. It prevents another          │
│   transaction/saga from updating the Order while it is in the   │
│   process of being created."                                    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 6.3 학술적 근거

```
┌─────────────────────────────────────────────────────────────────┐
│  논문: "Semantic ACID properties in multidatabases"             │
│  저자: Lars Frank, Torben U. Zahle (1998)                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  분산 트랜잭션 없이 멀티 데이터베이스 환경에서                   │
│  트랜잭션 격리 부족을 처리하는 방법 제시                        │
│                                                                 │
│  → Semantic Lock 개념의 학술적 기반                             │
│  → Saga 패턴의 countermeasure로 널리 인용됨                     │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 6.4 현재 프로젝트와의 매핑

| 업계 표준 Countermeasure | 현재 프로젝트 구현 | 상태 |
|-------------------------|-------------------|------|
| Semantic Lock | `ReservationStatus` + `sagaId` | ⬜ 구현 예정 |
| Versioning | `@Version` 필드 | ✅ 구현됨 |
| Reread Value | RLock 내부에서 조회 | ✅ 구현됨 |
| Commutative Updates | `reservedQuantity` 증감 | ✅ 구현됨 |
| Pessimistic View | 해당 없음 | - |

---

## 관련 문서

- [D020 Saga Isolation](../../architecture/DECISIONS.md#d020-saga-isolation-전략)
- [01-saga-pattern.md](./01-saga-pattern.md)
- [04-distributed-lock.md](./04-distributed-lock.md)
- [04-2-lock-strategy.md](./04-2-lock-strategy.md) - 락 전략 통합 가이드
- [05-optimistic-lock.md](./05-optimistic-lock.md)
- [12-redis-lock-pitfalls.md](./12-redis-lock-pitfalls.md) - Redis 락 함정
