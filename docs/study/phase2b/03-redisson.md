# Redisson - 분산 락과 Spring 연동

## 이 문서에서 배우는 것

- Redisson의 개념과 특징
- 분산 락(Distributed Lock)의 필요성과 구현
- 다양한 Redisson 락 유형 (Lock, FairLock, ReadWriteLock, etc.)
- Spring Boot와 Redisson 통합
- 실전 분산 락 활용 패턴

---

## 1. 분산 락이란?

### 문제 상황

```
┌─────────────────────────────────────────────────────────────────────┐
│                    분산 락이 없는 경우 문제                           │
│                                                                      │
│   재고: 100개                                                        │
│                                                                      │
│   ┌────────────┐                        ┌────────────┐              │
│   │  Server A  │                        │  Server B  │              │
│   │            │                        │            │              │
│   │ 1. 재고 조회│   동시에!              │ 1. 재고 조회│              │
│   │   (100개)  │                        │   (100개)  │              │
│   │            │                        │            │              │
│   │ 2. 50개    │                        │ 2. 50개    │              │
│   │   주문 처리│                        │   주문 처리│              │
│   │            │                        │            │              │
│   │ 3. 재고    │                        │ 3. 재고    │              │
│   │   = 100-50 │                        │   = 100-50 │              │
│   │   = 50개   │                        │   = 50개   │              │
│   └────────────┘                        └────────────┘              │
│                                                                      │
│   결과: 재고 50개 (실제로는 0개여야 함!)                             │
│   → 100개 판매했는데 재고가 50개 남음 (데이터 불일치)                 │
└─────────────────────────────────────────────────────────────────────┘
```

### 분산 락 적용 후

```
┌─────────────────────────────────────────────────────────────────────┐
│                    분산 락이 있는 경우                                │
│                                                                      │
│   ┌────────────────────────────────────────────────────────┐        │
│   │                   Redis (Redisson)                      │        │
│   │                                                         │        │
│   │   Lock Key: "lock:product:001"                          │        │
│   │   Lock Value: "server-a-uuid"                          │        │
│   │   TTL: 30 seconds                                       │        │
│   └────────────────────────────────────────────────────────┘        │
│                    ▲                    ▲                           │
│                    │                    │                           │
│   ┌────────────────┴───┐    ┌──────────┴────────────┐              │
│   │      Server A      │    │       Server B        │              │
│   │                    │    │                       │              │
│   │ 1. 락 획득 요청    │    │ 1. 락 획득 요청       │              │
│   │    → 성공!         │    │    → 대기 (락 점유중) │              │
│   │                    │    │                       │              │
│   │ 2. 재고 100 조회   │    │       ...대기...      │              │
│   │                    │    │                       │              │
│   │ 3. 50개 주문 처리  │    │       ...대기...      │              │
│   │    재고 = 50       │    │                       │              │
│   │                    │    │                       │              │
│   │ 4. 락 해제         │    │ 2. 락 획득 → 성공!    │              │
│   │                    │    │                       │              │
│   │                    │    │ 3. 재고 50 조회       │              │
│   │                    │    │    50개 주문 처리     │              │
│   │                    │    │    재고 = 0          │              │
│   │                    │    │                       │              │
│   │                    │    │ 4. 락 해제            │              │
│   └────────────────────┘    └───────────────────────┘              │
│                                                                      │
│   결과: 재고 0개 (정확!)                                             │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 2. Redisson이란?

### 정의

**Redisson**은 Redis 기반의 Java 분산 데이터 구조 라이브러리입니다.

### Redisson vs Lettuce vs Jedis

| 특징 | Redisson | Lettuce | Jedis |
|------|----------|---------|-------|
| **분산 락** | O (내장) | X (직접 구현) | X (직접 구현) |
| **분산 객체** | O | X | X |
| **비동기** | O | O | 제한적 |
| **스레드 안전** | O | O | X |
| **Spring 통합** | O | O | O |
| **학습 곡선** | 중간 | 낮음 | 낮음 |

### Redisson 주요 기능

```
┌─────────────────────────────────────────────────────────────────────┐
│                       Redisson 기능                                  │
│                                                                      │
│   ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐    │
│   │  분산 락        │  │  분산 컬렉션    │  │  분산 서비스    │    │
│   │                 │  │                 │  │                 │    │
│   │  • Lock        │  │  • Map          │  │  • RemoteService│    │
│   │  • FairLock    │  │  • Set          │  │  • ExecutorSvc  │    │
│   │  • ReadWrite   │  │  • List         │  │  • SchedulerSvc │    │
│   │  • Semaphore   │  │  • Queue        │  │                 │    │
│   │  • CountDown   │  │  • Deque        │  │                 │    │
│   └─────────────────┘  └─────────────────┘  └─────────────────┘    │
│                                                                      │
│   ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐    │
│   │  분산 객체      │  │  Pub/Sub        │  │  캐시           │    │
│   │                 │  │                 │  │                 │    │
│   │  • AtomicLong  │  │  • Topic        │  │  • Cache        │    │
│   │  • BitSet      │  │  • PatternTopic │  │  • LocalCache   │    │
│   │  • BloomFilter │  │                 │  │  • NearCache    │    │
│   └─────────────────┘  └─────────────────┘  └─────────────────┘    │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 3. Spring Boot 연동

### 의존성 설정

```groovy
// build.gradle
dependencies {
    implementation 'org.redisson:redisson-spring-boot-starter:4.0.0'  // Spring Boot 4 호환
}
```

### 설정 파일

```yaml
# application.yml
spring:
  redis:
    host: localhost
    port: 6379

# 또는 Redisson 전용 설정
spring:
  redis:
    redisson:
      config: |
        singleServerConfig:
          address: "redis://localhost:6379"
          connectionMinimumIdleSize: 5
          connectionPoolSize: 10
          connectTimeout: 10000
          timeout: 3000
          retryAttempts: 3
          retryInterval: 1500
```

### Java Config

```java
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://localhost:6379")
                .setConnectionMinimumIdleSize(5)
                .setConnectionPoolSize(10)
                .setConnectTimeout(10000)
                .setTimeout(3000)
                .setRetryAttempts(3)
                .setRetryInterval(1500);

        return Redisson.create(config);
    }

    // Cluster 모드
    @Bean
    @Profile("cluster")
    public RedissonClient redissonClusterClient() {
        Config config = new Config();
        config.useClusterServers()
                .addNodeAddress(
                    "redis://node1:6379",
                    "redis://node2:6379",
                    "redis://node3:6379"
                )
                .setScanInterval(2000);

        return Redisson.create(config);
    }
}
```

---

## 4. 분산 락 구현

### 기본 Lock 사용

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class StockService {

    private final RedissonClient redissonClient;
    private final StockRepository stockRepository;

    public void decreaseStock(Long productId, int quantity) {
        String lockKey = "lock:stock:" + productId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 락 획득 시도 (최대 10초 대기, 락 유지 시간 5초)
            boolean acquired = lock.tryLock(10, 5, TimeUnit.SECONDS);

            if (!acquired) {
                throw new LockAcquisitionException("Failed to acquire lock for product: " + productId);
            }

            log.info("Lock acquired for product: {}", productId);

            // 비즈니스 로직
            Stock stock = stockRepository.findByProductId(productId)
                    .orElseThrow(() -> new StockNotFoundException(productId));

            if (stock.getQuantity() < quantity) {
                throw new InsufficientStockException(productId, stock.getQuantity(), quantity);
            }

            stock.decrease(quantity);
            stockRepository.save(stock);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException("Lock acquisition interrupted", e);
        } finally {
            // 락 해제 (현재 스레드가 보유한 경우에만)
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("Lock released for product: {}", productId);
            }
        }
    }
}
```

### AOP 기반 분산 락

```java
// 어노테이션 정의
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {
    String key();                    // 락 키 (SpEL 지원)
    long waitTime() default 5;       // 락 획득 대기 시간 (초)
    long leaseTime() default 3;      // 락 유지 시간 (초)
    TimeUnit timeUnit() default TimeUnit.SECONDS;
}
```

```java
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class DistributedLockAspect {

    private final RedissonClient redissonClient;
    private final ExpressionParser parser = new SpelExpressionParser();

    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        String lockKey = resolveLockKey(joinPoint, distributedLock.key());
        RLock lock = redissonClient.getLock(lockKey);

        log.debug("Attempting to acquire lock: {}", lockKey);

        try {
            boolean acquired = lock.tryLock(
                    distributedLock.waitTime(),
                    distributedLock.leaseTime(),
                    distributedLock.timeUnit()
            );

            if (!acquired) {
                throw new LockAcquisitionException("Failed to acquire lock: " + lockKey);
            }

            log.debug("Lock acquired: {}", lockKey);
            return joinPoint.proceed();

        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("Lock released: {}", lockKey);
            }
        }
    }

    private String resolveLockKey(ProceedingJoinPoint joinPoint, String keyExpression) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();
        String[] paramNames = signature.getParameterNames();

        StandardEvaluationContext context = new StandardEvaluationContext();
        for (int i = 0; i < paramNames.length; i++) {
            context.setVariable(paramNames[i], args[i]);
        }

        return "lock:" + parser.parseExpression(keyExpression).getValue(context, String.class);
    }
}
```

```java
// 사용 예시
@Service
@RequiredArgsConstructor
public class OrderService {

    private final StockRepository stockRepository;
    private final OrderRepository orderRepository;

    @DistributedLock(key = "'stock:' + #productId", waitTime = 10, leaseTime = 5)
    @Transactional
    public Order createOrder(Long productId, int quantity, Long customerId) {
        // 분산 락이 자동으로 적용됨
        Stock stock = stockRepository.findByProductId(productId)
                .orElseThrow();

        stock.decrease(quantity);

        Order order = Order.builder()
                .productId(productId)
                .quantity(quantity)
                .customerId(customerId)
                .build();

        return orderRepository.save(order);
    }
}
```

---

## 5. 다양한 락 유형

### Fair Lock (공정 락)

먼저 요청한 순서대로 락을 획득합니다.

```java
@Service
@RequiredArgsConstructor
public class FairLockService {

    private final RedissonClient redissonClient;

    public void processWithFairLock(String resourceId) {
        RLock fairLock = redissonClient.getFairLock("fairLock:" + resourceId);

        try {
            // 요청 순서대로 락 획득
            fairLock.lock(10, TimeUnit.SECONDS);

            // 비즈니스 로직
            doProcess(resourceId);

        } finally {
            fairLock.unlock();
        }
    }
}
```

### ReadWrite Lock (읽기/쓰기 락)

읽기는 동시에, 쓰기는 배타적으로 처리합니다.

```java
@Service
@RequiredArgsConstructor
public class CacheService {

    private final RedissonClient redissonClient;
    private final Map<String, Object> localCache = new ConcurrentHashMap<>();

    public Object read(String key) {
        RReadWriteLock rwLock = redissonClient.getReadWriteLock("rwLock:" + key);
        RLock readLock = rwLock.readLock();

        try {
            readLock.lock(5, TimeUnit.SECONDS);
            // 여러 스레드가 동시에 읽기 가능
            return localCache.get(key);
        } finally {
            readLock.unlock();
        }
    }

    public void write(String key, Object value) {
        RReadWriteLock rwLock = redissonClient.getReadWriteLock("rwLock:" + key);
        RLock writeLock = rwLock.writeLock();

        try {
            writeLock.lock(10, TimeUnit.SECONDS);
            // 쓰기 시 다른 읽기/쓰기 모두 대기
            localCache.put(key, value);
        } finally {
            writeLock.unlock();
        }
    }
}
```

### Semaphore (세마포어)

동시 접근 수를 제한합니다.

```java
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final RedissonClient redissonClient;
    private static final int MAX_CONCURRENT_REQUESTS = 10;

    @PostConstruct
    public void init() {
        RSemaphore semaphore = redissonClient.getSemaphore("api:rate-limit");
        semaphore.trySetPermits(MAX_CONCURRENT_REQUESTS);
    }

    public void executeWithRateLimit(Runnable task) {
        RSemaphore semaphore = redissonClient.getSemaphore("api:rate-limit");

        try {
            // permit 획득 (최대 10개 동시 실행)
            if (semaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                try {
                    task.run();
                } finally {
                    semaphore.release();
                }
            } else {
                throw new RateLimitExceededException("Too many requests");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### CountDownLatch (카운트다운 래치)

여러 작업이 완료될 때까지 대기합니다.

```java
@Service
@RequiredArgsConstructor
public class BatchJobService {

    private final RedissonClient redissonClient;

    public void runParallelJobs(List<Runnable> jobs) throws InterruptedException {
        String latchKey = "batch:latch:" + UUID.randomUUID();
        RCountDownLatch latch = redissonClient.getCountDownLatch(latchKey);
        latch.trySetCount(jobs.size());

        // 각 작업 실행
        for (Runnable job : jobs) {
            CompletableFuture.runAsync(() -> {
                try {
                    job.run();
                } finally {
                    latch.countDown();
                }
            });
        }

        // 모든 작업 완료 대기
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        if (!completed) {
            throw new TimeoutException("Batch jobs not completed in time");
        }
    }
}
```

---

## 6. 락 유형 비교

```
┌─────────────────────────────────────────────────────────────────────┐
│                       락 유형별 특징 비교                             │
│                                                                      │
│   ┌──────────────┬─────────────────┬─────────────────────────────┐  │
│   │     타입     │      특징        │           사용 사례          │  │
│   ├──────────────┼─────────────────┼─────────────────────────────┤  │
│   │   Lock       │ 기본 배타 락     │ 일반적인 동시성 제어         │  │
│   ├──────────────┼─────────────────┼─────────────────────────────┤  │
│   │  FairLock    │ FIFO 순서 보장   │ 공정성이 중요한 경우         │  │
│   ├──────────────┼─────────────────┼─────────────────────────────┤  │
│   │ ReadWrite    │ 읽기 동시/쓰기   │ 읽기가 많은 캐시             │  │
│   │   Lock       │ 배타적          │                              │  │
│   ├──────────────┼─────────────────┼─────────────────────────────┤  │
│   │  Semaphore   │ 동시 접근 수     │ API Rate Limiting            │  │
│   │              │ 제한            │ 커넥션 풀 관리               │  │
│   ├──────────────┼─────────────────┼─────────────────────────────┤  │
│   │ CountDown    │ N개 완료 후      │ 병렬 작업 동기화             │  │
│   │   Latch      │ 진행            │ 배치 작업 완료 대기          │  │
│   └──────────────┴─────────────────┴─────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 7. 분산 락 Best Practices

### 락 타임아웃 설정

```java
@Component
public class LockTimeoutConfig {

    // 락 획득 대기 시간: 비즈니스 요구사항에 따라 설정
    // 너무 길면 사용자 대기 시간 증가
    // 너무 짧으면 락 획득 실패 증가
    public static final long LOCK_WAIT_TIME = 5;

    // 락 유지 시간: 비즈니스 로직 최대 실행 시간보다 길게
    // 너무 짧으면 처리 중 락 해제 위험
    // 너무 길면 장애 시 복구 지연
    public static final long LOCK_LEASE_TIME = 10;
}
```

### 락 키 설계

```java
public class LockKeyGenerator {

    // 좋은 락 키 예시
    public static String forStock(Long productId) {
        return "lock:stock:" + productId;  // 상품별 락
    }

    public static String forOrder(Long customerId) {
        return "lock:order:" + customerId;  // 고객별 주문 락
    }

    public static String forPayment(String transactionId) {
        return "lock:payment:" + transactionId;  // 결제별 락
    }

    // 나쁜 락 키 예시 (너무 넓은 범위)
    // "lock:order" - 모든 주문이 직렬화됨
    // "lock:stock" - 모든 재고 작업이 직렬화됨
}
```

### 예외 처리

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class SafeLockService {

    private final RedissonClient redissonClient;

    public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime,
                                  Callable<T> task) {
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean acquired = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);

            if (!acquired) {
                log.warn("Failed to acquire lock: {}", lockKey);
                throw new LockAcquisitionException(lockKey);
            }

            log.debug("Lock acquired: {}", lockKey);
            return task.call();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Lock acquisition interrupted: {}", lockKey);
            throw new LockAcquisitionException("Interrupted while acquiring lock", e);

        } catch (Exception e) {
            log.error("Error during locked execution: {}", lockKey, e);
            throw new RuntimeException(e);

        } finally {
            releaseLockSafely(lock, lockKey);
        }
    }

    private void releaseLockSafely(RLock lock, String lockKey) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("Lock released: {}", lockKey);
            }
        } catch (IllegalMonitorStateException e) {
            log.warn("Lock already released (possibly expired): {}", lockKey);
        }
    }
}
```

### 데드락 방지

```java
@Service
public class DeadlockPreventionService {

    private final RedissonClient redissonClient;

    // 여러 락을 순서대로 획득 (항상 같은 순서로)
    public void transferStock(Long fromProductId, Long toProductId, int quantity) {
        // 항상 작은 ID부터 락 획득 → 데드락 방지
        Long firstId = Math.min(fromProductId, toProductId);
        Long secondId = Math.max(fromProductId, toProductId);

        RLock firstLock = redissonClient.getLock("lock:stock:" + firstId);
        RLock secondLock = redissonClient.getLock("lock:stock:" + secondId);

        try {
            firstLock.lock(10, TimeUnit.SECONDS);
            try {
                secondLock.lock(10, TimeUnit.SECONDS);

                // 비즈니스 로직
                doTransfer(fromProductId, toProductId, quantity);

            } finally {
                secondLock.unlock();
            }
        } finally {
            firstLock.unlock();
        }
    }

    // MultiLock 사용
    public void transferStockWithMultiLock(Long fromProductId, Long toProductId, int quantity) {
        RLock lock1 = redissonClient.getLock("lock:stock:" + fromProductId);
        RLock lock2 = redissonClient.getLock("lock:stock:" + toProductId);

        RLock multiLock = redissonClient.getMultiLock(lock1, lock2);

        try {
            multiLock.lock(10, TimeUnit.SECONDS);
            doTransfer(fromProductId, toProductId, quantity);
        } finally {
            multiLock.unlock();
        }
    }
}
```

---

## 8. Phantom Key와 락 타임아웃 이슈

### 8.1 Phantom Key란?

**Phantom Key**는 존재한다고 "착각"하지만 실제로는 없는(또는 사라진) 키를 의미합니다.

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Phantom Key 발생 시나리오                          │
│                                                                       │
│   [시나리오 1: 분산 락 TTL 만료]                                       │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                                                              │   │
│   │  T0: Server A가 락 획득                                       │   │
│   │      SET lock:order:123 "server-a-uuid" EX 5                 │   │
│   │      → 락 유지 시간: 5초                                      │   │
│   │                                                              │   │
│   │  T1~T4: Server A가 오래 걸리는 작업 처리 중...                │   │
│   │         (GC pause, 네트워크 지연, 복잡한 연산 등)             │   │
│   │                                                              │   │
│   │  T5: TTL 만료! 락 키 자동 삭제 ← Phantom 발생 시점           │   │
│   │      Server A는 락을 가지고 있다고 "착각"                     │   │
│   │                                                              │   │
│   │  T6: Server B가 락 획득 성공!                                 │   │
│   │      SET lock:order:123 "server-b-uuid" EX 5                 │   │
│   │                                                              │   │
│   │  T7: Server A 작업 완료, Server B도 작업 중                   │   │
│   │      → 두 서버가 동시에 같은 리소스 접근! 💥                  │   │
│   │                                                              │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                       │
│   [시나리오 2: Check-then-Act Race Condition]                         │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                                                              │   │
│   │  Server A                      Redis                Server B │   │
│   │     │                            │                      │    │   │
│   │     │ ─── EXISTS key ──────────▶│                      │    │   │
│   │     │◀─── true ─────────────────│                      │    │   │
│   │     │                            │◀── DEL key ─────────│    │   │
│   │     │                            │─── OK ─────────────▶│    │   │
│   │     │ ─── GET key ─────────────▶│                      │    │   │
│   │     │◀─── null ─────────────────│  ← Phantom!          │    │   │
│   │     │                            │                      │    │   │
│   │  "키가 있다고 확인했는데 없음!"                               │   │
│   │                                                              │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                       │
│   [시나리오 3: 락 해제 시 다른 서버의 락 삭제]                         │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                                                              │   │
│   │  T0: Server A 락 획득 (value: "uuid-a")                      │   │
│   │  T5: TTL 만료, 락 삭제                                        │   │
│   │  T6: Server B 락 획득 (value: "uuid-b")                      │   │
│   │  T7: Server A 작업 완료, unlock 호출                          │   │
│   │      DEL lock:order:123                                      │   │
│   │      → Server B의 락이 삭제됨! 💥                            │   │
│   │                                                              │   │
│   └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### 8.2 락 타임아웃 문제 상세

```
┌─────────────────────────────────────────────────────────────────────┐
│                    락 타임아웃 딜레마                                  │
│                                                                       │
│   TTL을 너무 짧게 설정하면:                                           │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │  • 처리 중 락 만료 → 동시 접근 발생                           │   │
│   │  • Phantom Key 위험 증가                                     │   │
│   │  • 데이터 정합성 깨짐                                         │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                       │
│   TTL을 너무 길게 설정하면:                                           │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │  • 서버 장애 시 락이 오래 유지됨                              │   │
│   │  • 다른 서버들이 오래 대기해야 함                              │   │
│   │  • 시스템 처리량 저하                                         │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                       │
│   적정 TTL = 예상 처리 시간 + 여유 시간 (GC, 네트워크 지연 고려)      │
│                                                                       │
└─────────────────────────────────────────────────────────────────────┘
```

### 8.3 대응 전략 1: Redisson Watch Dog (락 자동 연장)

Redisson은 **Watch Dog** 메커니즘으로 락 자동 연장을 지원합니다.

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class WatchDogLockService {

    private final RedissonClient redissonClient;

    /**
     * Watch Dog 활성화된 락 사용
     *
     * leaseTime을 지정하지 않으면 Watch Dog이 자동 활성화됨
     * - 기본 lockWatchdogTimeout: 30초
     * - 10초마다 락 연장 (lockWatchdogTimeout / 3)
     * - 스레드가 살아있는 한 계속 연장
     */
    public void executeWithWatchDog(String resourceId, Runnable task) {
        String lockKey = "lock:resource:" + resourceId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // leaseTime 미지정 → Watch Dog 활성화
            lock.lock();  // 또는 lock.tryLock(waitTime, TimeUnit.SECONDS)

            log.info("Lock acquired with Watch Dog: {}", lockKey);
            task.run();

        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("Lock released: {}", lockKey);
            }
        }
    }

    /**
     * Watch Dog 비활성화 (leaseTime 지정)
     *
     * leaseTime을 지정하면 Watch Dog이 비활성화됨
     * - 지정된 시간 후 자동 만료
     * - 장애 시 빠른 복구가 필요한 경우 사용
     */
    public void executeWithFixedLease(String resourceId, Runnable task) {
        String lockKey = "lock:resource:" + resourceId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // leaseTime 지정 → Watch Dog 비활성화
            boolean acquired = lock.tryLock(10, 30, TimeUnit.SECONDS);

            if (!acquired) {
                throw new LockAcquisitionException("Failed to acquire lock: " + lockKey);
            }

            log.info("Lock acquired with fixed lease: {}", lockKey);
            task.run();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException("Interrupted", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

### 8.4 대응 전략 2: 락 소유권 검증 (Fencing Token)

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class FencingTokenLockService {

    private final RedissonClient redissonClient;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Fencing Token을 사용한 안전한 락
     *
     * 원리:
     * 1. 락 획득 시 단조 증가하는 토큰 발급
     * 2. 리소스 변경 시 토큰 검증
     * 3. 더 큰 토큰으로 변경된 경우 거부
     */
    public <T> T executeWithFencingToken(String resourceId, FencedOperation<T> operation) {
        String lockKey = "lock:fenced:" + resourceId;
        String tokenKey = "token:fenced:" + resourceId;

        RLock lock = redissonClient.getLock(lockKey);

        try {
            if (!lock.tryLock(10, 30, TimeUnit.SECONDS)) {
                throw new LockAcquisitionException("Failed to acquire lock");
            }

            // Fencing Token 발급 (단조 증가)
            Long fencingToken = stringRedisTemplate.opsForValue()
                    .increment(tokenKey);

            log.info("Lock acquired: key={}, fencingToken={}", lockKey, fencingToken);

            // 토큰과 함께 작업 실행
            return operation.execute(fencingToken);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException("Interrupted", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @FunctionalInterface
    public interface FencedOperation<T> {
        T execute(Long fencingToken);
    }
}

// 사용 예시: 리소스 저장 시 토큰 검증
@Service
@RequiredArgsConstructor
public class StockService {

    private final FencingTokenLockService lockService;
    private final StockRepository stockRepository;

    public void updateStock(Long productId, int newQuantity) {
        lockService.executeWithFencingToken("stock:" + productId, (fencingToken) -> {
            Stock stock = stockRepository.findByProductId(productId)
                    .orElseThrow();

            // Fencing Token 검증
            if (stock.getLastFencingToken() != null &&
                stock.getLastFencingToken() >= fencingToken) {
                log.warn("Stale operation detected: current={}, new={}",
                        stock.getLastFencingToken(), fencingToken);
                throw new StaleOperationException("Operation rejected by fencing token");
            }

            stock.setQuantity(newQuantity);
            stock.setLastFencingToken(fencingToken);
            return stockRepository.save(stock);
        });
    }
}
```

### 8.5 대응 전략 3: 안전한 락 해제 (Lua Script)

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class SafeLockReleaseService {

    private final StringRedisTemplate redisTemplate;

    // 소유자 검증 후 삭제하는 Lua 스크립트
    private static final String SAFE_UNLOCK_SCRIPT = """
        if redis.call('get', KEYS[1]) == ARGV[1] then
            return redis.call('del', KEYS[1])
        else
            return 0
        end
        """;

    private final RedisScript<Long> safeUnlockScript = new DefaultRedisScript<>(
            SAFE_UNLOCK_SCRIPT, Long.class);

    /**
     * 직접 구현하는 안전한 분산 락
     * (Redisson을 사용하지 않는 경우)
     */
    public boolean tryLock(String lockKey, String ownerId, Duration ttl) {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, ownerId, ttl);
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * 소유자 검증 후 안전하게 해제
     *
     * 문제: 단순 DEL은 다른 서버의 락을 삭제할 수 있음
     * 해결: GET + 비교 + DEL을 원자적으로 수행 (Lua Script)
     */
    public boolean safeUnlock(String lockKey, String ownerId) {
        Long result = redisTemplate.execute(
                safeUnlockScript,
                List.of(lockKey),
                ownerId
        );

        if (result != null && result == 1) {
            log.debug("Lock released successfully: key={}, owner={}", lockKey, ownerId);
            return true;
        } else {
            log.warn("Lock release failed (not owner or expired): key={}, owner={}",
                    lockKey, ownerId);
            return false;
        }
    }

    /**
     * 사용 예시
     */
    public void executeWithSafeLock(String resourceId, Runnable task) {
        String lockKey = "lock:" + resourceId;
        String ownerId = UUID.randomUUID().toString();

        try {
            if (!tryLock(lockKey, ownerId, Duration.ofSeconds(30))) {
                throw new LockAcquisitionException("Failed to acquire lock: " + lockKey);
            }

            task.run();

        } finally {
            safeUnlock(lockKey, ownerId);
        }
    }
}
```

### 8.6 대응 전략 4: 락 상태 모니터링

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class LockMonitoringService {

    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    /**
     * 락 상태 모니터링 및 알림
     */
    @Scheduled(fixedRate = 30000)
    public void monitorLocks() {
        // 주요 락 키 패턴 모니터링
        Set<String> lockKeys = redisTemplate.keys("lock:*");

        if (lockKeys != null) {
            int activeLocks = lockKeys.size();
            meterRegistry.gauge("redis.lock.active", activeLocks);

            for (String lockKey : lockKeys) {
                Long ttl = redisTemplate.getExpire(lockKey, TimeUnit.SECONDS);

                if (ttl != null && ttl > 0) {
                    // TTL이 곧 만료될 락 경고
                    if (ttl < 5) {
                        log.warn("Lock about to expire: key={}, ttl={}s", lockKey, ttl);
                        meterRegistry.counter("redis.lock.expiring.soon").increment();
                    }
                } else if (ttl != null && ttl == -1) {
                    // TTL 없는 락 (위험)
                    log.error("Lock without TTL detected: key={}", lockKey);
                    meterRegistry.counter("redis.lock.no.ttl").increment();
                }
            }
        }
    }

    /**
     * 장기 보유 락 탐지
     */
    @Scheduled(fixedRate = 60000)
    public void detectLongHeldLocks() {
        // 락 획득 시간 기록 키
        Set<String> lockTimeKeys = redisTemplate.keys("lock:acquired:*");

        if (lockTimeKeys != null) {
            Instant threshold = Instant.now().minus(Duration.ofMinutes(5));

            for (String timeKey : lockTimeKeys) {
                String acquiredTimeStr = redisTemplate.opsForValue().get(timeKey);

                if (acquiredTimeStr != null) {
                    Instant acquiredTime = Instant.parse(acquiredTimeStr);

                    if (acquiredTime.isBefore(threshold)) {
                        String lockKey = timeKey.replace("lock:acquired:", "lock:");
                        log.warn("Long-held lock detected: key={}, acquiredAt={}",
                                lockKey, acquiredTime);
                    }
                }
            }
        }
    }
}
```

### 8.7 Phantom Key 대응 체크리스트

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Phantom Key 대응 체크리스트                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  설계 단계 (Design)                                                   │
│  [ ] Watch Dog 사용 여부 결정 (자동 연장 vs 고정 TTL)                  │
│  [ ] 적정 TTL 산정 (예상 처리 시간 × 2~3배)                           │
│  [ ] Fencing Token 필요 여부 검토                                     │
│  [ ] 락 해제 방식 결정 (Lua Script로 소유자 검증)                      │
│                                                                       │
│  구현 단계 (Implementation)                                           │
│  [ ] Redisson Watch Dog 활용 또는 수동 연장 구현                       │
│  [ ] isHeldByCurrentThread() 체크 후 unlock                           │
│  [ ] 락 획득/해제 로깅                                                 │
│  [ ] 락 관련 메트릭 수집                                               │
│                                                                       │
│  운영 단계 (Operation)                                                │
│  [ ] TTL 없는 락 탐지 알림                                            │
│  [ ] 장기 보유 락 모니터링                                             │
│  [ ] 락 만료 임박 경고                                                 │
│  [ ] 락 관련 장애 런북 작성                                            │
│                                                                       │
│  테스트 (Testing)                                                      │
│  [ ] GC pause 시뮬레이션 테스트                                        │
│  [ ] 네트워크 지연 시뮬레이션 테스트                                    │
│  [ ] 동시 락 획득 테스트                                               │
│  [ ] 락 만료 시나리오 테스트                                           │
│                                                                       │
└─────────────────────────────────────────────────────────────────────┘
```

### 8.8 락 타임아웃 전략 결정 가이드

```
┌─────────────────────────────────────────────────────────────────────┐
│                     락 타임아웃 전략 결정 가이드                        │
│                                                                       │
│   작업 유형별 권장 전략:                                               │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                                                              │   │
│   │  [짧은 작업 (< 1초)]                                         │   │
│   │  - 전략: 고정 TTL (5~10초)                                   │   │
│   │  - Watch Dog: 불필요                                         │   │
│   │  - 예: 재고 차감, 포인트 적립                                 │   │
│   │                                                              │   │
│   │  [중간 작업 (1~30초)]                                        │   │
│   │  - 전략: Watch Dog 또는 충분한 TTL (60초)                    │   │
│   │  - 예: 주문 처리, 결제 처리                                   │   │
│   │                                                              │   │
│   │  [긴 작업 (> 30초)]                                          │   │
│   │  - 전략: Watch Dog 필수                                      │   │
│   │  - 주의: 작업 분할 검토                                       │   │
│   │  - 예: 배치 처리, 리포트 생성                                 │   │
│   │                                                              │   │
│   │  [불확실한 작업]                                              │   │
│   │  - 전략: Watch Dog + Fencing Token                           │   │
│   │  - 예: 외부 API 호출, 복잡한 비즈니스 로직                    │   │
│   │                                                              │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                       │
│   Redisson 설정 예시:                                                 │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │  Config config = new Config();                               │   │
│   │  config.setLockWatchdogTimeout(30000);  // 30초 (기본값)     │   │
│   │                                                              │   │
│   │  // Watch Dog 연장 주기 = lockWatchdogTimeout / 3 = 10초     │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                       │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 9. 테스트

### 분산 락 테스트

```java
@SpringBootTest
@Testcontainers
class DistributedLockTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Autowired
    private StockService stockService;

    @Autowired
    private StockRepository stockRepository;

    @Test
    @DisplayName("동시에 100개 요청이 와도 재고가 정확하게 감소해야 한다")
    void concurrentStockDecrease() throws InterruptedException {
        // given
        Long productId = 1L;
        int initialStock = 100;
        int concurrentRequests = 100;
        int quantityPerRequest = 1;

        stockRepository.save(new Stock(productId, initialStock));

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(concurrentRequests);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // when
        for (int i = 0; i < concurrentRequests; i++) {
            executor.submit(() -> {
                try {
                    stockService.decreaseStock(productId, quantityPerRequest);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // then
        Stock stock = stockRepository.findByProductId(productId).orElseThrow();
        assertThat(stock.getQuantity()).isEqualTo(0);
        assertThat(successCount.get()).isEqualTo(100);
        assertThat(failCount.get()).isEqualTo(0);
    }
}
```

---

## 9. 실습 과제

### 과제 1: 기본 분산 락 구현
1. Redisson 설정 및 연결
2. 재고 감소 API에 분산 락 적용
3. 동시성 테스트 작성

### 과제 2: AOP 기반 분산 락
1. @DistributedLock 어노테이션 생성
2. Aspect 구현
3. 다양한 메서드에 적용

### 과제 3: 다양한 락 유형 활용
1. ReadWriteLock으로 캐시 구현
2. Semaphore로 API Rate Limiting 구현
3. CountDownLatch로 배치 작업 동기화

### 체크리스트
```
[ ] Redisson 의존성 및 설정 완료
[ ] 기본 Lock 사용법 이해
[ ] AOP 기반 분산 락 구현
[ ] FairLock, ReadWriteLock 이해
[ ] Semaphore, CountDownLatch 이해
[ ] 동시성 테스트 작성
[ ] 데드락 방지 패턴 적용
```

---

## 참고 자료

- [Redisson 공식 문서](https://github.com/redisson/redisson/wiki)
- [Redisson 분산 락](https://github.com/redisson/redisson/wiki/8.-Distributed-locks-and-synchronizers)
- [Spring Boot Redisson Starter](https://github.com/redisson/redisson/tree/master/redisson-spring-boot-starter)
- [분산 락 패턴](https://redis.io/docs/manual/patterns/distributed-locks/)

---

## 다음 단계

[04-outbox-pattern.md](./04-outbox-pattern.md) - Outbox 패턴으로 이동
