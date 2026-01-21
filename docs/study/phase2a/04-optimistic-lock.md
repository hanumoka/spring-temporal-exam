# 낙관적 락 (Optimistic Lock)

## 이 문서에서 배우는 것

- 낙관적 락과 비관적 락의 차이
- JPA @Version을 활용한 낙관적 락 구현
- 충돌 처리 방법
- 분산 락과의 조합

---

## 1. 낙관적 락 vs 비관적 락

### 비관적 락 (Pessimistic Lock)

**"충돌이 발생할 것이라고 가정"**하고 먼저 락을 겁니다.

```java
// SELECT ... FOR UPDATE
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Product p WHERE p.id = :id")
Product findByIdForUpdate(@Param("id") Long id);
```

```
트랜잭션 A                    트랜잭션 B
    │                             │
    │  락 획득                    │  락 대기...
    │  데이터 읽기                │       │
    │  데이터 수정                │       │
    │  커밋                       │       │
    │  락 해제                    │  락 획득!
    │                             │  데이터 읽기
```

**장점**: 충돌 시 데이터 정합성 보장
**단점**: 락 대기로 인한 성능 저하, 데드락 가능성

### 낙관적 락 (Optimistic Lock)

**"충돌이 드물 것이라고 가정"**하고 커밋 시점에 충돌을 감지합니다.

```java
@Entity
public class Product {
    @Id
    private Long id;

    @Version  // 버전 필드
    private Long version;

    private int stock;
}
```

```
트랜잭션 A                    트랜잭션 B
    │                             │
    │  읽기 (version=1)           │  읽기 (version=1)
    │  수정                       │  수정
    │  커밋 (version=2)           │  커밋 시도
    │  ✓ 성공                     │  ✗ 충돌! (version 불일치)
```

**장점**: 락 없이 동시 처리 가능, 성능 우수
**단점**: 충돌 시 재시도 필요

### 선택 기준

| 상황 | 권장 방식 |
|------|----------|
| 충돌이 드문 경우 | 낙관적 락 |
| 충돌이 빈번한 경우 | 비관적 락 또는 분산 락 |
| 읽기가 많고 쓰기가 적은 경우 | 낙관적 락 |
| 분산 환경 (멀티 서버) | 분산 락 + 낙관적 락 |

---

## 2. JPA @Version

### 동작 원리

```java
@Entity
public class Product {
    @Id
    @GeneratedValue
    private Long id;

    private String name;
    private int stock;

    @Version  // 핵심!
    private Long version;
}
```

**JPA가 자동으로 처리**:
1. 엔티티 조회 시 `version` 값 읽음
2. 업데이트 시 `WHERE version = ?` 조건 추가
3. 업데이트된 행이 없으면 `OptimisticLockException` 발생

```sql
-- JPA가 생성하는 SQL
UPDATE product
SET stock = 99, version = 2
WHERE id = 1 AND version = 1  -- version 조건!

-- 다른 트랜잭션이 먼저 수정했다면 version이 달라져서 0 rows affected
```

### 지원되는 타입

```java
@Version
private Long version;       // Long (권장)

@Version
private Integer version;    // Integer

@Version
private Short version;      // Short

@Version
private Timestamp version;  // Timestamp (수정 시간 기반)
```

---

## 3. 구현 예시

### 3.1 엔티티 정의

```java
// service-order/src/main/java/com/example/order/domain/Order.java
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderNumber;

    @Column(nullable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(nullable = false)
    private BigDecimal totalAmount;

    @Version  // 낙관적 락
    private Long version;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public void confirm() {
        if (this.status != OrderStatus.PENDING) {
            throw new IllegalStateException("PENDING 상태만 확정 가능합니다");
        }
        this.status = OrderStatus.CONFIRMED;
        this.updatedAt = LocalDateTime.now();
    }

    public void cancel() {
        if (this.status == OrderStatus.CONFIRMED) {
            throw new IllegalStateException("확정된 주문은 취소할 수 없습니다");
        }
        this.status = OrderStatus.CANCELLED;
        this.updatedAt = LocalDateTime.now();
    }
}
```

### 3.2 서비스에서 사용

```java
@Service
@RequiredArgsConstructor
@Transactional
public class OrderService {

    private final OrderRepository orderRepository;

    public void confirmOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));

        order.confirm();
        // save() 호출 시 @Version 검증
        orderRepository.save(order);
    }
}
```

### 3.3 충돌 처리

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(
            OptimisticLockingFailureException e) {

        log.warn("낙관적 락 충돌 발생: {}", e.getMessage());

        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(new ErrorResponse(
                "CONFLICT",
                "다른 사용자가 먼저 수정했습니다. 다시 시도해주세요."
            ));
    }
}
```

---

## 4. 재시도 구현

### 4.1 수동 재시도

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final int MAX_RETRIES = 3;

    public void confirmOrderWithRetry(Long orderId) {
        int attempt = 0;

        while (attempt < MAX_RETRIES) {
            try {
                confirmOrder(orderId);
                return;  // 성공!
            } catch (OptimisticLockingFailureException e) {
                attempt++;
                if (attempt >= MAX_RETRIES) {
                    throw new ConcurrentModificationException(
                        "동시 수정으로 인해 처리 실패 (재시도 " + MAX_RETRIES + "회 초과)"
                    );
                }
                log.warn("낙관적 락 충돌, 재시도 {}/{}", attempt, MAX_RETRIES);
                // 잠시 대기 후 재시도
                sleep(100 * attempt);  // 백오프
            }
        }
    }
}
```

### 4.2 Spring Retry 활용

```groovy
// build.gradle
implementation 'org.springframework.retry:spring-retry'
implementation 'org.springframework.boot:spring-boot-starter-aop'
```

```java
@Configuration
@EnableRetry
public class RetryConfig {
}

@Service
public class OrderService {

    @Retryable(
        retryFor = OptimisticLockingFailureException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2)
    )
    @Transactional
    public void confirmOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
        order.confirm();
        orderRepository.save(order);
    }

    @Recover
    public void recoverConfirmOrder(OptimisticLockingFailureException e, Long orderId) {
        log.error("주문 확정 실패 (재시도 초과): orderId={}", orderId);
        throw new ConcurrentModificationException("동시 수정으로 인해 처리 실패");
    }
}
```

---

## 5. 분산 락과 조합

### 왜 조합하는가?

```
[분산 락만 사용]
- 같은 서버의 다른 요청은 제어 가능
- 하지만 락 획득 실패 시 재시도하는 동안 다른 요청이 수정할 수 있음

[낙관적 락만 사용]
- 단일 서버에서는 잘 동작
- 분산 환경에서는 충돌이 많이 발생할 수 있음

[조합 사용]
- 분산 락: 동시 접근 자체를 줄임
- 낙관적 락: 만약의 경우를 대비한 2차 방어
```

### 구현 예시

```java
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final RedissonClient redissonClient;
    private final InventoryRepository inventoryRepository;

    @Transactional
    public void decreaseStock(Long productId, int quantity) {
        String lockKey = "lock:inventory:" + productId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new LockAcquisitionException("락 획득 실패");
            }

            // 분산 락 획득 후에도 낙관적 락으로 2차 검증
            Inventory inventory = inventoryRepository.findById(productId)
                .orElseThrow();

            inventory.decrease(quantity);
            inventoryRepository.save(inventory);  // @Version 검증

        } catch (OptimisticLockingFailureException e) {
            // 분산 락 내에서도 충돌 가능 (드물지만)
            log.warn("낙관적 락 충돌 (분산 락 내부): productId={}", productId);
            throw e;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

---

## 6. LockModeType 상세

### JPA 락 모드

```java
public enum LockModeType {
    NONE,                    // 락 없음
    OPTIMISTIC,              // 낙관적 락 (읽기)
    OPTIMISTIC_FORCE_INCREMENT,  // 낙관적 락 + 버전 증가
    PESSIMISTIC_READ,        // 비관적 읽기 락 (공유 락)
    PESSIMISTIC_WRITE,       // 비관적 쓰기 락 (배타 락)
    PESSIMISTIC_FORCE_INCREMENT  // 비관적 락 + 버전 증가
}
```

### Repository에서 사용

```java
public interface OrderRepository extends JpaRepository<Order, Long> {

    // 비관적 락
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdWithPessimisticLock(@Param("id") Long id);

    // 낙관적 락 (명시적)
    @Lock(LockModeType.OPTIMISTIC)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdWithOptimisticLock(@Param("id") Long id);
}
```

### EntityManager에서 사용

```java
@Repository
@RequiredArgsConstructor
public class OrderRepositoryCustom {

    private final EntityManager em;

    public Order findByIdWithLock(Long id, LockModeType lockMode) {
        return em.find(Order.class, id, lockMode);
    }

    public void lock(Order order, LockModeType lockMode) {
        em.lock(order, lockMode);
    }
}
```

---

## 7. 주의사항

### 7.1 @Version 필드 직접 수정 금지

```java
// ✗ 잘못된 예: 직접 version 수정
order.setVersion(order.getVersion() + 1);

// ✓ 올바른 예: JPA가 자동 관리
// version 필드는 건드리지 않음
```

### 7.2 벌크 업데이트 시 주의

```java
// ✗ 벌크 업데이트는 @Version을 무시함!
@Modifying
@Query("UPDATE Order o SET o.status = :status WHERE o.id = :id")
void updateStatus(@Param("id") Long id, @Param("status") OrderStatus status);

// ✓ 버전도 함께 업데이트
@Modifying
@Query("UPDATE Order o SET o.status = :status, o.version = o.version + 1 " +
       "WHERE o.id = :id AND o.version = :version")
int updateStatusWithVersion(
    @Param("id") Long id,
    @Param("status") OrderStatus status,
    @Param("version") Long version
);
```

### 7.3 관계가 있는 엔티티

```java
// 자식 엔티티 수정 시 부모의 version도 증가시키려면:
@Entity
public class Order {
    @Version
    private Long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    private List<OrderItem> items;

    public void addItem(OrderItem item) {
        this.items.add(item);
        // 부모 수정으로 인식하려면 필드 수정 필요
        this.updatedAt = LocalDateTime.now();
    }
}
```

---

## 8. 실습 과제

1. Order 엔티티에 @Version 필드 추가
2. 동시 수정 시나리오 테스트 작성
3. OptimisticLockingFailureException 처리
4. Spring Retry로 재시도 구현
5. 분산 락 + 낙관적 락 조합 구현

---

## 참고 자료

- [JPA 공식 문서 - Locking](https://jakarta.ee/specifications/persistence/3.0/jakarta-persistence-spec-3.0.html#a2071)
- [Hibernate - Optimistic Locking](https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#locking-optimistic)
- [Baeldung - Optimistic Locking in JPA](https://www.baeldung.com/jpa-optimistic-locking)

---

## 다음 단계

[05-idempotency.md](./05-idempotency.md) - 멱등성 처리로 이동
