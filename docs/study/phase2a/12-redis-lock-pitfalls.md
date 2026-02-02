# Redis 분산 락 10가지 함정

## 개요

### What (무엇인가)
Redis 분산 락을 프로덕션에서 사용할 때 발생할 수 있는 10가지 핵심 함정과 그 해결책을 다룹니다.

### Why (왜 중요한가)

```
분산 락 실패 = 데이터 정합성 파괴

예시:
├── 재고 100개
├── 동시 주문 2건 (각 60개)
├── 분산 락 실패
└── 결과: 재고 -20개 (불가능한 상태)
```

---

## 함정 1: SETNX + EXPIRE 비원자성

### 문제

```
┌─────────────────────────────────────────────────────────────────────┐
│                    비원자적 락 획득의 위험                           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  // 잘못된 구현                                                      │
│  if (redis.setnx("lock:inventory:1", "owner-1")) {  // 락 획득      │
│      // ⚡ 여기서 프로세스 크래시!                                   │
│      redis.expire("lock:inventory:1", 30);          // 실행 안 됨   │
│  }                                                                   │
│                                                                      │
│  결과: 락은 획득했지만 TTL이 없음 → 영구 락 (Deadlock)              │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 해결책

```java
// 올바른 구현 - 원자적 명령어
Boolean acquired = redisTemplate.opsForValue()
    .setIfAbsent("lock:inventory:1", "owner-1", Duration.ofSeconds(30));

// Redis 명령어
// SET lock:inventory:1 owner-1 NX EX 30
```

---

## 함정 2: Master-Slave 복제 문제

### 문제

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Master-Slave 복제 지연                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  시간  │  Master              │  Slave                │  Thread B   │
│  ──────┼──────────────────────┼───────────────────────┼──────────── │
│  T1    │  Thread A 락 획득    │  (복제 전)            │             │
│  T2    │  ⚡ Master 다운!     │                       │             │
│  T3    │                      │  Master 승격          │             │
│  T4    │                      │  (락 정보 없음)       │  락 획득!   │
│  ──────┼──────────────────────┼───────────────────────┼──────────── │
│                                                                      │
│  결과: Thread A와 B 모두 락을 가졌다고 생각                          │
│        → 동시 접근 발생!                                             │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 해결책: Redlock 알고리즘

```java
// Redisson Redlock 사용
RLock lock1 = redisson1.getLock("lock:inventory:1");
RLock lock2 = redisson2.getLock("lock:inventory:1");
RLock lock3 = redisson3.getLock("lock:inventory:1");

RedissonRedLock redLock = new RedissonRedLock(lock1, lock2, lock3);

try {
    // N/2 + 1 노드에서 락 획득해야 성공 (3개 중 2개)
    if (redLock.tryLock(5, 30, TimeUnit.SECONDS)) {
        // 안전하게 처리
    }
} finally {
    redLock.unlock();
}
```

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Redlock 알고리즘                                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Redis 1 ──┐                                                        │
│            │                                                        │
│  Redis 2 ──┼──→ N/2 + 1 노드에서 락 획득 시 성공                    │
│            │                                                        │
│  Redis 3 ──┘                                                        │
│                                                                      │
│  예: 5개 노드 중 3개 이상에서 락 획득 필요                          │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 함정 3: 락 조기 만료

### 문제

```
┌─────────────────────────────────────────────────────────────────────┐
│                    락 TTL < 작업 시간                                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  시간  │  Thread A                    │  Thread B                   │
│  ──────┼──────────────────────────────┼──────────────────────────── │
│  T1    │  락 획득 (TTL=10초)          │                             │
│  T2    │  작업 처리 중... (15초 소요)  │                             │
│  T11   │  (락 만료!)                  │  락 획득 성공!              │
│  T12   │  작업 계속 중...              │  작업 시작...               │
│  T15   │  작업 완료, 락 해제 시도      │  작업 중... ⚡             │
│  ──────┼──────────────────────────────┼──────────────────────────── │
│                                                                      │
│  문제:                                                               │
│  ├── Thread A 작업 중 락 만료                                       │
│  ├── Thread B 락 획득 후 작업 시작                                  │
│  └── 두 스레드 동시 작업 → 데이터 불일치                            │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 해결책: Watch Dog (Redisson)

```java
// Redisson Watch Dog 사용
RLock lock = redissonClient.getLock("lock:inventory:1");

try {
    // leaseTime 미지정 → Watch Dog 활성화
    // 30초 TTL로 시작, 10초마다 자동 연장
    lock.lock();

    // 긴 작업 수행 (Watch Dog이 자동 연장)
    processLongRunningTask();

} finally {
    lock.unlock();
}
```

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Watch Dog 동작                                    │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  락 획득 (TTL=30초)                                                  │
│       │                                                              │
│       ▼                                                              │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  10초  │  10초  │  10초  │  10초  │  작업 완료              │    │
│  │   ↓   │   ↓   │   ↓   │   ↓   │                            │    │
│  │ 연장  │ 연장  │ 연장  │ 연장  │  unlock()                  │    │
│  │ 30초  │ 30초  │ 30초  │ 30초  │                            │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  Watch Dog: lockWatchdogTimeout/3 마다 갱신 (기본 10초)              │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 함정 4: 트랜잭션과 락 순서 문제

### 문제

```java
@Transactional
public void processOrder(Long productId, int quantity) {
    RLock lock = redissonClient.getLock("lock:inventory:" + productId);

    try {
        lock.lock();

        // 재고 차감
        Inventory inv = inventoryRepository.findById(productId).get();
        inv.decrease(quantity);
        inventoryRepository.save(inv);

    } finally {
        lock.unlock();  // ⚡ 락 해제
    }
    // 트랜잭션 커밋은 메서드 종료 후!
}
```

```
┌─────────────────────────────────────────────────────────────────────┐
│                    트랜잭션 < 락 범위 문제                           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  시간  │  Thread A                    │  Thread B                   │
│  ──────┼──────────────────────────────┼──────────────────────────── │
│  T1    │  @Transactional 시작         │                             │
│  T2    │  락 획득                      │                             │
│  T3    │  재고 100 → 90 변경          │                             │
│  T4    │  락 해제                      │  락 획득                    │
│  T5    │  (커밋 전)                   │  재고 읽기: 100 (커밋 전!)  │
│  T6    │  커밋: 90 저장               │  재고 90 → 80 변경          │
│  T7    │                              │  커밋: 80 저장              │
│  ──────┼──────────────────────────────┼──────────────────────────── │
│                                                                      │
│  결과: 정상 (80)                                                     │
│  BUT: Thread B가 커밋 전 값을 읽을 수 있는 타이밍 존재               │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 해결책

```java
// 방법 1: 트랜잭션 범위 밖에서 락 관리
public void processOrderSafe(Long productId, int quantity) {
    RLock lock = redissonClient.getLock("lock:inventory:" + productId);

    try {
        lock.lock();

        // 트랜잭션 내부에서 처리
        doProcessOrder(productId, quantity);

        // 트랜잭션 커밋 후 락 해제
    } finally {
        lock.unlock();
    }
}

@Transactional
protected void doProcessOrder(Long productId, int quantity) {
    Inventory inv = inventoryRepository.findById(productId).get();
    inv.decrease(quantity);
    inventoryRepository.save(inv);
}

// 방법 2: TransactionSynchronization 사용
@Transactional
public void processOrderWithSync(Long productId, int quantity) {
    RLock lock = redissonClient.getLock("lock:inventory:" + productId);
    lock.lock();

    // 트랜잭션 커밋 후 락 해제
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                lock.unlock();
            }
        }
    );

    Inventory inv = inventoryRepository.findById(productId).get();
    inv.decrease(quantity);
}
```

---

## 함정 5: Clock Drift (시계 드리프트)

### 문제

```
┌─────────────────────────────────────────────────────────────────────┐
│                    서버 간 시간 차이                                 │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Server A (시간: 10:00:00)                                          │
│  Server B (시간: 10:00:05) ← 5초 빠름                               │
│  Redis   (시간: 10:00:02)                                           │
│                                                                      │
│  문제:                                                               │
│  ├── Server A가 30초 TTL로 락 획득 (10:00:30 만료 예상)             │
│  ├── Server B는 10:00:35가 만료 시점이라고 계산                      │
│  ├── 실제로는 Redis 기준 10:00:32에 만료                            │
│  └── 예상과 실제 만료 시점 불일치                                   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 해결책

```bash
# 1. NTP 동기화 필수
sudo ntpd -qg
sudo systemctl enable ntpd

# 2. 충분한 TTL 마진
# 실제 작업 시간 + 클럭 드리프트 허용치 + 네트워크 지연
```

```java
// 락 TTL 설정 시 마진 고려
private static final int LOCK_TTL_SECONDS = 30;
private static final int CLOCK_DRIFT_MARGIN = 5;  // 5초 마진
private static final int EFFECTIVE_TTL = LOCK_TTL_SECONDS - CLOCK_DRIFT_MARGIN;
```

---

## 함정 6: 재진입 락 미지원 (기본 구현)

### 문제

```java
// 기본 Redis 락은 재진입 미지원
public void outerMethod() {
    acquireLock("lock:resource");
    try {
        innerMethod();  // ⚡ Deadlock!
    } finally {
        releaseLock("lock:resource");
    }
}

public void innerMethod() {
    acquireLock("lock:resource");  // 같은 락 재획득 시도 → 블로킹
    // ...
}
```

### 해결책: Redisson RLock

```java
// Redisson RLock은 재진입 지원
RLock lock = redissonClient.getLock("lock:resource");

public void outerMethod() {
    lock.lock();  // 락 카운터: 1
    try {
        innerMethod();
    } finally {
        lock.unlock();  // 락 카운터: 0 → 해제
    }
}

public void innerMethod() {
    lock.lock();  // 같은 스레드 → 락 카운터: 2
    try {
        // 정상 처리
    } finally {
        lock.unlock();  // 락 카운터: 1
    }
}
```

---

## 함정 7: 락 소유자 미검증 해제

### 문제

```
┌─────────────────────────────────────────────────────────────────────┐
│                    소유자 미검증 락 해제                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  시간  │  Thread A                    │  Thread B                   │
│  ──────┼──────────────────────────────┼──────────────────────────── │
│  T1    │  락 획득 (owner-A)           │                             │
│  T2    │  작업 중... (오래 걸림)      │                             │
│  T3    │  (락 만료!)                  │  락 획득 (owner-B)          │
│  T4    │  작업 완료                    │  작업 중...                 │
│  T5    │  DEL lock:resource ⚡        │  (락 삭제됨!)               │
│  T6    │                              │  ⚡ 락 없이 작업 계속       │
│  ──────┼──────────────────────────────┼──────────────────────────── │
│                                                                      │
│  문제: Thread A가 Thread B의 락을 삭제함                             │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 해결책: Lua Script로 소유자 검증

```java
// Redisson 내부에서 자동 처리됨
// 직접 구현 시:

private static final String UNLOCK_SCRIPT = """
    if redis.call('get', KEYS[1]) == ARGV[1] then
        return redis.call('del', KEYS[1])
    else
        return 0
    end
    """;

public boolean releaseLockSafe(String lockKey, String ownerId) {
    Long result = redisTemplate.execute(
        new DefaultRedisScript<>(UNLOCK_SCRIPT, Long.class),
        List.of(lockKey),
        ownerId
    );
    return result != null && result == 1;
}
```

---

## 함정 8: 락 획득 대기 무한 블로킹

### 문제

```java
// 위험: 무한 대기
lock.lock();  // 락 획득까지 영원히 대기
```

### 해결책

```java
// 타임아웃 설정
boolean acquired = lock.tryLock(5, 30, TimeUnit.SECONDS);
//                              ↑ waitTime: 최대 5초 대기
//                                  ↑ leaseTime: 락 보유 30초

if (!acquired) {
    throw new LockAcquisitionException("락 획득 실패");
}
```

---

## 함정 9: 네트워크 파티션

### 문제

```
┌─────────────────────────────────────────────────────────────────────┐
│                    네트워크 분리 상황                                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [Application] ──X── [Redis]                                        │
│                 ↑                                                    │
│           네트워크 단절                                              │
│                                                                      │
│  문제:                                                               │
│  ├── Application은 락을 가졌다고 생각                               │
│  ├── Redis는 TTL 만료로 락 삭제                                     │
│  ├── 다른 Application이 락 획득                                     │
│  └── 네트워크 복구 후 충돌                                          │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 해결책: Fencing Token

```java
// Fencing Token: 단조 증가하는 토큰으로 순서 보장
public class FencingLock {

    private final RedisTemplate<String, String> redisTemplate;
    private final AtomicLong tokenGenerator = new AtomicLong(0);

    public FencingToken acquireLock(String lockKey) {
        long token = tokenGenerator.incrementAndGet();

        Boolean acquired = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, String.valueOf(token), Duration.ofSeconds(30));

        if (Boolean.TRUE.equals(acquired)) {
            return new FencingToken(token);
        }
        return null;
    }

    public void writeWithFencing(String dataKey, String value, long fencingToken) {
        // 저장 전 토큰 검증
        String currentToken = redisTemplate.opsForValue().get("token:" + dataKey);

        if (currentToken != null && Long.parseLong(currentToken) >= fencingToken) {
            throw new StaleTokenException("더 최신 토큰이 이미 쓰기 완료");
        }

        // 토큰과 함께 저장
        redisTemplate.opsForValue().set("token:" + dataKey, String.valueOf(fencingToken));
        redisTemplate.opsForValue().set(dataKey, value);
    }
}
```

---

## 함정 10: 과도한 락 경합

### 문제

```java
// 모든 상품에 대해 하나의 락
RLock lock = redissonClient.getLock("lock:inventory");  // 전체 재고 락

// 상품 A 주문할 때 상품 B 주문도 대기
```

### 해결책: 세분화된 락

```java
// 상품별 개별 락
RLock lock = redissonClient.getLock("lock:inventory:" + productId);

// 필요 시 다중 락
RLock lock1 = redissonClient.getLock("lock:inventory:1");
RLock lock2 = redissonClient.getLock("lock:inventory:2");
RedissonMultiLock multiLock = new RedissonMultiLock(lock1, lock2);
```

---

## 프로덕션 체크리스트

```
┌─────────────────────────────────────────────────────────────────────┐
│                    분산 락 프로덕션 체크리스트                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  □ 원자적 락 획득 (SET NX EX)                                       │
│  □ 락 해제 시 소유자 검증 (Lua Script)                              │
│  □ 적절한 TTL 설정 (작업 시간 + 마진)                               │
│  □ Watch Dog 또는 수동 연장 구현                                    │
│  □ 락 획득 타임아웃 설정                                            │
│  □ 트랜잭션과 락 순서 검토                                          │
│  □ NTP 시간 동기화                                                  │
│  □ 고가용성 필요 시 Redlock 검토                                    │
│  □ 락 세분화 (리소스별 개별 락)                                     │
│  □ 모니터링 및 알림 설정                                            │
│    ├── 락 획득 실패율                                               │
│    ├── 락 대기 시간                                                 │
│    └── 락 보유 시간                                                 │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 관련 문서

- [D021 Redis 분산 락 심화](../../architecture/DECISIONS.md#d021-redis-분산-락-심화-전략)
- [04-distributed-lock.md](./04-distributed-lock.md)
- [03-redisson.md](../phase2b/03-redisson.md)
