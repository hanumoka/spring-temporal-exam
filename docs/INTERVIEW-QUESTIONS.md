# 백엔드 면접 질문지

> 이 문서는 Spring Temporal 학습 프로젝트의 학습 자료를 기반으로 작성된 백엔드 면접 질문지입니다.

---

## 목차

1. [분산 시스템 & MSA 아키텍처](#1-분산-시스템--msa-아키텍처)
2. [Saga 패턴](#2-saga-패턴)
3. [멱등성 (Idempotency)](#3-멱등성-idempotency)
4. [동시성 제어](#4-동시성-제어)
5. [Resilience4j - 장애 대응](#5-resilience4j---장애-대응)
6. [Redis & Redisson](#6-redis--redisson)
7. [메시지 큐 (Redis Stream)](#7-메시지-큐-redis-stream)
8. [Temporal](#8-temporal)
9. [Spring 관련](#9-spring-관련)
10. [데이터베이스 & ORM](#10-데이터베이스--orm)
11. [모니터링 & 로깅](#11-모니터링--로깅)
12. [인프라 & DevOps](#12-인프라--devops)

---

## 1. 분산 시스템 & MSA 아키텍처

### Q1-1. 모놀리식과 MSA의 차이점은 무엇인가요?

**모범 답안:**

| 구분 | 모놀리식 | MSA |
|------|---------|-----|
| 배포 | 전체 배포 | 서비스별 독립 배포 |
| 확장 | 전체 확장 | 서비스별 개별 확장 |
| 트랜잭션 | 단일 DB ACID 보장 | 분산 트랜잭션 필요 |
| 장애 범위 | 전체 서비스 영향 | 해당 서비스만 영향 |
| 기술 스택 | 단일 기술 | 서비스별 다양한 기술 가능 |

**추가 포인트:**
- 모놀리식에서 MSA로 전환 시 가장 큰 도전은 **분산 트랜잭션 관리**
- MSA는 "Database per Service" 원칙으로 각 서비스가 자신의 DB 소유

---

### Q1-2. MSA에서 분산 트랜잭션이 왜 문제가 되나요?

**모범 답안:**

```
시나리오: 주문 생성
1. Order Service: 주문 생성 (Order DB)
2. Inventory Service: 재고 차감 (Inventory DB)
3. Payment Service: 결제 처리 (Payment DB)

문제: Step 1, 2 성공 후 Step 3 실패
→ 이미 커밋된 Step 1, 2를 어떻게 롤백할 것인가?
```

**해결 방법:**
- **2PC (Two-Phase Commit)**: 성능 문제, 단일 장애점
- **Saga 패턴**: 각 서비스의 로컬 트랜잭션 + 보상 트랜잭션
- **Temporal**: Saga를 자동화해주는 워크플로우 엔진

---

### Q1-3. CAP 정리에 대해 설명해주세요.

**모범 답안:**

분산 시스템에서 다음 세 가지를 동시에 만족할 수 없다는 정리:

- **C (Consistency)**: 모든 노드가 같은 데이터를 볼 수 있음
- **A (Availability)**: 모든 요청이 응답을 받음
- **P (Partition Tolerance)**: 네트워크 분할 상황에서도 동작

```
실제 선택:
- CP: 일관성 우선 (금융 시스템) → MongoDB, HBase
- AP: 가용성 우선 (SNS, 쇼핑몰) → Cassandra, DynamoDB
- CA: 네트워크 분할 없음 가정 (단일 노드) → 전통적 RDBMS
```

**추가 포인트:**
- 실제로는 P를 포기할 수 없어서 CP vs AP 선택
- 우리 프로젝트: **AP 선호** (가용성 우선, 최종 일관성)

---

### Q1-4. 멀티모듈 프로젝트에서 순환 참조를 어떻게 방지하나요?

**모범 답안:**

```
올바른 의존성 방향:
common (공통) ← 다른 모듈에 의존하지 않음
    ↑
services (비즈니스) ← common만 의존
    ↑
orchestrator (조율) ← services, common 의존
```

**방지 전략:**
1. **계층 구조 명확화**: 하위 계층 → 상위 계층 방향만 의존
2. **인터페이스 추출**: 공통 인터페이스를 common에 정의
3. **ArchUnit 테스트**: 의존성 규칙 자동 검증

```java
// ArchUnit 테스트 예시
@ArchTest
static final ArchRule commonShouldNotDependOnServices =
    noClasses()
        .that().resideInAPackage("..common..")
        .should().dependOnClassesThat()
        .resideInAPackage("..service..");
```

---

## 2. Saga 패턴

### Q2-1. Saga 패턴이 무엇이고, 왜 필요한가요?

**모범 답안:**

**정의:** 분산 트랜잭션을 여러 개의 **로컬 트랜잭션**과 **보상 트랜잭션**으로 분리하여 관리하는 패턴

```
정상 흐름:
T1 (주문 생성) → T2 (재고 차감) → T3 (결제)

실패 시 보상 흐름:
T3 실패 → C2 (재고 복구) → C1 (주문 취소)
```

**필요한 이유:**
- MSA에서 각 서비스가 자신의 DB를 가짐
- 여러 DB에 걸친 ACID 트랜잭션 불가능
- 2PC는 성능 문제와 단일 장애점 발생

---

### Q2-2. Orchestration과 Choreography의 차이점은 무엇인가요?

**모범 답안:**

| 구분 | Orchestration | Choreography |
|------|---------------|--------------|
| 제어 방식 | 중앙 오케스트레이터가 조율 | 각 서비스가 이벤트 기반 자율 동작 |
| 흐름 파악 | 오케스트레이터에서 한눈에 파악 | 여러 서비스 로그 추적 필요 |
| 결합도 | 오케스트레이터에 의존 | 느슨한 결합 |
| 복잡도 | 서비스 증가해도 관리 용이 | 서비스 증가 시 이벤트 복잡 |
| 사용 비율 | 70-80% (금융, 주문 등) | 20-30% (단순 이벤트 전파) |

**Orchestration 선택 이유:**
1. 비즈니스 흐름 명확 (디버깅 용이)
2. Temporal과 자연스러운 연동
3. 실무에서 더 많이 사용 (Uber, Netflix)

---

### Q2-3. 보상 트랜잭션 설계 시 주의할 점은 무엇인가요?

**모범 답안:**

1. **멱등성 보장**
   - 보상 트랜잭션이 여러 번 호출되어도 같은 결과

2. **역순 실행**
   - 정상: T1 → T2 → T3
   - 보상: C3 → C2 → C1

3. **시맨틱 롤백 (Semantic Undo)**
   ```sql
   -- 나쁜 예: 데이터 삭제
   DELETE FROM orders WHERE id = ?

   -- 좋은 예: 상태 변경 (감사 추적 가능)
   UPDATE orders SET status = 'CANCELLED' WHERE id = ?
   ```

4. **실패 허용**
   - 보상 중 일부 실패해도 계속 진행
   - Dead Letter Queue로 수동 처리

---

### Q2-4. Saga의 격리성(Isolation) 문제는 어떻게 해결하나요?

**모범 답안:**

**문제:** Saga는 ACID의 I(Isolation)를 보장하지 않음

```
시나리오:
1. 사용자 A: 주문 시작 (재고 100 → 99)
2. 사용자 B: 재고 조회 (99개 보임) ← Dirty Read
3. 사용자 A: 결제 실패 → 재고 복구 (99 → 100)
4. 사용자 B: 잘못된 재고 정보로 의사결정
```

**해결 전략:**

1. **Semantic Lock (의미적 잠금)**
   ```sql
   -- 재고 차감 대신 "예약" 상태로
   UPDATE inventory SET reserved = reserved + 1 WHERE product_id = ?
   ```

2. **Commutative Updates (교환 가능 업데이트)**
   ```sql
   -- 절대값 대신 상대값으로 업데이트
   UPDATE inventory SET quantity = quantity - 1 WHERE product_id = ?
   ```

3. **Pessimistic View (비관적 뷰)**
   - 진행 중인 Saga가 있으면 조회 시 경고 표시

4. **Version Check**
   - 낙관적 락으로 충돌 감지

---

## 3. 멱등성 (Idempotency)

### Q3-1. 멱등성이 무엇이고, 왜 중요한가요?

**모범 답안:**

**정의:** 같은 요청을 여러 번 실행해도 결과가 동일한 성질

**중요한 이유:**
```
시나리오:
1. 클라이언트: 결제 요청
2. 서버: 결제 성공, 응답 전송 중 네트워크 끊김
3. 클라이언트: 타임아웃, 재시도
4. 서버: 또 결제 처리 → 이중 결제 발생!
```

**해결:**
```
헤더: X-Idempotency-Key: uuid-123-abc

1. 첫 요청: 처리 후 결과 저장
2. 재요청(같은 키): 저장된 결과 반환 (재처리 안함)
```

---

### Q3-2. 멱등성을 어떻게 구현하나요?

**모범 답안:**

**방법 1: 데이터베이스 유니크 제약**
```sql
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    response TEXT,
    created_at TIMESTAMP
);

-- 요청 처리 전
INSERT INTO idempotency_keys (idempotency_key) VALUES (?);
-- 중복 키면 예외 발생 → 저장된 결과 반환
```

**방법 2: Redis 캐싱**
```java
String key = "idempotency:" + idempotencyKey;
String cached = redis.get(key);
if (cached != null) {
    return deserialize(cached);
}
// 처리 후 저장
redis.setex(key, 86400, serialize(result));  // 24시간 TTL
```

**방법 3: INSERT IGNORE (MyBatis)**
```sql
INSERT IGNORE INTO payments (idempotency_key, amount, status)
VALUES (#{key}, #{amount}, 'COMPLETED')
-- affected rows = 0 이면 중복
```

---

### Q3-3. HTTP 메서드별 멱등성은 어떻게 되나요?

**모범 답안:**

| 메서드 | 멱등성 | 안전성 | 설명 |
|--------|--------|--------|------|
| GET | O | O | 조회만, 상태 변경 없음 |
| HEAD | O | O | GET과 동일, 본문 없음 |
| PUT | O | X | 전체 교체, 같은 결과 |
| DELETE | O | X | 삭제 후 재삭제해도 결과 동일 |
| POST | X | X | 매번 새 리소스 생성 |
| PATCH | X | X | 부분 수정, 결과 다를 수 있음 |

**POST의 멱등성 확보:**
```http
POST /payments
X-Idempotency-Key: uuid-123-abc
Content-Type: application/json

{"amount": 10000, "orderId": "order-456"}
```

---

## 4. 동시성 제어

### Q4-1. 단일 서버와 분산 환경에서 동시성 제어가 어떻게 다른가요?

**모범 답안:**

**단일 서버:**
```java
// synchronized로 해결 가능
public synchronized void decreaseStock(Long productId, int qty) {
    Stock stock = repository.findById(productId);
    stock.decrease(qty);
    repository.save(stock);
}
```

**분산 환경 (여러 서버):**
```
서버 A: synchronized 획득 → 재고 100 → 99 저장
서버 B: synchronized 획득 → 재고 100 → 99 저장  (동시!)
결과: 99 (2개 팔았는데 1개만 차감)

→ synchronized는 같은 JVM 내에서만 동작
→ 분산 락 필요 (Redis, ZooKeeper 등)
```

---

### Q4-2. 분산 락(RLock)과 세마포어(RSemaphore)의 차이는 무엇인가요?

**모범 답안:**

| 구분 | 분산 락 (RLock) | 세마포어 (RSemaphore) |
|------|-----------------|----------------------|
| 동시 접근 | 1개만 | N개까지 허용 |
| 용도 | 상호 배제 | 동시 접근 수 제한 |
| 소유자 | 있음 | 없음 |
| 재진입 | 가능 | 불가 |
| 예시 | 재고 차감 | API Rate Limiting |

**사용 예시:**
```java
// 분산 락: 재고 차감 (순차 처리 필수)
RLock lock = redisson.getLock("lock:stock:" + productId);
lock.tryLock(10, 30, TimeUnit.SECONDS);

// 세마포어: PG API 동시 호출 제한 (최대 10개)
RSemaphore semaphore = redisson.getSemaphore("semaphore:pg:toss");
semaphore.trySetPermits(10);
semaphore.tryAcquire(5, TimeUnit.SECONDS);
```

---

### Q4-3. 낙관적 락과 비관적 락의 차이는 무엇인가요?

**모범 답안:**

| 구분 | 낙관적 락 | 비관적 락 |
|------|----------|----------|
| 가정 | 충돌이 드물다 | 충돌이 자주 발생한다 |
| 락 시점 | 커밋 시 검증 | 조회 시 락 획득 |
| 구현 | @Version, CAS | SELECT FOR UPDATE |
| 성능 | 충돌 적을 때 좋음 | 충돌 많을 때 안전 |
| 대기 | 없음 (실패 시 재시도) | 있음 (락 해제까지 대기) |

**낙관적 락 예시 (JPA):**
```java
@Entity
public class Order {
    @Version
    private Long version;
}

// 업데이트 시 자동으로:
// UPDATE orders SET status=?, version=version+1
// WHERE id=? AND version=?
```

**비관적 락 예시:**
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT o FROM Order o WHERE o.id = :id")
Optional<Order> findByIdForUpdate(Long id);
```

---

### Q4-4. 서비스별로 다른 동시성 제어 전략을 사용하는 이유는 무엇인가요?

**모범 답안:**

```
┌─────────────────────────────────────────────────────────┐
│              서비스별 동시성 제어 전략                    │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Order Service → 낙관적 락 (@Version)                   │
│  └── 이유: 주문 상태 변경은 충돌 드묾                    │
│  └── 충돌 시: 재시도로 해결 가능                        │
│                                                         │
│  Inventory Service → 분산 락 (RLock)                    │
│  └── 이유: 동일 상품 재고는 순차 처리 필수               │
│  └── 동시 요청 시: 하나씩 처리해야 정합성 보장           │
│                                                         │
│  Payment Service → 세마포어 (RSemaphore)                │
│  └── 이유: PG사 API TPS 제한 준수                       │
│  └── 동시 10개까지만 허용, 나머지는 대기                 │
│                                                         │
│  Notification Service → 세마포어 (RSemaphore)           │
│  └── 이유: SMS/Email API 동시 발송 제한                 │
│  └── 채널별로 다른 제한 (SMS:5, Email:20, Push:50)      │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

---

### Q4-5. 분산 락 사용 시 주의할 점은 무엇인가요?

**모범 답안:**

1. **leaseTime 설정 필수**
   ```java
   // 나쁜 예: 서버 다운 시 영구 락
   lock.lock();

   // 좋은 예: 30초 후 자동 해제
   lock.tryLock(10, 30, TimeUnit.SECONDS);
   ```

2. **finally에서 unlock**
   ```java
   try {
       lock.lock();
       // 비즈니스 로직
   } finally {
       if (lock.isHeldByCurrentThread()) {
           lock.unlock();
       }
   }
   ```

3. **락 범위 최소화**
   ```java
   // 나쁜 예: 불필요하게 넓은 락 범위
   lock.lock();
   validateRequest();    // 락 불필요
   fetchExternalData();  // 락 불필요
   updateStock();        // 락 필요
   sendNotification();   // 락 불필요
   lock.unlock();

   // 좋은 예: 필요한 부분만 락
   validateRequest();
   fetchExternalData();
   lock.lock();
   updateStock();
   lock.unlock();
   sendNotification();
   ```

4. **Watchdog 활용**
   ```java
   // leaseTime = -1: Watchdog 활성화 (자동 연장)
   lock.tryLock(10, -1, TimeUnit.SECONDS);
   ```

---

## 5. Resilience4j - 장애 대응

### Q5-1. Circuit Breaker가 무엇이고, 어떻게 동작하나요?

**모범 답안:**

**정의:** 연속적인 실패가 감지되면 호출을 차단하여 연쇄 장애를 방지하는 패턴

```
상태 전이:

CLOSED (정상)
    │
    │ 실패율 50% 이상
    ▼
OPEN (차단) ─────── 모든 요청 즉시 실패 (fallback)
    │
    │ 30초 후
    ▼
HALF_OPEN ─────── 제한된 요청 허용 (테스트)
    │
    │ 성공률 충분
    ▼
CLOSED (복구)
```

**설정 예시:**
```yaml
resilience4j:
  circuitbreaker:
    instances:
      inventoryService:
        failure-rate-threshold: 50       # 실패율 50%
        wait-duration-in-open-state: 30s # OPEN 유지 시간
        permitted-calls-in-half-open: 5  # HALF_OPEN에서 테스트 호출 수
        sliding-window-size: 10          # 최근 10개 요청 기준
```

---

### Q5-2. Retry와 Circuit Breaker를 함께 사용할 때 순서는 어떻게 되나요?

**모범 답안:**

```
요청 → Retry → CircuitBreaker → 실제 호출

1. Retry가 먼저 재시도 (예: 3번)
2. 모든 재시도 실패하면 CircuitBreaker에 1번 실패로 기록
3. CircuitBreaker 임계값 도달 시 OPEN
```

**설정:**
```java
@Retry(name = "inventoryService")
@CircuitBreaker(name = "inventoryService", fallbackMethod = "fallback")
public Response callInventory() { }
```

**주의:** 순서가 바뀌면 재시도마다 CircuitBreaker에 기록되어 빠르게 OPEN됨

---

### Q5-3. Resilience4j의 4가지 핵심 모듈을 설명해주세요.

**모범 답안:**

| 모듈 | 용도 | 예시 상황 |
|------|------|----------|
| **Retry** | 일시적 실패 시 재시도 | 네트워크 순간 끊김 |
| **CircuitBreaker** | 연속 실패 시 차단 | 서비스 다운 |
| **TimeLimiter** | 타임아웃 설정 | 응답 지연 |
| **RateLimiter** | 호출 횟수 제한 | API 과부하 방지 |

```java
// 조합 사용 예시
@Retry(name = "external")
@CircuitBreaker(name = "external")
@TimeLimiter(name = "external")
@RateLimiter(name = "external")
public CompletableFuture<Response> callExternalApi() { }
```

---

### Q5-4. Resilience4j는 어느 서비스에 적용해야 하나요?

**모범 답안:**

```
호출하는 쪽(클라이언트)에 적용!

[Order Service] ← Resilience4j 적용
    │
    │ REST 호출
    ▼
[Inventory Service]

이유:
- Order Service가 Inventory의 장애에 대응해야 함
- Inventory가 다운되어도 Order가 fallback 처리 가능
```

**코드 예시:**
```java
// OrderService에서 InventoryService 호출
@Service
public class OrderService {

    @CircuitBreaker(name = "inventory", fallbackMethod = "reserveStockFallback")
    public StockResponse reserveStock(Long productId, int qty) {
        return inventoryClient.reserve(productId, qty);
    }

    public StockResponse reserveStockFallback(Long productId, int qty, Throwable t) {
        log.warn("Inventory unavailable, queuing request");
        // 큐에 저장하고 나중에 처리
        return StockResponse.pending();
    }
}
```

---

## 6. Redis & Redisson

### Q6-1. Redis의 주요 자료구조와 사용 사례를 설명해주세요.

**모범 답안:**

| 자료구조 | 명령어 | 사용 사례 |
|----------|--------|----------|
| **String** | SET, GET | 세션, 캐싱, 카운터 |
| **Hash** | HSET, HGET | 객체 저장 (사용자 정보) |
| **List** | RPUSH, LPOP | 메시지 큐, 최근 항목 |
| **Set** | SADD, SMEMBERS | 태그, 유니크 방문자 |
| **Sorted Set** | ZADD, ZRANGE | 랭킹, 우선순위 큐 |
| **Stream** | XADD, XREAD | 이벤트 소싱, 메시지 큐 |

---

### Q6-2. Redisson은 무엇이고, Lettuce/Jedis와 어떻게 다른가요?

**모범 답안:**

| 구분 | Jedis/Lettuce | Redisson |
|------|---------------|----------|
| 추상화 수준 | 저수준 (Redis 명령어) | 고수준 (Java 객체) |
| 분산 락 | 직접 구현 필요 | RLock 내장 |
| 분산 컬렉션 | 없음 | RMap, RSet 등 제공 |
| Watchdog | 없음 | 자동 락 갱신 |

**비유:** Jedis는 JDBC, Redisson은 JPA

```java
// Lettuce: 저수준
redisTemplate.opsForValue().set("key", "value");

// Redisson: 고수준
RMap<String, User> map = redisson.getMap("users");
map.put("user1", new User("Alice"));
```

---

### Q6-3. Redisson의 Watchdog은 무엇인가요?

**모범 답안:**

**정의:** 락 보유 중 자동으로 TTL을 갱신하여 락 만료를 방지하는 메커니즘

```
문제 상황:
1. 서버 A: 락 획득 (TTL 30초)
2. 작업이 예상보다 오래 걸림 (40초)
3. 30초 후 락 만료 → 서버 B가 락 획득
4. 서버 A, B 둘 다 작업 중 → 데이터 불일치!

Watchdog 해결:
1. 서버 A: 락 획득 (Watchdog 활성화)
2. 10초마다 자동으로 TTL 갱신
3. 작업 완료 시까지 락 유지
```

**활성화 방법:**
```java
// leaseTime = -1 (또는 생략)
lock.tryLock(waitTime, -1, TimeUnit.SECONDS);  // Watchdog ON
lock.tryLock(waitTime, 30, TimeUnit.SECONDS);  // Watchdog OFF (30초 후 만료)
```

---

## 7. 메시지 큐 (Redis Stream)

### Q7-1. Redis Stream이 무엇이고, 언제 사용하나요?

**모범 답안:**

**정의:** Redis 5.0+에서 제공하는 로그 기반 메시지 큐

```
특징:
- 영속성: 메시지가 Redis에 저장됨
- Consumer Group: 여러 소비자가 메시지 분배
- ACK: 메시지 처리 확인
- 재처리: 실패한 메시지 다시 처리 가능
```

**사용 사례:**
- 이벤트 소싱
- 비동기 작업 처리
- 서비스 간 느슨한 결합

**Kafka 대비 장점:**
- 설정 간단
- 별도 인프라 불필요 (Redis에 이미 포함)
- 학습 환경에서 빠른 시작

---

### Q7-2. Consumer Group과 Pending List는 무엇인가요?

**모범 답안:**

**Consumer Group:**
```
Stream: orders
    │
    ├── Consumer Group: order-processors
    │       ├── Consumer 1 (서버 A)
    │       ├── Consumer 2 (서버 B)
    │       └── Consumer 3 (서버 C)
    │
    │   메시지 자동 분배 (로드 밸런싱)
```

**Pending List:**
```
Consumer가 메시지를 읽었지만 ACK하지 않은 목록

용도:
1. 처리 실패한 메시지 추적
2. Consumer 장애 시 다른 Consumer가 인계
3. 재처리 로직 구현

명령어:
XPENDING stream group              # Pending 목록 조회
XCLAIM stream group consumer ...   # 다른 Consumer에게 소유권 이전
XACK stream group message-id       # 처리 완료 확인
```

---

### Q7-3. Outbox 패턴이 무엇인가요?

**모범 답안:**

**문제:**
```
트랜잭션 1: DB 저장 성공
트랜잭션 2: 메시지 발행 실패
→ 데이터와 이벤트 불일치!
```

**Outbox 패턴 해결:**
```
1. DB와 Outbox 테이블에 같은 트랜잭션으로 저장
   BEGIN TRANSACTION
     INSERT INTO orders ...
     INSERT INTO outbox (event_type, payload) ...
   COMMIT

2. 별도 프로세스가 Outbox 테이블 폴링
   SELECT * FROM outbox WHERE published = false

3. 메시지 발행 후 상태 업데이트
   UPDATE outbox SET published = true WHERE id = ?
```

**장점:**
- DB 트랜잭션으로 원자성 보장
- 메시지 발행 실패해도 재시도 가능
- 이벤트 순서 보장

---

## 8. Temporal

### Q8-1. Temporal이 무엇이고, 어떤 문제를 해결하나요?

**모범 답안:**

**정의:** 내구성 있는 실행(Durable Execution)을 제공하는 워크플로우 엔진

**해결하는 문제:**
```
기존 Saga 구현의 어려움:
1. Saga 상태 관리 복잡 (어디까지 실행됐지?)
2. 보상 트랜잭션 구현 어려움
3. 재시도 로직 산재
4. 타임아웃 처리 복잡
5. 장애 복구 어려움 (서버 다운 시)

Temporal 해결:
1. Event Sourcing으로 자동 상태 관리
2. Saga 패턴 내장 지원
3. Retry Policy 선언적 설정
4. Activity Timeout 내장
5. 서버 다운 후 자동 재개
```

---

### Q8-2. Workflow와 Activity의 차이는 무엇인가요?

**모범 답안:**

| 구분 | Workflow | Activity |
|------|----------|----------|
| 역할 | 비즈니스 흐름 정의 | 실제 작업 수행 |
| 특징 | 결정론적 (Deterministic) | 비결정론적 가능 |
| 예시 | if/else, 상태 전이 | API 호출, DB 저장 |
| 재시도 | 자동 재개 | Retry Policy 설정 |

```java
// Workflow: 흐름 정의
@WorkflowInterface
public interface OrderWorkflow {
    @WorkflowMethod
    OrderResult executeOrder(OrderRequest request);
}

@Override
public OrderResult executeOrder(OrderRequest request) {
    // 결정론적 코드만 (Math.random() X)
    Long orderId = activities.createOrder(request);
    activities.reserveStock(orderId, request.getProductId());
    activities.processPayment(orderId, request.getAmount());
    return new OrderResult(orderId, "COMPLETED");
}

// Activity: 실제 작업
@ActivityInterface
public interface PaymentActivity {
    @ActivityMethod
    PaymentResponse processPayment(Long orderId, BigDecimal amount);
}
```

---

### Q8-3. Temporal의 내구성 있는 실행(Durable Execution)이란?

**모범 답안:**

**정의:** 워크플로우 실행 중 장애가 발생해도 자동으로 재개되는 특성

```
시나리오:
1. Step 1 완료 (주문 생성)
2. Step 2 진행 중 (재고 차감) → 서버 다운!
3. 서버 재시작
4. Step 2부터 자동 재개 ← 내구성

원리:
- Event Sourcing: 모든 이벤트를 Temporal Server에 기록
- Replay: 서버 재시작 시 이벤트 재생으로 상태 복원
```

**기존 방식과 비교:**
```java
// 기존: 서버 다운 시 처음부터 다시
public void processOrder() {
    createOrder();      // 완료
    reserveStock();     // 진행 중 → 서버 다운 → 어디까지 했지?
    processPayment();
}

// Temporal: 자동 재개
@WorkflowMethod
public void processOrder() {
    activities.createOrder();      // 이미 완료된 건 스킵
    activities.reserveStock();     // 여기서부터 재개
    activities.processPayment();
}
```

---

### Q8-4. Temporal Worker는 무엇인가요?

**모범 답안:**

**정의:** Workflow와 Activity를 실행하는 프로세스

```
┌─────────────────────────────────────────────────────────┐
│                   Temporal Server                        │
│                                                          │
│   Task Queue: "order-task-queue"                         │
│   ┌─────────────────────────────────────────┐           │
│   │ Workflow Task 1, Activity Task 2, ...    │           │
│   └─────────────────────────────────────────┘           │
└─────────────────────────────────────────────────────────┘
              ▲              ▲              ▲
              │              │              │
       ┌──────┴──────┐ ┌─────┴─────┐ ┌──────┴──────┐
       │  Worker 1   │ │  Worker 2  │ │  Worker 3   │
       │  (서버 A)   │ │  (서버 B)  │ │  (서버 C)   │
       └─────────────┘ └────────────┘ └─────────────┘
```

**등록 방법:**
```java
WorkerFactory factory = WorkerFactory.newInstance(client);
Worker worker = factory.newWorker("order-task-queue");
worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);
worker.registerActivitiesImplementations(new PaymentActivityImpl());
factory.start();
```

---

## 9. Spring 관련

### Q9-1. Spring Profiles를 어떻게 활용하나요?

**모범 답안:**

**파일 구조:**
```
application.yml          # 공통 설정
application-local.yml    # 로컬 개발
application-dev.yml      # 개발 서버
application-prod.yml     # 운영 서버
```

**로드 순서:**
```
1. application.yml 로드
2. application-{active}.yml 로드 (덮어씀)

예: SPRING_PROFILES_ACTIVE=prod
→ application.yml + application-prod.yml
```

**환경별 설정 예시:**
```yaml
# application-local.yml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/order_db
  redis:
    host: localhost

# application-prod.yml
spring:
  datasource:
    url: jdbc:mysql://prod-db.example.com:3306/order_db
  redis:
    host: prod-redis.example.com
```

---

### Q9-2. @Transactional vs TransactionTemplate의 차이는?

**모범 답안:**

| 구분 | @Transactional | TransactionTemplate |
|------|----------------|---------------------|
| 방식 | 선언적 | 프로그래밍 방식 |
| 경계 | 메서드 단위 | 코드 블록 단위 |
| 유연성 | 제한적 | 높음 |
| 가독성 | 좋음 | 복잡할 수 있음 |

**TransactionTemplate 사용 시기:**
```java
// 여러 트랜잭션을 세밀하게 제어
public void complexOperation() {
    // 트랜잭션 1
    transactionTemplate.executeWithoutResult(status -> {
        orderRepository.save(order);
    });

    // 외부 API 호출 (트랜잭션 밖)
    externalApiClient.notify();

    // 트랜잭션 2
    transactionTemplate.executeWithoutResult(status -> {
        inventoryRepository.update(stock);
    });
}
```

---

### Q9-3. Bean Validation의 주요 어노테이션을 설명해주세요.

**모범 답안:**

| 어노테이션 | 용도 | 예시 |
|-----------|------|------|
| @NotNull | null 불가 | 필수 필드 |
| @NotBlank | null, 공백 불가 | 문자열 필수 |
| @NotEmpty | null, 빈 컬렉션 불가 | 리스트 필수 |
| @Size | 길이/크기 제한 | @Size(min=2, max=10) |
| @Min, @Max | 숫자 범위 | @Min(1) @Max(100) |
| @Email | 이메일 형식 | user@example.com |
| @Pattern | 정규식 | @Pattern(regexp="^[A-Z]+$") |
| @Valid | 중첩 객체 검증 | @Valid AddressDto address |

```java
public record OrderRequest(
    @NotNull(message = "상품 ID는 필수입니다")
    Long productId,

    @Positive(message = "수량은 1 이상이어야 합니다")
    Integer quantity,

    @Valid
    PaymentInfo paymentInfo
) {}
```

---

## 10. 데이터베이스 & ORM

### Q10-1. Flyway의 역할과 주의사항은 무엇인가요?

**모범 답안:**

**역할:**
- DB 스키마 버전 관리
- 팀원 간 스키마 동기화
- 배포 시 자동 마이그레이션

**파일 네이밍:**
```
V1__create_orders_table.sql      # 버전 1
V2__add_customer_id.sql          # 버전 2
V3__create_payments_table.sql    # 버전 3
R__refresh_views.sql             # 반복 실행 (R prefix)
```

**주의사항:**
```
1. 이미 실행된 스크립트 수정 금지
   → checksum 불일치로 에러 발생
   → 새 버전 파일로 수정사항 적용

2. 롤백은 새 마이그레이션으로
   V4__undo_v3.sql  # V3 변경사항을 되돌리는 새 스크립트
```

---

### Q10-2. JPA와 MyBatis를 함께 사용하는 이유는?

**모범 답안:**

**JPA 장점:**
- 생산성 (자동 쿼리 생성)
- ORM 추상화
- 간단한 CRUD

**MyBatis 장점:**
- SQL 직접 제어
- 복잡한 쿼리 최적화
- 멱등성 구현 용이

```sql
-- MyBatis로 멱등성 구현
INSERT IGNORE INTO payments (idempotency_key, amount)
VALUES (#{key}, #{amount})

-- MyBatis로 낙관적 락
UPDATE orders
SET status = #{status}, version = version + 1
WHERE id = #{id} AND version = #{version}
```

**결론:** "JPA로 기본 CRUD, MyBatis로 특수 케이스 처리"

---

### Q10-3. N+1 문제가 무엇이고, 어떻게 해결하나요?

**모범 답안:**

**문제:**
```java
List<Order> orders = orderRepository.findAll();  // 1번 쿼리
for (Order order : orders) {
    order.getItems().size();  // N번 쿼리 (지연 로딩)
}
// 총 N+1번 쿼리 실행
```

**해결 방법:**

1. **Fetch Join (JPQL)**
   ```java
   @Query("SELECT o FROM Order o JOIN FETCH o.items")
   List<Order> findAllWithItems();
   ```

2. **EntityGraph**
   ```java
   @EntityGraph(attributePaths = {"items"})
   List<Order> findAll();
   ```

3. **Batch Size**
   ```yaml
   spring:
     jpa:
       properties:
         hibernate:
           default_batch_fetch_size: 100
   ```

---

## 11. 모니터링 & 로깅

### Q11-1. MDC(Mapped Diagnostic Context)는 무엇이고, 왜 사용하나요?

**모범 답안:**

**정의:** 스레드 로컬 컨텍스트에 진단 정보를 저장하여 로그에 자동 포함

**필요한 이유:**
```
분산 시스템에서 요청 추적:
Client → Order Service → Inventory Service → Payment Service

MDC 없이:
[Order] Processing order
[Inventory] Reserving stock
[Payment] Processing payment
→ 어떤 요청인지 알 수 없음

MDC 사용:
[traceId=abc123] [Order] Processing order
[traceId=abc123] [Inventory] Reserving stock
[traceId=abc123] [Payment] Processing payment
→ 같은 traceId로 요청 흐름 추적 가능
```

**사용 예시:**
```java
@Component
public class MdcFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
        try {
            MDC.put("traceId", UUID.randomUUID().toString());
            MDC.put("userId", getUserId(req));
            chain.doFilter(req, res);
        } finally {
            MDC.clear();
        }
    }
}
```

---

### Q11-2. Prometheus와 Grafana의 역할은 무엇인가요?

**모범 답안:**

| 도구 | 역할 | 데이터 |
|------|------|--------|
| **Prometheus** | 메트릭 수집 & 저장 | 시계열 데이터 |
| **Grafana** | 시각화 & 대시보드 | 그래프, 알림 |

```
Spring Boot App
    │
    │ /actuator/prometheus (메트릭 노출)
    ▼
Prometheus ─────────────────────────┐
    │ (15초마다 스크랩)             │
    │                              │
    ▼                              ▼
시계열 DB ────────────────────→ Grafana
                                   │
                               대시보드
                               알림 설정
```

**주요 메트릭:**
- `http_server_requests_seconds`: HTTP 요청 지연 시간
- `jvm_memory_used_bytes`: JVM 메모리 사용량
- `process_cpu_usage`: CPU 사용률

---

### Q11-3. 분산 추적(Distributed Tracing)이 무엇인가요?

**모범 답안:**

**정의:** 여러 서비스를 거치는 요청의 전체 흐름을 추적하는 기술

```
Client ──→ Gateway ──→ Order ──→ Inventory ──→ Payment

Trace ID: abc-123
├── Span 1: Gateway (10ms)
├── Span 2: Order Service (50ms)
│   └── Span 3: Inventory Call (20ms)
│   └── Span 4: Payment Call (30ms)
└── Total: 110ms
```

**도구:**
- **Zipkin**: 경량, 간단한 설정
- **Jaeger**: Uber 개발, Kubernetes 친화적
- **OpenTelemetry**: 표준화된 수집 라이브러리

**Spring Boot 설정:**
```yaml
management:
  tracing:
    sampling:
      probability: 1.0  # 100% 샘플링 (운영에서는 0.1 등)
  zipkin:
    tracing:
      endpoint: http://localhost:9411/api/v2/spans
```

---

## 12. 인프라 & DevOps

### Q12-1. Docker Compose의 주요 개념을 설명해주세요.

**모범 답안:**

| 개념 | 설명 | 예시 |
|------|------|------|
| **Service** | 컨테이너 정의 | mysql, redis, app |
| **Volume** | 데이터 영속화 | mysql-data:/var/lib/mysql |
| **Network** | 컨테이너 간 통신 | backend (브릿지 네트워크) |
| **depends_on** | 시작 순서 | app depends_on mysql |
| **healthcheck** | 헬스 체크 | mysqladmin ping |

```yaml
services:
  mysql:
    image: mysql:8.0
    volumes:
      - mysql-data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping"]
      interval: 10s
      timeout: 5s
      retries: 3

  app:
    build: .
    depends_on:
      mysql:
        condition: service_healthy

volumes:
  mysql-data:

networks:
  default:
    name: backend
```

---

### Q12-2. 컨테이너 오케스트레이션이 필요한 이유는?

**모범 답안:**

**Docker Compose의 한계:**
- 단일 호스트에서만 동작
- 자동 확장 불가
- 장애 복구 수동

**Kubernetes가 해결하는 문제:**
```
1. 자동 확장 (HPA)
   - CPU 80% 이상 → Pod 자동 증가

2. 자가 치유 (Self-healing)
   - Pod 죽으면 자동 재시작

3. 롤링 업데이트
   - 무중단 배포

4. 서비스 디스커버리
   - DNS 기반 서비스 찾기

5. 시크릿 관리
   - ConfigMap, Secret
```

**우리 프로젝트가 K8s를 사용하지 않는 이유:**
> "학습 목표에 집중하기 위해 Docker Compose로 충분한 로컬 환경 구성"

---

### Q12-3. 환경 변수 관리 전략은 어떻게 되나요?

**모범 답안:**

| 환경 | 전략 | 도구 |
|------|------|------|
| **로컬** | .env 파일 | Docker Compose |
| **CI/CD** | 파이프라인 시크릿 | GitHub Secrets |
| **Kubernetes** | ConfigMap/Secret | kubectl |
| **클라우드** | 관리형 서비스 | AWS Secrets Manager |

**12 Factor App 원칙:**
> "설정은 코드에서 분리하고 환경 변수로 주입"

```yaml
# docker-compose.yml
services:
  app:
    environment:
      - SPRING_PROFILES_ACTIVE=${PROFILE:-local}
      - DB_HOST=${DB_HOST:-localhost}
      - DB_PASSWORD=${DB_PASSWORD}  # .env에서 로드
```

---

## 부록: 면접 답변 템플릿

### 기술 개념 설명 시

```
1. 정의: "~란 ~를 의미합니다"
2. 필요성: "이 문제를 해결하기 위해 사용합니다"
3. 구현: "구체적인 방법은 ~입니다"
4. 장단점: "장점은 ~이고, 단점은 ~입니다"
5. 실무: "실제로는 ~에서 사용됩니다"
```

### 프로젝트 설명 시

```
1. 배경: "왜 이 프로젝트를 했는지"
2. 목표: "무엇을 달성하려 했는지"
3. 기술 선택: "왜 이 기술을 선택했는지"
4. 어려움: "어떤 문제가 있었는지"
5. 해결: "어떻게 해결했는지"
6. 결과: "무엇을 배웠는지"
```

---

## 참고 자료

- [프로젝트 아키텍처 결정](./architecture/DECISIONS.md)
- [기술 스택 검증](./architecture/TECH-STACK.md)
- [Phase별 학습 문서](./study/)
- [Temporal 공식 문서](https://docs.temporal.io/)
- [Resilience4j 가이드](https://resilience4j.readme.io/)
- [Redisson Wiki](https://github.com/redisson/redisson/wiki)
