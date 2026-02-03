# 낙관적 락 (Optimistic Lock)

## 이 문서에서 배우는 것

- 낙관적 락과 비관적 락의 차이
- JPA @Version을 활용한 낙관적 락 구현
- 충돌 처리 방법
- 분산 락과의 조합

---

## 📊 현재 프로젝트 구현 상태 (2026-02-03 코드 검토)

```
┌─────────────────────────────────────────────────────────────────┐
│  구현 상태 요약                                                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  [✅ 이미 구현됨]                                               │
│  ├── @Version 필드                                              │
│  │   ├── Inventory.java:33-34                                  │
│  │   ├── Order.java:37-38                                      │
│  │   └── Payment.java (있음)                                   │
│  │                                                              │
│  ├── OptimisticLockTest.java                                   │
│  │   └── service-inventory/src/test/java/.../OptimisticLockTest│
│  │   └── 동시 수정 시 예외 발생 테스트 완성                     │
│  │                                                              │
│  └── SQL 로그 설정                                              │
│      └── application-local.yml (show-sql: true)                │
│                                                                 │
│  [❌ 구현 필요]                                                  │
│  └── GlobalExceptionHandler.java                               │
│      ├── 위치: common/.../exception/                           │
│      ├── ObjectOptimisticLockingFailureException 처리 (409)    │
│      ├── BusinessException 처리 (400)                          │
│      └── 현재 예외 발생 시 Spring 기본 500 에러 반환            │
│                                                                 │
│  [확인 필요]                                                     │
│  ├── Docker Compose 실행 후 테스트 실행                         │
│  └── SQL 로그에서 WHERE version=? 조건 확인                     │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 테스트 실행 방법

```bash
# 1. Docker Compose 실행 (MySQL + Redis 필요)
docker-compose up -d

# 2. 테스트 실행
./gradlew :service-inventory:test --tests "OptimisticLockTest"
```

### GlobalExceptionHandler 생성 가이드

**파일 생성**: `common/src/main/java/com/hanumoka/common/exception/GlobalExceptionHandler.java`

```java
package com.hanumoka.common.exception;

import com.hanumoka.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        log.warn("비즈니스 예외: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(e.getErrorInfo()));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(
            ObjectOptimisticLockingFailureException e) {
        log.warn("낙관적 락 충돌: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail("CONFLICT", "다른 요청이 먼저 처리되었습니다."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("예상치 못한 예외: ", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR.toErrorInfo()));
    }
}
```

**주의**: common 모듈의 `@RestControllerAdvice`가 다른 모듈에서 스캔되려면:
- 각 서비스 Application에 `@ComponentScan(basePackages = {"com.hanumoka.xxx", "com.hanumoka.common.exception"})` 추가
- 또는 각 서비스 모듈에 개별 ExceptionHandler 생성

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

## 8. MyBatis 기반 구현

JPA @Version은 내부 동작이 추상화되어 있습니다. 쿼리 레벨의 이해를 위해 MyBatis로 직접 구현해봅니다.

### 8.1 왜 MyBatis로도 학습하는가?

```
┌─────────────────────────────────────────────────────────────────────┐
│                    JPA vs MyBatis 학습 포인트                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [JPA @Version]                                                      │
│  ├── 장점: 선언적, 간편함                                            │
│  ├── 단점: 내부 동작이 숨겨짐                                        │
│  └── 학습: "어떻게 동작하는지" 이해 어려움                           │
│                                                                      │
│  [MyBatis 직접 구현]                                                 │
│  ├── 장점: SQL 직접 작성, 동작 원리 명확                             │
│  ├── 단점: 보일러플레이트 코드                                       │
│  └── 학습: WHERE version = ? 조건의 의미 체감                        │
│                                                                      │
│  권장: 두 방식 모두 학습하여 원리 이해 + 실무 적용                    │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 8.2 테이블 스키마

```sql
-- V3__create_orders_table.sql
CREATE TABLE orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_number VARCHAR(50) NOT NULL UNIQUE,
    customer_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    total_amount DECIMAL(15, 2) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,  -- 낙관적 락용 버전
    created_at DATETIME NOT NULL,
    updated_at DATETIME,

    INDEX idx_order_number (order_number),
    INDEX idx_customer_id (customer_id)
);
```

### 8.3 도메인 객체

```java
// MyBatis용 도메인 (JPA 어노테이션 없음)
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Order {
    private Long id;
    private String orderNumber;
    private Long customerId;
    private OrderStatus status;
    private BigDecimal totalAmount;
    private Long version;  // 버전 필드
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

    // 버전 증가 (MyBatis에서는 직접 관리)
    public void incrementVersion() {
        this.version = this.version + 1;
    }
}
```

### 8.4 Mapper 인터페이스

```java
@Mapper
public interface OrderMapper {

    // 기본 조회
    Optional<Order> findById(Long id);

    Optional<Order> findByOrderNumber(String orderNumber);

    // 삽입
    void insert(Order order);

    // 낙관적 락 업데이트 (핵심!)
    // 반환값: 업데이트된 행 수 (0이면 충돌)
    int updateWithVersion(Order order);

    // 상태만 업데이트 (버전 체크 포함)
    int updateStatus(
        @Param("id") Long id,
        @Param("status") OrderStatus status,
        @Param("version") Long version
    );

    // 비관적 락 조회 (비교용)
    @Select("SELECT * FROM orders WHERE id = #{id} FOR UPDATE")
    Optional<Order> findByIdForUpdate(Long id);
}
```

### 8.5 Mapper XML

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">

<mapper namespace="com.example.order.mapper.OrderMapper">

    <resultMap id="OrderResultMap" type="com.example.order.domain.Order">
        <id property="id" column="id"/>
        <result property="orderNumber" column="order_number"/>
        <result property="customerId" column="customer_id"/>
        <result property="status" column="status"/>
        <result property="totalAmount" column="total_amount"/>
        <result property="version" column="version"/>
        <result property="createdAt" column="created_at"/>
        <result property="updatedAt" column="updated_at"/>
    </resultMap>

    <!-- 기본 조회 -->
    <select id="findById" resultMap="OrderResultMap">
        SELECT id, order_number, customer_id, status, total_amount,
               version, created_at, updated_at
        FROM orders
        WHERE id = #{id}
    </select>

    <select id="findByOrderNumber" resultMap="OrderResultMap">
        SELECT id, order_number, customer_id, status, total_amount,
               version, created_at, updated_at
        FROM orders
        WHERE order_number = #{orderNumber}
    </select>

    <!-- 삽입 -->
    <insert id="insert" useGeneratedKeys="true" keyProperty="id">
        INSERT INTO orders (
            order_number, customer_id, status, total_amount,
            version, created_at, updated_at
        ) VALUES (
            #{orderNumber}, #{customerId}, #{status}, #{totalAmount},
            0, #{createdAt}, #{updatedAt}
        )
    </insert>

    <!--
        낙관적 락 업데이트 (핵심!)

        WHERE version = #{version} 조건이 핵심입니다:
        - 다른 트랜잭션이 먼저 수정했다면 version이 달라져서 0 rows affected
        - 0 rows면 OptimisticLockException을 던져야 함
    -->
    <update id="updateWithVersion">
        UPDATE orders
        SET status = #{status},
            total_amount = #{totalAmount},
            version = version + 1,        <!-- 버전 증가 -->
            updated_at = #{updatedAt}
        WHERE id = #{id}
          AND version = #{version}        <!-- 버전 체크! -->
    </update>

    <!-- 상태만 업데이트 (간단 버전) -->
    <update id="updateStatus">
        UPDATE orders
        SET status = #{status},
            version = version + 1,
            updated_at = NOW()
        WHERE id = #{id}
          AND version = #{version}
    </update>

</mapper>
```

### 8.6 서비스 구현

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderMapper orderMapper;

    private static final int MAX_RETRIES = 3;

    /**
     * 주문 확정 (낙관적 락 + 재시도)
     */
    @Transactional
    public void confirmOrder(Long orderId) {
        int attempt = 0;

        while (attempt < MAX_RETRIES) {
            try {
                doConfirmOrder(orderId);
                return;  // 성공!

            } catch (OptimisticLockException e) {
                attempt++;
                log.warn("낙관적 락 충돌, 재시도 {}/{}: orderId={}",
                    attempt, MAX_RETRIES, orderId);

                if (attempt >= MAX_RETRIES) {
                    throw new ConcurrentModificationException(
                        "동시 수정으로 인해 처리 실패 (재시도 " + MAX_RETRIES + "회 초과)"
                    );
                }

                // 잠시 대기 후 재시도 (지수 백오프)
                sleep(100L * attempt);
            }
        }
    }

    private void doConfirmOrder(Long orderId) {
        // 1. 주문 조회
        Order order = orderMapper.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));

        log.info("주문 조회: id={}, version={}", order.getId(), order.getVersion());

        // 2. 상태 변경
        order.confirm();

        // 3. 낙관적 락 업데이트
        int updatedRows = orderMapper.updateWithVersion(order);

        // 4. 충돌 감지 (핵심!)
        if (updatedRows == 0) {
            // version이 맞지 않아 업데이트 실패 = 다른 트랜잭션이 먼저 수정함
            throw new OptimisticLockException(
                "주문이 다른 사용자에 의해 수정되었습니다: orderId=" + orderId
            );
        }

        log.info("주문 확정 완료: id={}, newVersion={}",
            order.getId(), order.getVersion() + 1);
    }

    /**
     * 간단 버전: 상태만 업데이트
     */
    @Transactional
    public void updateOrderStatus(Long orderId, OrderStatus newStatus, Long expectedVersion) {
        int updatedRows = orderMapper.updateStatus(orderId, newStatus, expectedVersion);

        if (updatedRows == 0) {
            // 업데이트 실패 - 버전 불일치 또는 존재하지 않음
            Order current = orderMapper.findById(orderId).orElse(null);

            if (current == null) {
                throw new OrderNotFoundException(orderId);
            }

            throw new OptimisticLockException(
                String.format("버전 충돌: expected=%d, actual=%d",
                    expectedVersion, current.getVersion())
            );
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### 8.7 커스텀 예외

```java
/**
 * 낙관적 락 충돌 예외
 */
public class OptimisticLockException extends RuntimeException {
    public OptimisticLockException(String message) {
        super(message);
    }
}

/**
 * 동시 수정 예외 (재시도 초과)
 */
public class ConcurrentModificationException extends RuntimeException {
    public ConcurrentModificationException(String message) {
        super(message);
    }
}
```

### 8.8 테스트

```java
@SpringBootTest
class OrderServiceOptimisticLockTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderMapper orderMapper;

    @Test
    @DisplayName("동시 수정 시 낙관적 락이 충돌을 감지한다")
    void optimisticLock_detectsConflict() throws Exception {
        // given: 주문 생성
        Order order = Order.builder()
            .orderNumber("ORD-001")
            .customerId(1L)
            .status(OrderStatus.PENDING)
            .totalAmount(new BigDecimal("10000"))
            .createdAt(LocalDateTime.now())
            .build();
        orderMapper.insert(order);
        Long orderId = order.getId();

        // when: 동시에 2개의 스레드가 같은 주문을 수정
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    orderService.confirmOrder(orderId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // then: 하나는 성공, 하나는 실패 (또는 재시도 후 성공)
        Order result = orderMapper.findById(orderId).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(result.getVersion()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("버전이 맞지 않으면 업데이트가 실패한다")
    void updateWithWrongVersion_fails() {
        // given
        Order order = Order.builder()
            .orderNumber("ORD-002")
            .customerId(1L)
            .status(OrderStatus.PENDING)
            .totalAmount(new BigDecimal("10000"))
            .createdAt(LocalDateTime.now())
            .build();
        orderMapper.insert(order);

        // when: 잘못된 버전으로 업데이트 시도
        Long wrongVersion = 999L;

        // then
        assertThatThrownBy(() ->
            orderService.updateOrderStatus(order.getId(), OrderStatus.CONFIRMED, wrongVersion)
        ).isInstanceOf(OptimisticLockException.class);
    }
}
```

### 8.9 JPA vs MyBatis 비교

```
┌─────────────────────────────────────────────────────────────────────┐
│                    동일한 동작, 다른 구현                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [JPA 방식]                                                          │
│                                                                      │
│  @Entity                                                             │
│  public class Order {                                                │
│      @Version                                                        │
│      private Long version;   // 선언만 하면 자동 처리                 │
│  }                                                                   │
│                                                                      │
│  orderRepository.save(order);  // 내부적으로 WHERE version 체크       │
│                                                                      │
│  → OptimisticLockingFailureException 자동 발생                       │
│                                                                      │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [MyBatis 방식]                                                      │
│                                                                      │
│  <update id="updateWithVersion">                                     │
│      UPDATE orders                                                   │
│      SET status = #{status}, version = version + 1                   │
│      WHERE id = #{id} AND version = #{version}  // 직접 작성!        │
│  </update>                                                           │
│                                                                      │
│  int rows = orderMapper.updateWithVersion(order);                    │
│  if (rows == 0) {                                                    │
│      throw new OptimisticLockException(...);  // 직접 처리!          │
│  }                                                                   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 9. 실습 과제

### JPA 실습
1. Order 엔티티에 @Version 필드 추가
2. 동시 수정 시나리오 테스트 작성
3. OptimisticLockingFailureException 처리
4. Spring Retry로 재시도 구현
5. 분산 락 + 낙관적 락 조합 구현

### MyBatis 실습
6. version 컬럼이 있는 orders 테이블 생성 (Flyway)
7. OrderMapper XML에 updateWithVersion 쿼리 작성
8. 업데이트 결과가 0일 때 OptimisticLockException 처리
9. 수동 재시도 로직 구현 (지수 백오프)
10. 동시 수정 테스트로 충돌 감지 확인

---

## 10. Temporal 미리보기: 낙관적 락이 덜 필요해진다

> **Phase 3 예고**: 직접 구현한 낙관적 락이 Temporal에서 왜 덜 필요한지 살펴봅니다.

### 10.1 현재 구현에서 낙관적 락이 필요한 이유

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                  REST 환경에서 낙관적 락이 필요한 상황                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   문제 상황: 동시에 여러 요청이 같은 주문을 수정                             │
│                                                                             │
│   [요청 A]                              [요청 B]                            │
│   주문 조회 → Order(status=PENDING)     주문 조회 → Order(status=PENDING)   │
│        ↓                                      ↓                             │
│   status = CONFIRMED                    status = CANCELLED                  │
│        ↓                                      ↓                             │
│   저장 시도 ─────────────────────────→ 저장 시도                            │
│                                                                             │
│   ❌ 둘 다 성공하면 데이터 불일치!                                           │
│                                                                             │
│   해결: @Version으로 충돌 감지                                               │
│   → 나중 요청이 OptimisticLockException 발생                                │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 10.2 Temporal에서는 왜 덜 필요한가?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                   Temporal Workflow의 단일 실행 보장                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   핵심 원리: 하나의 Workflow ID = 하나의 실행                               │
│                                                                             │
│   [요청 A]                              [요청 B]                            │
│   WorkflowId: order-123                 WorkflowId: order-123               │
│        ↓                                      ↓                             │
│   Workflow 시작 ──────────────────────→ 이미 실행 중!                       │
│        ↓                                      ↓                             │
│   정상 처리                              WorkflowExecutionAlreadyStarted    │
│                                          → 기존 Workflow에 Signal 전송      │
│                                          → 또는 결과 대기                   │
│                                                                             │
│   ✅ 동시 수정 자체가 발생하지 않음!                                        │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 10.3 Temporal Workflow의 상태 관리

```java
/**
 * Temporal Workflow - 상태 변경이 직렬화됨
 */
@WorkflowInterface
public interface OrderWorkflow {
    @WorkflowMethod
    OrderResult processOrder(OrderRequest request);

    @SignalMethod
    void cancelOrder(String reason);

    @QueryMethod
    OrderStatus getStatus();
}

@WorkflowImpl
public class OrderWorkflowImpl implements OrderWorkflow {

    // Workflow 내부 상태 - Temporal이 관리
    private OrderStatus status = OrderStatus.PENDING;
    private String cancellationReason;

    @Override
    public OrderResult processOrder(OrderRequest request) {
        // 상태 전이 - 순차적으로 실행됨 (동시 실행 없음!)
        status = OrderStatus.PROCESSING;

        // Activity 호출
        inventoryActivity.reserve(request);
        status = OrderStatus.INVENTORY_RESERVED;

        paymentActivity.charge(request);
        status = OrderStatus.PAYMENT_COMPLETED;

        return OrderResult.success();
    }

    @Override
    public void cancelOrder(String reason) {
        // Signal로 들어온 요청도 순차 처리!
        // 현재 상태에 따라 적절한 보상 실행
        if (status == OrderStatus.PAYMENT_COMPLETED) {
            paymentActivity.refund();
        }
        if (status.ordinal() >= OrderStatus.INVENTORY_RESERVED.ordinal()) {
            inventoryActivity.release();
        }
        this.cancellationReason = reason;
        status = OrderStatus.CANCELLED;
    }
}
```

### 10.4 여전히 낙관적 락이 필요한 경우

```
┌─────────────────────────────────────────────────────────────────────────────┐
│              Temporal 사용 시에도 낙관적 락이 필요한 경우                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   Case 1: Activity 내부에서 DB 직접 수정                                    │
│   ─────────────────────────────────────                                     │
│   @ActivityImpl                                                             │
│   public class InventoryActivityImpl {                                      │
│       public void reserve(ReserveRequest req) {                             │
│           // 여기서 DB 직접 접근 → 낙관적 락 여전히 필요!                    │
│           Inventory inv = repo.findById(req.getProductId());                │
│           inv.decreaseQuantity(req.getQuantity());                          │
│           repo.save(inv);  // @Version 필요                                 │
│       }                                                                     │
│   }                                                                         │
│                                                                             │
│   이유: 여러 Workflow가 동시에 같은 상품 재고 수정 가능                      │
│                                                                             │
│   ─────────────────────────────────────────────────────────────────────     │
│                                                                             │
│   Case 2: 외부 시스템과 연동                                                │
│   ────────────────────────                                                  │
│   Activity가 외부 API 호출 시 멱등성/동시성은 외부 시스템 책임               │
│                                                                             │
│   ─────────────────────────────────────────────────────────────────────     │
│                                                                             │
│   Case 3: 한 Workflow가 여러 Entity 수정                                    │
│   ────────────────────────────────────                                      │
│   하나의 Activity에서 여러 테이블 수정 시                                   │
│   각 테이블에 대한 동시성 제어 필요                                         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 10.5 직접 비교: 무엇이 달라지는가?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Phase 2-A → Phase 3 비교                           │
├────────────────────────────────┬────────────────────────────────────────────┤
│      Phase 2-A (직접 구현)     │           Phase 3 (Temporal)               │
├────────────────────────────────┼────────────────────────────────────────────┤
│                                │                                            │
│  주문 상태 관리:               │  주문 상태 관리:                           │
│  ❌ @Version 필수              │  ✅ Workflow 내부 상태로 관리              │
│  ❌ 충돌 시 재시도 로직 필요   │  ✅ Signal로 순차 처리                     │
│                                │                                            │
│  재고 관리:                    │  재고 관리:                                │
│  ❌ @Version 또는 분산락 필요  │  ⚠️ Activity에서 여전히 필요               │
│                                │     (여러 Workflow가 동시 접근)            │
│                                │                                            │
│  Saga 상태 관리:               │  Saga 상태 관리:                           │
│  ❌ @Version으로 동시 수정 방지│  ✅ Workflow가 상태 - 락 불필요            │
│                                │                                            │
├────────────────────────────────┼────────────────────────────────────────────┤
│  낙관적 락 사용처:             │  낙관적 락 사용처:                         │
│  • Order 엔티티               │  • Inventory 등 공유 리소스만              │
│  • SagaState 엔티티           │  • (Workflow 상태는 락 불필요)              │
│  • Inventory 엔티티           │                                            │
└────────────────────────────────┴────────────────────────────────────────────┘
```

### 10.6 Temporal의 동시성 제어 메커니즘

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Temporal 내장 동시성 제어                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   1. Workflow ID Uniqueness                                                 │
│   ─────────────────────────                                                 │
│   • 같은 ID로 동시 시작 불가                                                │
│   • WorkflowExecutionAlreadyStarted 예외                                    │
│                                                                             │
│   2. Event Sourcing                                                         │
│   ─────────────────                                                         │
│   • 모든 상태 변경이 이벤트로 기록                                          │
│   • 이벤트 순서가 보장됨                                                    │
│   • 재생 시에도 동일한 결과                                                 │
│                                                                             │
│   3. Signal 순차 처리                                                       │
│   ──────────────────                                                        │
│   • Signal이 동시에 들어와도 순차 처리                                      │
│   • Workflow 코드 내에서 동시성 걱정 불필요                                 │
│                                                                             │
│   4. Activity Task Queue                                                    │
│   ─────────────────────                                                     │
│   • maxConcurrentActivityExecutionSize로 동시 실행 수 제한                  │
│   • Worker 레벨에서 처리량 제어                                             │
│                                                                             │
│   ═══════════════════════════════════════════════════════════════════════   │
│                                                                             │
│   결론: Workflow 상태에는 락 불필요, DB 직접 접근에는 여전히 필요           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 10.7 Phase 3에서 확인할 것들

- [ ] Workflow 내부 상태 변경과 동시성
- [ ] Signal 처리 순서 보장 확인
- [ ] Activity에서 DB 접근 시 락 전략
- [ ] maxConcurrentActivityExecutionSize 설정
- [ ] Phase 2-A 낙관적 락 코드와 비교

---

## 참고 자료

- [JPA 공식 문서 - Locking](https://jakarta.ee/specifications/persistence/3.0/jakarta-persistence-spec-3.0.html#a2071)
- [Hibernate - Optimistic Locking](https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#locking-optimistic)
- [Baeldung - Optimistic Locking in JPA](https://www.baeldung.com/jpa-optimistic-locking)
- [Temporal - Workflow Execution](https://docs.temporal.io/workflows#workflow-execution)

---

## 다음 단계

[06-bean-validation.md](./06-bean-validation.md) - 입력 검증으로 이동
