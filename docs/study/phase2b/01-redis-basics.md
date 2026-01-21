# Redis 기초

## 이 문서에서 배우는 것

- Redis의 개념과 특징
- 기본 자료구조 (String, Hash, List, Set, Sorted Set)
- Spring Boot에서 Redis 사용
- 캐싱 적용

---

## 1. Redis란?

### 정의

**Redis(Remote Dictionary Server)**는 인메모리 키-값 데이터 저장소입니다.

```
┌─────────────────────────────────────────────────────────────┐
│                        Redis                                 │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │              메모리 (RAM)                           │    │
│  │  key1 → value1                                      │    │
│  │  key2 → value2                                      │    │
│  │  key3 → [list, of, values]                         │    │
│  │  key4 → {hash: data}                               │    │
│  └─────────────────────────────────────────────────────┘    │
│                    ↓ (선택적 영속화)                        │
│  ┌─────────────────────────────────────────────────────┐    │
│  │              디스크 (RDB/AOF)                        │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

### 주요 특징

| 특징 | 설명 |
|------|------|
| **인메모리** | 데이터를 메모리에 저장하여 빠른 속도 |
| **키-값 구조** | 단순한 키로 데이터 접근 |
| **다양한 자료구조** | String, List, Set, Hash, Sorted Set 등 |
| **싱글 스레드** | 원자적 연산 보장 |
| **영속성** | RDB/AOF로 디스크 저장 가능 |
| **복제** | Master-Replica 구조 지원 |

### 사용 사례

```
1. 캐싱 (Caching)
   DB 조회 결과 캐싱 → 응답 속도 향상

2. 세션 저장소 (Session Store)
   사용자 세션 정보 저장 → 서버 확장 용이

3. 분산 락 (Distributed Lock)
   여러 서버 간 동기화 (Redisson)

4. 메시지 큐 (Message Queue)
   Redis Stream, Pub/Sub

5. 순위표 (Leaderboard)
   Sorted Set으로 실시간 랭킹
```

---

## 2. 기본 자료구조

### 2.1 String

가장 기본적인 타입. 최대 512MB.

```bash
# 저장
SET user:1:name "홍길동"
SET user:1:age 30

# 조회
GET user:1:name  # "홍길동"

# 숫자 증가/감소
INCR counter      # 1
INCR counter      # 2
DECR counter      # 1

# 만료 시간 설정
SET session:abc "data" EX 3600  # 1시간 후 만료
SETEX session:abc 3600 "data"   # 동일

# TTL 확인
TTL session:abc  # 남은 초
```

### 2.2 Hash

필드-값 쌍의 집합. 객체 저장에 적합.

```bash
# 저장
HSET user:1 name "홍길동" age 30 email "hong@example.com"

# 조회
HGET user:1 name       # "홍길동"
HGETALL user:1         # 모든 필드-값

# 필드 존재 확인
HEXISTS user:1 name    # 1 (존재)

# 필드 삭제
HDEL user:1 email
```

### 2.3 List

순서가 있는 문자열 리스트. 양쪽 끝에서 삽입/삭제.

```bash
# 오른쪽에 추가
RPUSH queue:orders "order1" "order2" "order3"

# 왼쪽에서 꺼내기 (FIFO 큐)
LPOP queue:orders  # "order1"

# 범위 조회
LRANGE queue:orders 0 -1  # 전체

# 길이
LLEN queue:orders
```

### 2.4 Set

중복 없는 문자열 집합.

```bash
# 추가
SADD tags:post:1 "java" "spring" "redis"

# 멤버 확인
SISMEMBER tags:post:1 "java"  # 1 (존재)

# 모든 멤버
SMEMBERS tags:post:1

# 집합 연산
SINTER tags:post:1 tags:post:2  # 교집합
SUNION tags:post:1 tags:post:2  # 합집합
```

### 2.5 Sorted Set (ZSet)

점수로 정렬된 집합. 랭킹에 적합.

```bash
# 추가 (점수, 멤버)
ZADD leaderboard 100 "player1" 200 "player2" 150 "player3"

# 순위 조회 (0부터 시작)
ZRANK leaderboard "player1"   # 0 (1등)
ZREVRANK leaderboard "player1"  # 역순

# 상위 N명
ZREVRANGE leaderboard 0 9 WITHSCORES  # 상위 10명

# 점수 증가
ZINCRBY leaderboard 50 "player1"  # 150
```

---

## 3. Spring Boot 연동

### 의존성

```groovy
// build.gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
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
      # password: your-password  # 필요시
      timeout: 3000ms
      lettuce:
        pool:
          max-active: 10
          max-idle: 5
          min-idle: 1
```

### RedisTemplate 사용

```java
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // 키는 문자열
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        // 값은 JSON
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());

        return template;
    }
}
```

```java
@Service
@RequiredArgsConstructor
public class ProductCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final String KEY_PREFIX = "product:";

    // 저장
    public void cacheProduct(Product product) {
        String key = KEY_PREFIX + product.getId();
        redisTemplate.opsForValue().set(key, product, Duration.ofHours(1));
    }

    // 조회
    public Product getCachedProduct(Long productId) {
        String key = KEY_PREFIX + productId;
        return (Product) redisTemplate.opsForValue().get(key);
    }

    // 삭제
    public void evictProduct(Long productId) {
        String key = KEY_PREFIX + productId;
        redisTemplate.delete(key);
    }
}
```

---

## 4. Spring Cache 추상화

### @Cacheable 사용

```java
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(30))
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())
            )
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer())
            );

        return RedisCacheManager.builder(factory)
            .cacheDefaults(config)
            .build();
    }
}
```

```java
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository repository;

    // 조회 시 캐시, 없으면 DB 조회 후 캐시
    @Cacheable(value = "products", key = "#id")
    public Product findById(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new ProductNotFoundException(id));
    }

    // 수정 시 캐시 업데이트
    @CachePut(value = "products", key = "#product.id")
    public Product update(Product product) {
        return repository.save(product);
    }

    // 삭제 시 캐시 제거
    @CacheEvict(value = "products", key = "#id")
    public void delete(Long id) {
        repository.deleteById(id);
    }

    // 전체 캐시 제거
    @CacheEvict(value = "products", allEntries = true)
    public void clearCache() {
    }
}
```

---

## 5. 캐싱 전략

### Cache-Aside (Lazy Loading)

```java
public Product getProduct(Long id) {
    // 1. 캐시 조회
    Product cached = cache.get(id);
    if (cached != null) {
        return cached;
    }

    // 2. DB 조회
    Product product = repository.findById(id);

    // 3. 캐시 저장
    cache.put(id, product);

    return product;
}
```

### Write-Through

```java
public Product saveProduct(Product product) {
    // 1. DB 저장
    Product saved = repository.save(product);

    // 2. 캐시 저장
    cache.put(saved.getId(), saved);

    return saved;
}
```

### Write-Behind (Write-Back)

```java
public void saveProduct(Product product) {
    // 1. 캐시에만 저장
    cache.put(product.getId(), product);

    // 2. 비동기로 DB 저장 (배치)
    asyncQueue.add(product);
}
```

---

## 6. 실습 과제

1. Redis 로컬 설치 또는 Docker 실행
2. Spring Boot Redis 연동
3. 상품 정보 캐싱 구현
4. @Cacheable 적용
5. TTL 설정 및 캐시 만료 테스트

---

## 참고 자료

- [Redis 공식 문서](https://redis.io/docs/)
- [Spring Data Redis](https://docs.spring.io/spring-data/redis/docs/current/reference/html/)
- [Redis 명령어 레퍼런스](https://redis.io/commands/)

---

## 다음 단계

[02-redis-stream.md](./02-redis-stream.md) - Redis Stream으로 이동
