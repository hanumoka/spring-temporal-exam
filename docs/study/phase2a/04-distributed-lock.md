# 분산 락 (Distributed Lock)

## 이 문서에서 배우는 것

- 분산 환경에서의 동시성 문제 이해
- 분산 락의 개념과 필요성
- Redis + Redisson을 활용한 분산 락 구현
- 실무 적용 패턴

---

## 1. 분산 환경에서의 동시성 문제

### 단일 서버에서의 동시성 제어

```java
// 단일 서버: synchronized로 해결 가능
public synchronized void decreaseStock(Long productId, int quantity) {
    Product product = productRepository.findById(productId);
    product.decreaseStock(quantity);
    productRepository.save(product);
}
```

### 다중 서버에서의 문제

```
서버 A                        서버 B
  │                             │
  │  재고 조회: 100개           │  재고 조회: 100개
  │        ↓                    │        ↓
  │  100 - 1 = 99              │  100 - 1 = 99
  │        ↓                    │        ↓
  │  저장: 99개                 │  저장: 99개  ← 동시 저장!
  │                             │
  └──────────────────────────────┘
              결과: 99개 (2개 팔았는데 1개만 차감됨!)
```

**문제**: `synchronized`는 같은 JVM 내에서만 동작. 다른 서버의 요청은 제어 불가!

### 해결 방법: 분산 락

```
서버 A                        서버 B
  │                             │
  │  락 획득 시도               │  락 획득 시도
  │  ✓ 락 획득!                 │  ✗ 대기...
  │        ↓                    │     │
  │  재고 조회: 100개           │     │
  │  100 - 1 = 99              │     │
  │  저장: 99개                 │     │
  │        ↓                    │     │
  │  락 해제                    │  ✓ 락 획득!
  │                             │        ↓
  │                             │  재고 조회: 99개
  │                             │  99 - 1 = 98
  │                             │  저장: 98개
  │                             │        ↓
  │                             │  락 해제
  └──────────────────────────────┘
              결과: 98개 (정확!)
```

---

## 2. 분산 락 구현 방법

### 2.1 DB 기반 락

```sql
-- 별도 락 테이블 사용
SELECT * FROM locks WHERE name = 'stock_lock' FOR UPDATE;
```

**장점**: 별도 인프라 불필요
**단점**: DB 부하, 성능 이슈

### 2.2 Redis 기반 락 (Redisson)

```
┌─────────────────────────────────────────────────────────────┐
│                        Redis                                 │
│                                                              │
│  Key: "lock:stock:product:123"                              │
│  Value: "서버A의 고유 ID"                                    │
│  TTL: 30초 (자동 만료)                                       │
│                                                              │
└─────────────────────────────────────────────────────────────┘
        ▲                           ▲
        │ 락 획득                    │ 락 획득 실패 (대기)
        │                           │
   ┌────┴────┐                 ┌────┴────┐
   │ 서버 A  │                 │ 서버 B  │
   └─────────┘                 └─────────┘
```

**장점**: 빠른 성능, 자동 만료, 분산 환경에 적합
**단점**: Redis 의존성

### 2.3 ZooKeeper 기반 락

강력한 일관성 보장, 하지만 설정이 복잡.

---

## 3. Redisson 소개

### Redisson이란?

**Redisson**은 Redis 기반의 Java 분산 객체/컬렉션 라이브러리입니다.

```
┌─────────────────────────────────────────────────────────────┐
│                      Redisson                                │
│                                                              │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐              │
│  │    Lock    │ │  RMap      │ │  RQueue    │   ...        │
│  │ (분산 락)  │ │ (분산 맵)  │ │ (분산 큐)  │              │
│  └────────────┘ └────────────┘ └────────────┘              │
│                                                              │
│         ↓                ↓                ↓                 │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                     Redis                            │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 의존성 추가

```groovy
// build.gradle
dependencies {
    implementation 'org.redisson:redisson-spring-boot-starter:4.0.0'  // Spring Boot 4 호환
}
```

### 설정

```yaml
# application.yml
spring:
  data:
    redis:
      host: localhost
      port: 6379

# Redisson 상세 설정 (선택)
redisson:
  single-server-config:
    address: "redis://localhost:6379"
    connection-minimum-idle-size: 5
    connection-pool-size: 10
```

---

## 4. Redisson Lock 사용법

### 4.1 기본 사용법

```java
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final RedissonClient redissonClient;
    private final ProductRepository productRepository;

    public void decreaseStock(Long productId, int quantity) {
        // 락 키 생성 (상품별로 다른 락)
        String lockKey = "lock:stock:product:" + productId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 락 획득 시도 (최대 10초 대기, 획득 후 5초간 유지)
            boolean acquired = lock.tryLock(10, 5, TimeUnit.SECONDS);

            if (!acquired) {
                throw new LockAcquisitionException("락 획득 실패");
            }

            // 락 획득 성공 - 비즈니스 로직 실행
            Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

            product.decreaseStock(quantity);
            productRepository.save(product);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException("락 획득 중 인터럽트 발생");
        } finally {
            // 락 해제 (반드시 finally에서!)
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

### 4.2 tryLock 파라미터 설명

```java
lock.tryLock(waitTime, leaseTime, TimeUnit)
```

| 파라미터 | 설명 | 권장 값 |
|----------|------|---------|
| waitTime | 락 획득 대기 시간 | 5~30초 |
| leaseTime | 락 자동 해제 시간 | 작업 예상 시간 + 여유 |
| TimeUnit | 시간 단위 | SECONDS |

### 4.3 어노테이션 기반 사용 (커스텀)

```java
// 커스텀 어노테이션 정의
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {
    String key();                    // 락 키
    long waitTime() default 5;       // 대기 시간 (초)
    long leaseTime() default 10;     // 유지 시간 (초)
}
```

```java
// AOP로 처리
@Aspect
@Component
@RequiredArgsConstructor
public class DistributedLockAspect {

    private final RedissonClient redissonClient;

    @Around("@annotation(distributedLock)")
    public Object lock(ProceedingJoinPoint joinPoint, DistributedLock distributedLock)
            throws Throwable {

        String key = parseKey(distributedLock.key(), joinPoint);
        RLock lock = redissonClient.getLock(key);

        try {
            boolean acquired = lock.tryLock(
                distributedLock.waitTime(),
                distributedLock.leaseTime(),
                TimeUnit.SECONDS
            );

            if (!acquired) {
                throw new LockAcquisitionException("락 획득 실패: " + key);
            }

            return joinPoint.proceed();

        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private String parseKey(String keyExpression, ProceedingJoinPoint joinPoint) {
        // SpEL 파싱 로직 (생략)
        return keyExpression;
    }
}
```

```java
// 사용
@Service
public class InventoryService {

    @DistributedLock(key = "lock:stock:product:#{#productId}")
    public void decreaseStock(Long productId, int quantity) {
        // 락 관련 코드 없이 비즈니스 로직만!
        Product product = productRepository.findById(productId)
            .orElseThrow();
        product.decreaseStock(quantity);
        productRepository.save(product);
    }
}
```

---

## 5. 락 종류

### 5.1 RLock (일반 락)

```java
RLock lock = redissonClient.getLock("myLock");
```

- 재진입 가능 (같은 스레드가 여러 번 획득 가능)
- 가장 일반적인 사용

### 5.2 RReadWriteLock (읽기-쓰기 락)

```java
RReadWriteLock rwLock = redissonClient.getReadWriteLock("myRWLock");

// 읽기 락 (여러 클라이언트 동시 획득 가능)
RLock readLock = rwLock.readLock();
readLock.lock();
try {
    // 읽기 작업
} finally {
    readLock.unlock();
}

// 쓰기 락 (단독 획득)
RLock writeLock = rwLock.writeLock();
writeLock.lock();
try {
    // 쓰기 작업
} finally {
    writeLock.unlock();
}
```

**사용 시나리오**: 읽기가 많고 쓰기가 적은 경우

### 5.3 RFencedLock (펜싱 락)

```java
RFencedLock lock = redissonClient.getFencedLock("myFencedLock");
Long token = lock.lockAndGetToken();
try {
    // token을 사용하여 유효성 검증
} finally {
    lock.unlock();
}
```

**사용 시나리오**: 더 강력한 안전성이 필요한 경우

### 5.4 RSemaphore (세마포어)

```java
RSemaphore semaphore = redissonClient.getSemaphore("mySemaphore");
semaphore.trySetPermits(5);  // 최대 5개 동시 접근

semaphore.acquire();  // 허가 획득
try {
    // 작업 수행
} finally {
    semaphore.release();  // 허가 반환
}
```

**사용 시나리오**: 동시 접근 수 제한 (예: API 호출 제한)

---

## 6. 세마포어 (Semaphore) 심화

### 6.1 세마포어 vs 락

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Lock vs Semaphore                                 │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [Lock - 상호 배제]                                                  │
│  ┌─────────┐                                                        │
│  │ 리소스  │ ←── 오직 1개의 스레드만 접근                           │
│  └─────────┘                                                        │
│                                                                      │
│  [Semaphore - 동시 접근 제한]                                        │
│  ┌─────────┐                                                        │
│  │ 리소스  │ ←── N개의 스레드까지 동시 접근 허용                    │
│  └─────────┘                                                        │
│      ▲                                                              │
│      │ permits = 5                                                  │
│      │                                                              │
│  [Thread 1] ✓                                                       │
│  [Thread 2] ✓                                                       │
│  [Thread 3] ✓                                                       │
│  [Thread 4] ✓                                                       │
│  [Thread 5] ✓                                                       │
│  [Thread 6] ✗ 대기...                                               │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

| 특성 | Lock | Semaphore |
|------|------|-----------|
| 동시 접근 수 | 1개 | N개 (설정 가능) |
| 사용 목적 | 상호 배제 | 리소스 풀 제한 |
| 재진입 | 가능 (RLock) | 불가 |
| 소유자 | 있음 | 없음 |

### 6.2 RSemaphore 기본 사용법

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class ExternalApiService {

    private final RedissonClient redissonClient;
    private final RestTemplate restTemplate;

    // 외부 API 동시 호출 제한 (최대 10개)
    private static final String SEMAPHORE_KEY = "semaphore:external-api";
    private static final int MAX_PERMITS = 10;

    @PostConstruct
    public void initSemaphore() {
        RSemaphore semaphore = redissonClient.getSemaphore(SEMAPHORE_KEY);
        semaphore.trySetPermits(MAX_PERMITS);  // 최초 1회만 설정됨
        log.info("세마포어 초기화: {} (permits={})", SEMAPHORE_KEY, MAX_PERMITS);
    }

    public ApiResponse callExternalApi(ApiRequest request) {
        RSemaphore semaphore = redissonClient.getSemaphore(SEMAPHORE_KEY);

        try {
            // 최대 5초 대기 후 permit 획득 시도
            boolean acquired = semaphore.tryAcquire(5, TimeUnit.SECONDS);

            if (!acquired) {
                throw new TooManyRequestsException("외부 API 호출 제한 초과");
            }

            log.info("Permit 획득 - 현재 사용 가능: {}", semaphore.availablePermits());

            // 외부 API 호출
            return restTemplate.postForObject(
                "https://external-api.com/endpoint",
                request,
                ApiResponse.class
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("API 호출 중 인터럽트 발생");
        } finally {
            semaphore.release();
            log.info("Permit 반환 - 현재 사용 가능: {}", semaphore.availablePermits());
        }
    }
}
```

### 6.3 여러 Permit 한번에 획득

```java
public class BatchProcessor {

    private final RedissonClient redissonClient;

    // 배치 작업: 한번에 여러 permit 필요
    public void processBatch(List<Task> tasks) {
        RSemaphore semaphore = redissonClient.getSemaphore("semaphore:batch");

        int requiredPermits = Math.min(tasks.size(), 5);  // 최대 5개

        try {
            // 여러 permit 한번에 획득
            if (!semaphore.tryAcquire(requiredPermits, 10, TimeUnit.SECONDS)) {
                throw new ResourceLimitException("배치 처리 리소스 부족");
            }

            // 병렬 처리
            tasks.parallelStream()
                .limit(requiredPermits)
                .forEach(this::processTask);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("배치 처리 중 인터럽트");
        } finally {
            // 획득한 만큼 반환
            semaphore.release(requiredPermits);
        }
    }
}
```

### 6.4 RPermitExpirableSemaphore (만료 가능 세마포어)

permit에 TTL을 설정하여 자동 반환:

```java
@Service
@RequiredArgsConstructor
public class ConnectionPoolService {

    private final RedissonClient redissonClient;

    private static final String SEMAPHORE_KEY = "semaphore:db-connection";

    public void executeWithConnection(Runnable task) {
        RPermitExpirableSemaphore semaphore =
            redissonClient.getPermitExpirableSemaphore(SEMAPHORE_KEY);

        String permitId = null;

        try {
            // permit 획득 (30초 후 자동 만료)
            permitId = semaphore.tryAcquire(5, 30, TimeUnit.SECONDS);

            if (permitId == null) {
                throw new ConnectionPoolExhaustedException("연결 풀 고갈");
            }

            // 작업 수행
            task.run();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("연결 획득 중 인터럽트");
        } finally {
            // permitId로 반환 (만료되었으면 무시됨)
            if (permitId != null) {
                semaphore.tryRelease(permitId);
            }
        }
    }
}
```

**장점**:
- 클라이언트 크래시 시에도 permit이 자동 반환됨
- 좀비 permit 방지

### 6.5 동적 Permit 조정

```java
@Service
@RequiredArgsConstructor
public class DynamicSemaphoreService {

    private final RedissonClient redissonClient;

    private static final String SEMAPHORE_KEY = "semaphore:dynamic";

    // 운영 중 permit 수 조정
    public void adjustPermits(int newPermits) {
        RSemaphore semaphore = redissonClient.getSemaphore(SEMAPHORE_KEY);

        int currentPermits = semaphore.availablePermits();
        int delta = newPermits - currentPermits;

        if (delta > 0) {
            // permit 증가
            semaphore.addPermits(delta);
            log.info("Permit 증가: {} → {}", currentPermits, newPermits);
        } else if (delta < 0) {
            // permit 감소 (주의: 즉시 감소되지 않을 수 있음)
            semaphore.reducePermits(Math.abs(delta));
            log.info("Permit 감소 요청: {} → {}", currentPermits, newPermits);
        }
    }

    // 현재 상태 조회
    public SemaphoreStatus getStatus() {
        RSemaphore semaphore = redissonClient.getSemaphore(SEMAPHORE_KEY);
        return new SemaphoreStatus(
            semaphore.availablePermits(),
            // 참고: 전체 permits 조회 API는 없음, 별도 관리 필요
            LocalDateTime.now()
        );
    }
}
```

### 6.6 Rate Limiting 구현

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitService {

    private final RedissonClient redissonClient;

    /**
     * 슬라이딩 윈도우 Rate Limiter
     *
     * @param key 제한 키 (예: "api:user:123")
     * @param maxRequests 윈도우 내 최대 요청 수
     * @param windowSeconds 윈도우 크기 (초)
     */
    public boolean tryAcquire(String key, int maxRequests, int windowSeconds) {
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);

        // Rate 설정 (최초 1회)
        rateLimiter.trySetRate(
            RateType.OVERALL,           // 전체 제한
            maxRequests,                // 최대 요청 수
            windowSeconds,              // 시간 윈도우
            RateIntervalUnit.SECONDS
        );

        // permit 획득 시도 (비차단)
        return rateLimiter.tryAcquire();
    }

    /**
     * 사용 예시: API 엔드포인트 Rate Limiting
     */
    public ApiResponse callApi(String userId, ApiRequest request) {
        String rateLimitKey = "ratelimit:api:user:" + userId;

        // 분당 100개 요청 제한
        if (!tryAcquire(rateLimitKey, 100, 60)) {
            throw new RateLimitExceededException(
                "요청 한도 초과. 잠시 후 다시 시도해주세요."
            );
        }

        return processRequest(request);
    }
}
```

### 6.7 Semaphore vs Resilience4j RateLimiter

| 특성 | RSemaphore | Resilience4j RateLimiter |
|------|------------|-------------------------|
| 분산 환경 | ✅ Redis 기반 | ❌ 단일 인스턴스 |
| 설정 위치 | 코드/Redis | application.yml |
| 동적 조정 | ✅ 런타임 가능 | ❌ 재시작 필요 |
| 모니터링 | Redis 직접 조회 | Actuator 연동 |
| 의존성 | Redis 필수 | 없음 |
| 정확도 | 높음 (분산 동기화) | 인스턴스별 독립 |

**선택 가이드**:

```
단일 인스턴스 or 인스턴스별 독립 제한 → Resilience4j
다중 인스턴스 전체 제한 필요 → RSemaphore / RRateLimiter
```

### 6.8 실무 적용 패턴

#### 패턴 1: 외부 API 동시 호출 제한

```java
@Service
public class PaymentGatewayService {

    private final RedissonClient redissonClient;

    // PG사별로 다른 동시 호출 제한
    public PaymentResult processPayment(PaymentRequest request) {
        String semaphoreKey = "semaphore:pg:" + request.getPgProvider();
        RSemaphore semaphore = redissonClient.getSemaphore(semaphoreKey);

        try {
            if (!semaphore.tryAcquire(3, TimeUnit.SECONDS)) {
                // 대기열에 추가하거나 다른 PG로 폴백
                return fallbackToPg(request);
            }

            return callPgApi(request);

        } finally {
            semaphore.release();
        }
    }
}
```

#### 패턴 2: 리소스 풀 관리

```java
@Component
public class FileProcessingPool {

    private final RedissonClient redissonClient;

    private static final int MAX_CONCURRENT_FILES = 5;

    public ProcessingResult processFile(MultipartFile file) {
        RSemaphore semaphore = redissonClient.getSemaphore("semaphore:file-processing");
        semaphore.trySetPermits(MAX_CONCURRENT_FILES);

        try {
            if (!semaphore.tryAcquire(30, TimeUnit.SECONDS)) {
                return ProcessingResult.queued("처리 대기 중...");
            }

            // CPU 집약적 파일 처리
            return doHeavyProcessing(file);

        } finally {
            semaphore.release();
        }
    }
}
```

#### 패턴 3: 사용자별 Rate Limiting (AOP)

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    int requests() default 10;      // 최대 요청 수
    int seconds() default 60;       // 시간 윈도우
    String keyPrefix() default "";  // 키 접두사
}

@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RedissonClient redissonClient;

    @Around("@annotation(rateLimit)")
    public Object checkRateLimit(ProceedingJoinPoint joinPoint, RateLimit rateLimit)
            throws Throwable {

        String userId = getCurrentUserId();
        String key = rateLimit.keyPrefix() + ":user:" + userId;

        RRateLimiter limiter = redissonClient.getRateLimiter(key);
        limiter.trySetRate(
            RateType.OVERALL,
            rateLimit.requests(),
            rateLimit.seconds(),
            RateIntervalUnit.SECONDS
        );

        if (!limiter.tryAcquire()) {
            throw new RateLimitExceededException("요청 한도 초과");
        }

        return joinPoint.proceed();
    }
}

// 사용
@RestController
public class ApiController {

    @RateLimit(requests = 100, seconds = 60, keyPrefix = "api:order")
    @PostMapping("/orders")
    public OrderResponse createOrder(@RequestBody OrderRequest request) {
        // ...
    }
}
```

### 6.9 세마포어 모니터링

```java
@RestController
@RequiredArgsConstructor
public class SemaphoreMonitorController {

    private final RedissonClient redissonClient;

    @GetMapping("/admin/semaphores")
    public List<SemaphoreInfo> getAllSemaphores() {
        // 등록된 세마포어 키 목록 (별도 관리 필요)
        List<String> keys = List.of(
            "semaphore:external-api",
            "semaphore:file-processing",
            "semaphore:pg:nice"
        );

        return keys.stream()
            .map(key -> {
                RSemaphore semaphore = redissonClient.getSemaphore(key);
                return new SemaphoreInfo(
                    key,
                    semaphore.availablePermits(),
                    semaphore.isExists()
                );
            })
            .toList();
    }
}
```

---

## 7. 주의사항 및 베스트 프랙티스

### 7.1 락 키 설계

```java
// ✓ 좋은 예: 구체적인 키
"lock:inventory:product:123"
"lock:order:customer:456"

// ✗ 나쁜 예: 너무 넓은 범위
"lock:inventory"  // 모든 상품이 하나의 락 공유
```

### 7.2 leaseTime 설정

```java
// ✗ 나쁜 예: leaseTime 없음 (영구 락 위험)
lock.lock();  // 서버 다운 시 락이 영원히 유지됨!

// ✓ 좋은 예: 적절한 leaseTime 설정
lock.tryLock(10, 30, TimeUnit.SECONDS);  // 30초 후 자동 해제
```

### 7.3 finally에서 unlock

```java
// ✓ 필수: finally에서 락 해제
try {
    lock.lock();
    // 작업
} finally {
    if (lock.isHeldByCurrentThread()) {
        lock.unlock();
    }
}
```

### 7.4 isHeldByCurrentThread 체크

```java
// 다른 스레드가 획득한 락을 해제하면 예외 발생
if (lock.isHeldByCurrentThread()) {
    lock.unlock();
}
```

### 7.5 락 범위 최소화

```java
// ✗ 나쁜 예: 불필요하게 넓은 락 범위
lock.lock();
try {
    validateRequest();      // 락 불필요
    fetchExternalData();    // 락 불필요
    updateStock();          // 락 필요한 부분
    sendNotification();     // 락 불필요
} finally {
    lock.unlock();
}

// ✓ 좋은 예: 최소 범위만 락
validateRequest();
fetchExternalData();

lock.lock();
try {
    updateStock();  // 락 필요한 부분만!
} finally {
    lock.unlock();
}

sendNotification();
```

---

## 8. 우리 프로젝트 적용

### 재고 서비스에 분산 락 적용

```java
// service-inventory/src/main/java/com/example/inventory/service/InventoryService.java
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final RedissonClient redissonClient;
    private final InventoryRepository inventoryRepository;

    private static final String LOCK_PREFIX = "lock:inventory:product:";

    @Transactional
    public ReservationResponse reserveStock(ReservationRequest request) {
        String lockKey = LOCK_PREFIX + request.productId();
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 락 획득 (최대 5초 대기, 30초 후 자동 해제)
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new LockAcquisitionException(
                    "재고 락 획득 실패: productId=" + request.productId()
                );
            }

            log.info("락 획득 성공: {}", lockKey);

            // 재고 확인 및 차감
            Inventory inventory = inventoryRepository
                .findByProductId(request.productId())
                .orElseThrow(() -> new ProductNotFoundException(request.productId()));

            if (inventory.getQuantity() < request.quantity()) {
                throw new InsufficientStockException(
                    request.productId(),
                    inventory.getQuantity(),
                    request.quantity()
                );
            }

            inventory.decrease(request.quantity());
            inventoryRepository.save(inventory);

            // 예약 기록 생성
            Reservation reservation = Reservation.create(
                request.orderId(),
                request.productId(),
                request.quantity()
            );
            reservationRepository.save(reservation);

            log.info("재고 예약 완료: {}", reservation.getId());

            return ReservationResponse.from(reservation);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException("락 획득 중 인터럽트 발생");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("락 해제: {}", lockKey);
            }
        }
    }
}
```

### 결제 서비스에 세마포어 적용

```java
// service-payment/src/main/java/com/example/payment/service/PaymentService.java
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final RedissonClient redissonClient;
    private final PaymentRepository paymentRepository;
    private final PaymentGatewayClient pgClient;  // 외부 PG사 API 클라이언트

    // PG사별 동시 호출 제한
    private static final String SEMAPHORE_PREFIX = "semaphore:pg:";
    private static final int MAX_CONCURRENT_CALLS = 10;  // 동시 최대 10개 요청

    @PostConstruct
    public void initSemaphores() {
        // 사용하는 PG사별로 세마포어 초기화
        List<String> pgProviders = List.of("toss", "nice", "kg");
        for (String provider : pgProviders) {
            RSemaphore semaphore = redissonClient.getSemaphore(SEMAPHORE_PREFIX + provider);
            semaphore.trySetPermits(MAX_CONCURRENT_CALLS);
            log.info("PG 세마포어 초기화: {} (permits={})", provider, MAX_CONCURRENT_CALLS);
        }
    }

    @Transactional
    public PaymentResponse processPayment(PaymentRequest request) {
        String pgProvider = request.pgProvider();  // "toss", "nice", "kg"
        String semaphoreKey = SEMAPHORE_PREFIX + pgProvider;
        RSemaphore semaphore = redissonClient.getSemaphore(semaphoreKey);

        try {
            // 세마포어 획득 시도 (최대 5초 대기)
            boolean acquired = semaphore.tryAcquire(5, TimeUnit.SECONDS);

            if (!acquired) {
                log.warn("PG 호출 제한 초과: {}", pgProvider);
                throw new PaymentThrottledException(
                    "결제 요청이 많습니다. 잠시 후 다시 시도해주세요."
                );
            }

            log.info("PG 세마포어 획득: {} (available={})",
                pgProvider, semaphore.availablePermits());

            // 결제 정보 생성
            Payment payment = Payment.create(
                request.orderId(),
                request.amount(),
                pgProvider
            );

            // 외부 PG사 API 호출
            PgResponse pgResponse = pgClient.requestPayment(
                pgProvider,
                request.amount(),
                request.cardInfo()
            );

            // 결제 결과 저장
            payment.complete(pgResponse.transactionId());
            paymentRepository.save(payment);

            log.info("결제 완료: paymentId={}, txId={}",
                payment.getId(), pgResponse.transactionId());

            return PaymentResponse.success(payment);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentException("결제 처리 중 인터럽트 발생");
        } catch (PgApiException e) {
            log.error("PG API 오류: {}", e.getMessage());
            throw new PaymentException("결제 처리 실패: " + e.getMessage());
        } finally {
            // 세마포어 반환
            semaphore.release();
            log.debug("PG 세마포어 반환: {} (available={})",
                pgProvider, semaphore.availablePermits());
        }
    }

    // 결제 취소 (환불)도 동일하게 세마포어 적용
    @Transactional
    public RefundResponse refundPayment(RefundRequest request) {
        Payment payment = paymentRepository.findById(request.paymentId())
            .orElseThrow(() -> new PaymentNotFoundException(request.paymentId()));

        String semaphoreKey = SEMAPHORE_PREFIX + payment.getPgProvider();
        RSemaphore semaphore = redissonClient.getSemaphore(semaphoreKey);

        try {
            if (!semaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                throw new PaymentThrottledException("환불 요청이 많습니다.");
            }

            // 외부 PG사 환불 API 호출
            pgClient.requestRefund(payment.getPgProvider(), payment.getTransactionId());

            payment.refund();
            paymentRepository.save(payment);

            return RefundResponse.success(payment);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentException("환불 처리 중 인터럽트 발생");
        } finally {
            semaphore.release();
        }
    }
}
```

### 알림 서비스에 세마포어 적용

```java
// service-notification/src/main/java/com/example/notification/service/NotificationService.java
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final RedissonClient redissonClient;

    // 채널별 동시 발송 제한
    private static final Map<String, Integer> CHANNEL_LIMITS = Map.of(
        "sms", 5,       // SMS: 동시 5건
        "email", 20,    // Email: 동시 20건
        "push", 50      // Push: 동시 50건
    );

    @PostConstruct
    public void initSemaphores() {
        CHANNEL_LIMITS.forEach((channel, permits) -> {
            RSemaphore semaphore = redissonClient.getSemaphore("semaphore:notification:" + channel);
            semaphore.trySetPermits(permits);
            log.info("알림 세마포어 초기화: {} (permits={})", channel, permits);
        });
    }

    public void sendNotification(NotificationRequest request) {
        String channel = request.channel();  // "sms", "email", "push"
        String semaphoreKey = "semaphore:notification:" + channel;
        RSemaphore semaphore = redissonClient.getSemaphore(semaphoreKey);

        try {
            // 채널별 세마포어 획득 (최대 10초 대기)
            if (!semaphore.tryAcquire(10, TimeUnit.SECONDS)) {
                log.warn("알림 발송 제한 초과: {}", channel);
                // 대기열에 추가하거나 나중에 재시도
                throw new NotificationThrottledException(
                    "알림 발송이 지연되고 있습니다."
                );
            }

            log.info("{} 세마포어 획득 (available={})", channel, semaphore.availablePermits());

            // 채널별 발송 처리
            switch (channel) {
                case "sms" -> sendSms(request);
                case "email" -> sendEmail(request);
                case "push" -> sendPush(request);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotificationException("알림 발송 중 인터럽트 발생");
        } finally {
            semaphore.release();
        }
    }

    private void sendSms(NotificationRequest request) {
        // SMS 발송 API 호출
    }

    private void sendEmail(NotificationRequest request) {
        // 이메일 발송 API 호출
    }

    private void sendPush(NotificationRequest request) {
        // 푸시 알림 발송
    }
}
```

### 프로젝트 동시성 제어 요약

```
┌─────────────────────────────────────────────────────────────────────┐
│                    서비스별 동시성 제어 전략                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [Inventory Service]                                                 │
│  ├── 메커니즘: 분산 락 (RLock)                                       │
│  ├── 대상: 상품별 재고 차감                                          │
│  └── 이유: 동일 상품 재고는 순차 처리 필수                            │
│                                                                      │
│  [Order Service]                                                     │
│  ├── 메커니즘: 낙관적 락 (@Version)                                  │
│  ├── 대상: 주문 상태 변경                                            │
│  └── 이유: 충돌 드묾, 충돌 시 재시도 가능                             │
│                                                                      │
│  [Payment Service]                                                   │
│  ├── 메커니즘: 세마포어 (RSemaphore)                                 │
│  ├── 대상: PG사별 API 호출                                           │
│  └── 이유: 외부 API TPS 제한 준수                                    │
│                                                                      │
│  [Notification Service]                                              │
│  ├── 메커니즘: 세마포어 (RSemaphore)                                 │
│  ├── 대상: 채널별 발송 (SMS, Email, Push)                            │
│  └── 이유: 외부 API 호출 제한 준수                                   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 9. 타임아웃 이슈 및 대응 전략

### 9.1 타임아웃 레이어 구조

분산 락 사용 시 여러 레이어의 타임아웃이 관련됩니다:

```
┌─────────────────────────────────────────────────────────────────────┐
│                    타임아웃 레이어 구조                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [클라이언트]                                                        │
│       │                                                              │
│       │ ① HTTP 타임아웃 (예: 30초)                                   │
│       ▼                                                              │
│  [API Gateway / Controller]                                          │
│       │                                                              │
│       │ ② 분산 락 waitTime (예: 10초)                               │
│       ▼                                                              │
│  [Service Layer]                                                     │
│       │                                                              │
│       │ ③ 분산 락 leaseTime (예: 30초)                              │
│       │ ④ DB 트랜잭션 타임아웃 (예: 30초)                           │
│       ▼                                                              │
│  [Database / Redis]                                                  │
│                                                                      │
│  ⚠️ 문제: 이 타임아웃들이 서로 맞지 않으면 다양한 이슈 발생!         │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 9.2 Case 1: 분산 락 획득 실패 (waitTime 초과)

**상황**: 다른 서버가 락을 오래 잡고 있어서 waitTime 내에 획득 불가

```
서버 A: 락 획득 (30초간 작업 중...)
서버 B: 락 획득 시도 → 10초 대기 → 실패!
```

**대응: 재시도 로직 구현**

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final RedissonClient redissonClient;

    private static final int MAX_RETRY = 3;
    private static final long RETRY_DELAY_MS = 500;

    public ReservationResponse reserveStockWithRetry(ReservationRequest request) {
        String lockKey = "lock:inventory:product:" + request.productId();
        RLock lock = redissonClient.getLock(lockKey);

        int retryCount = 0;

        while (retryCount < MAX_RETRY) {
            try {
                if (lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                    try {
                        return doReserveStock(request);
                    } finally {
                        if (lock.isHeldByCurrentThread()) {
                            lock.unlock();
                        }
                    }
                }

                retryCount++;
                log.warn("락 획득 실패, 재시도 {}/{}: {}", retryCount, MAX_RETRY, lockKey);

                if (retryCount < MAX_RETRY) {
                    Thread.sleep(RETRY_DELAY_MS * retryCount);  // 점진적 대기
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServiceException("락 획득 중 인터럽트");
            }
        }

        throw new LockAcquisitionException(
            "락 획득 실패 (재시도 " + MAX_RETRY + "회 초과): " + lockKey
        );
    }
}
```

**클라이언트 응답 처리:**

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(LockAcquisitionException.class)
    public ResponseEntity<ErrorResponse> handleLockFailure(LockAcquisitionException e) {
        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)  // 503
            .header("Retry-After", "5")  // 5초 후 재시도 권장
            .body(new ErrorResponse(
                "LOCK_ACQUISITION_FAILED",
                "현재 요청이 많습니다. 잠시 후 다시 시도해주세요."
            ));
    }
}
```

### 9.3 Case 2: 락 보유 중 HTTP 타임아웃

**상황**: 락 획득 후 작업 중 클라이언트가 HTTP 타임아웃으로 연결 종료

```
시간   서버                      클라이언트
0초    락 획득                   요청 전송
30초   작업 중...                ⚠️ HTTP 타임아웃! 연결 끊김
40초   작업 완료, 응답 전송 시도  (이미 연결 없음)

결과: 서버는 정상 처리했지만 클라이언트는 실패로 인식
      → 클라이언트가 재시도하면 중복 처리 위험!
```

**대응: 멱등성 키 사용**

```java
@RestController
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;
    private final IdempotencyService idempotencyService;

    @PostMapping("/reservations")
    public ResponseEntity<ReservationResponse> reserve(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestBody ReservationRequest request) {

        // 1. 이미 처리된 요청인지 확인
        Optional<ReservationResponse> cached = idempotencyService.get(idempotencyKey);
        if (cached.isPresent()) {
            log.info("중복 요청 감지, 캐시된 결과 반환: {}", idempotencyKey);
            return ResponseEntity.ok(cached.get());
        }

        // 2. 새 요청 처리
        ReservationResponse response = inventoryService.reserveStock(request);

        // 3. 결과 캐시 (TTL: 24시간)
        idempotencyService.save(idempotencyKey, response, Duration.ofHours(24));

        return ResponseEntity.ok(response);
    }
}
```

### 9.4 Case 3: 락 보유 중 leaseTime 만료

**상황**: 작업이 예상보다 오래 걸려 leaseTime 만료

```
시간   서버 A                    서버 B
0초    락 획득 (leaseTime=30초)
30초   ⚠️ leaseTime 만료!        락 획득!
35초   DB 저장!                  DB 저장!

결과: 두 서버가 동시에 같은 데이터 수정! (정합성 깨짐)
```

**대응 1: Watchdog (락 자동 갱신)**

```java
public ReservationResponse reserveStock(ReservationRequest request) {
    String lockKey = "lock:inventory:product:" + request.productId();
    RLock lock = redissonClient.getLock(lockKey);

    try {
        // ⚠️ leaseTime을 -1로 설정하면 Watchdog 활성화
        // Watchdog: 락 보유 중 자동으로 leaseTime 갱신 (30초마다)
        if (!lock.tryLock(10, -1, TimeUnit.SECONDS)) {
            throw new LockAcquisitionException("락 획득 실패");
        }

        log.info("락 획득 (Watchdog 활성화): {}", lockKey);
        return doReserveStock(request);

    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ServiceException("락 획득 중 인터럽트");
    } finally {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

**대응 2: 작업 전 락 유효성 검증**

```java
public ReservationResponse reserveStock(ReservationRequest request) {
    RFencedLock lock = redissonClient.getFencedLock(lockKey);

    try {
        Long fenceToken = lock.tryLockAndGetToken(10, 30, TimeUnit.SECONDS);
        if (fenceToken == null) {
            throw new LockAcquisitionException("락 획득 실패");
        }

        // 비즈니스 로직 수행
        Inventory inventory = inventoryRepository.findByProductId(request.productId())
            .orElseThrow();

        // 저장 전 락 유효성 검증
        if (!lock.isHeldByCurrentThread()) {
            throw new LockExpiredException("락이 만료되었습니다. 작업을 중단합니다.");
        }

        inventory.decrease(request.quantity());
        inventoryRepository.save(inventory);

        return ReservationResponse.success();

    } finally {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

### 9.5 Case 4: DB 트랜잭션 타임아웃

**상황**: 락 보유 중 DB 트랜잭션 타임아웃으로 롤백

**대응: 타임아웃 계층 설정**

```java
@Service
@RequiredArgsConstructor
public class InventoryService {

    // 타임아웃 계층: HTTP > (waitTime + leaseTime) > Transaction
    private static final long LOCK_WAIT_SECONDS = 5;
    private static final long LOCK_LEASE_SECONDS = 25;
    private static final int TX_TIMEOUT_SECONDS = 20;

    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public ReservationResponse reserveStock(ReservationRequest request) {
        String lockKey = "lock:inventory:product:" + request.productId();
        RLock lock = redissonClient.getLock(lockKey);

        try {
            if (!lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS)) {
                throw new LockAcquisitionException("락 획득 실패");
            }

            return doReserveStock(request);

        } catch (TransactionTimedOutException e) {
            log.error("트랜잭션 타임아웃: {}", e.getMessage());
            throw new ServiceException("요청 처리 시간 초과. 다시 시도해주세요.");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

### 9.6 타임아웃 설정 권장 가이드

```
┌─────────────────────────────────────────────────────────────────────┐
│                    타임아웃 설정 권장값                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  권장 순서: HTTP > (waitTime + leaseTime) > Transaction              │
│                                                                      │
│  예시 1: 일반 API                                                    │
│  ├── HTTP 타임아웃:        30초                                      │
│  ├── 락 waitTime:          5초                                       │
│  ├── 락 leaseTime:         20초                                      │
│  └── 트랜잭션 타임아웃:    15초                                      │
│                                                                      │
│  예시 2: 빠른 응답 필요                                              │
│  ├── HTTP 타임아웃:        10초                                      │
│  ├── 락 waitTime:          2초                                       │
│  ├── 락 leaseTime:         7초                                       │
│  └── 트랜잭션 타임아웃:    5초                                       │
│                                                                      │
│  예시 3: 오래 걸리는 작업                                            │
│  ├── HTTP 타임아웃:        120초 (또는 비동기 처리)                  │
│  ├── 락 waitTime:          10초                                      │
│  ├── 락 leaseTime:         -1 (Watchdog 사용)                        │
│  └── 트랜잭션 타임아웃:    90초                                      │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

**application.yml 설정 예시:**

```yaml
app:
  lock:
    wait-time: 5s
    lease-time: 20s

spring:
  transaction:
    default-timeout: 15s

server:
  servlet:
    connection-timeout: 30s
```

### 9.7 타임아웃 대응 체크리스트

```
□ 타임아웃 계층 설정
  └── HTTP > Lock(wait+lease) > Transaction

□ 락 획득 실패 처리
  ├── 적절한 재시도 로직 (지수 백오프)
  ├── 503 + Retry-After 헤더 응답
  └── 모니터링/알람 설정

□ 멱등성 보장
  ├── X-Idempotency-Key 헤더 사용
  └── 결과 캐싱 (Redis)

□ 락 만료 대응
  ├── Watchdog 사용 (leaseTime = -1)
  ├── 또는 충분한 leaseTime 설정
  └── 작업 전 락 유효성 검증

□ 트랜잭션 타임아웃 처리
  ├── @Transactional(timeout=N)
  └── 명확한 에러 메시지 반환

□ 모니터링
  ├── 락 획득 성공/실패 메트릭
  ├── 락 대기 시간 메트릭
  └── 타임아웃 발생 알람
```

---

## 10. 테스트

### 10.1 동시성 테스트

```java
@SpringBootTest
class InventoryServiceConcurrencyTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Test
    void 동시에_100개_요청해도_재고가_정확히_차감된다() throws InterruptedException {
        // given
        Long productId = 1L;
        int initialStock = 100;
        int threadCount = 100;

        inventoryRepository.save(new Inventory(productId, initialStock));

        ExecutorService executor = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // when
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    inventoryService.reserveStock(
                        new ReservationRequest("order-" + UUID.randomUUID(), productId, 1)
                    );
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // then
        Inventory result = inventoryRepository.findByProductId(productId).orElseThrow();
        assertThat(result.getQuantity()).isEqualTo(0);  // 100 - 100 = 0
    }
}
```

---

## 11. 실습 과제

### 분산 락 (Inventory Service)
1. Redisson 의존성 추가
2. 재고 서비스에 분산 락 적용
3. 동시 요청 테스트 작성
4. 락 획득 실패 시나리오 테스트
5. 락 타임아웃 시나리오 테스트
6. 타임아웃 계층 설정 및 검증

### 세마포어 (Payment Service)
7. PG사별 세마포어 초기화 구현
8. 결제 서비스에 세마포어 적용
9. 동시 결제 요청 제한 테스트 작성
10. 세마포어 획득 실패 시 적절한 응답 반환

### 세마포어 (Notification Service)
11. 채널별 세마포어 초기화 구현
12. 알림 발송에 세마포어 적용
13. 동시 발송 제한 테스트 작성

---

## 참고 자료

- [Redisson 공식 문서](https://github.com/redisson/redisson/wiki)
- [Redisson Lock 가이드](https://github.com/redisson/redisson/wiki/8.-distributed-locks-and-synchronizers)
- [Martin Kleppmann - Distributed Locks](https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html)

---

## 다음 단계

[04-optimistic-lock.md](./04-optimistic-lock.md) - 낙관적 락으로 이동
