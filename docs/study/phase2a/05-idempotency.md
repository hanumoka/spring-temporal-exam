# 멱등성 (Idempotency)

## 이 문서에서 배우는 것

- 멱등성의 개념과 중요성
- Idempotency Key를 활용한 구현
- 중복 요청 방지 전략
- 실무 적용 패턴

---

## 1. 멱등성이란?

### 정의

**멱등성(Idempotency)**은 같은 연산을 여러 번 수행해도 결과가 동일한 성질입니다.

```
멱등한 연산:
f(x) = f(f(x)) = f(f(f(x))) = ...

예시:
- 절댓값: |x| = ||x|| = |||x|||
- 조회: GET /orders/123 (여러 번 해도 같은 결과)
- 삭제: DELETE /orders/123 (이미 없으면 그냥 성공)
```

### HTTP 메서드별 멱등성

| 메서드 | 멱등성 | 설명 |
|--------|--------|------|
| GET | ✓ | 조회는 상태를 변경하지 않음 |
| PUT | ✓ | 같은 데이터로 업데이트하면 결과 동일 |
| DELETE | ✓ | 이미 삭제된 것을 다시 삭제해도 결과 동일 |
| **POST** | ✗ | 생성은 호출할 때마다 새 리소스 생성 |
| PATCH | ? | 구현에 따라 다름 |

---

## 2. 왜 멱등성이 중요한가?

### 문제 시나리오: 네트워크 불안정

```
클라이언트                    서버
    │                          │
    │  POST /payments          │
    │  (결제 요청)             │
    │─────────────────────────▶│
    │                          │  결제 처리 완료!
    │    ✗ 네트워크 끊김       │
    │◀─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ │  (응답 유실)
    │                          │
    │  "응답이 없네? 재시도!"  │
    │  POST /payments          │
    │─────────────────────────▶│
    │                          │  또 결제 처리?! 😱
```

**결과**: 사용자가 두 번 결제됨!

### 멱등성 적용 후

```
클라이언트                    서버
    │                          │
    │  POST /payments          │
    │  Idempotency-Key: abc123 │
    │─────────────────────────▶│
    │                          │  결제 처리, 키 저장
    │    ✗ 네트워크 끊김       │
    │◀─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ │
    │                          │
    │  POST /payments          │
    │  Idempotency-Key: abc123 │  (같은 키)
    │─────────────────────────▶│
    │                          │  "이미 처리된 키!"
    │◀─────────────────────────│  기존 결과 반환
```

**결과**: 한 번만 처리됨!

---

## 3. Idempotency Key 구현

### 3.1 기본 구조

```
┌──────────────────────────────────────────────────────────┐
│                    Idempotency 흐름                       │
│                                                           │
│  1. 클라이언트가 고유한 Idempotency-Key 생성             │
│  2. 요청 시 헤더에 키 포함                                │
│  3. 서버가 키로 중복 확인                                 │
│     - 처음: 처리 후 결과 저장                            │
│     - 중복: 저장된 결과 반환                              │
└──────────────────────────────────────────────────────────┘
```

### 3.2 테이블 설계

```sql
CREATE TABLE idempotency_keys (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    idempotency_key VARCHAR(64) NOT NULL UNIQUE,
    request_path VARCHAR(255) NOT NULL,
    request_body TEXT,
    response_status INT,
    response_body TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,

    INDEX idx_key (idempotency_key),
    INDEX idx_expires (expires_at)
) ENGINE=InnoDB;
```

### 3.3 엔티티

```java
@Entity
@Table(name = "idempotency_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64)
    private String key;

    @Column(name = "request_path", nullable = false)
    private String requestPath;

    @Column(name = "request_body", columnDefinition = "TEXT")
    private String requestBody;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    // 처리 중 상태 (응답 저장 전)
    @Column(name = "processing")
    private boolean processing = true;

    public static IdempotencyRecord create(String key, String path, String body, Duration ttl) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.key = key;
        record.requestPath = path;
        record.requestBody = body;
        record.createdAt = LocalDateTime.now();
        record.expiresAt = LocalDateTime.now().plus(ttl);
        record.processing = true;
        return record;
    }

    public void complete(int status, String responseBody) {
        this.responseStatus = status;
        this.responseBody = responseBody;
        this.processing = false;
    }
}
```

### 3.4 서비스 구현

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    /**
     * 멱등성 키 확인 및 등록
     * @return Optional.empty() = 새 요청, Optional.present() = 중복 요청
     */
    @Transactional
    public Optional<IdempotencyRecord> checkAndCreate(
            String key, String path, String requestBody) {

        // 1. 기존 키 조회
        Optional<IdempotencyRecord> existing = repository.findByKey(key);

        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();

            // 만료된 키인지 확인
            if (record.getExpiresAt().isBefore(LocalDateTime.now())) {
                repository.delete(record);
                // 만료되었으면 새로 처리
            } else if (record.isProcessing()) {
                // 아직 처리 중 (이전 요청이 아직 끝나지 않음)
                throw new IdempotencyConflictException(
                    "이전 요청이 처리 중입니다. 잠시 후 다시 시도해주세요."
                );
            } else {
                // 이미 완료된 요청 - 캐시된 응답 반환
                log.info("중복 요청 감지: key={}", key);
                return existing;
            }
        }

        // 2. 새 키 등록
        IdempotencyRecord newRecord = IdempotencyRecord.create(
            key, path, requestBody, DEFAULT_TTL
        );
        repository.save(newRecord);
        log.info("새 멱등성 키 등록: key={}", key);

        return Optional.empty();
    }

    /**
     * 처리 완료 후 결과 저장
     */
    @Transactional
    public void complete(String key, int status, String responseBody) {
        repository.findByKey(key).ifPresent(record -> {
            record.complete(status, responseBody);
            repository.save(record);
            log.info("멱등성 키 완료: key={}, status={}", key, status);
        });
    }
}
```

### 3.5 인터셉터 또는 필터 구현

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyInterceptor implements HandlerInterceptor {

    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
            Object handler) throws Exception {

        // POST 요청만 처리
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String idempotencyKey = request.getHeader(IDEMPOTENCY_KEY_HEADER);

        // 키가 없으면 통과 (멱등성 보장 안 함)
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return true;
        }

        // 요청 본문 읽기 (캐싱 필요)
        String requestBody = getRequestBody(request);

        // 중복 확인
        Optional<IdempotencyRecord> existing = idempotencyService.checkAndCreate(
            idempotencyKey,
            request.getRequestURI(),
            requestBody
        );

        if (existing.isPresent()) {
            // 캐시된 응답 반환
            IdempotencyRecord record = existing.get();
            response.setStatus(record.getResponseStatus());
            response.setContentType("application/json");
            response.getWriter().write(record.getResponseBody());
            return false;  // 컨트롤러 호출 안 함
        }

        // 새 요청 처리 진행
        request.setAttribute("idempotencyKey", idempotencyKey);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception ex) {

        String idempotencyKey = (String) request.getAttribute("idempotencyKey");
        if (idempotencyKey == null) {
            return;
        }

        // 응답 저장 (ResponseBodyAdvice와 조합 필요)
        // 간단 구현을 위해 생략
    }
}
```

### 3.6 어노테이션 기반 구현 (AOP)

```java
// 어노테이션 정의
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    String keyHeader() default "Idempotency-Key";
    long ttlSeconds() default 86400;  // 24시간
}
```

```java
// AOP 구현
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotencyAspect {

    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    @Around("@annotation(idempotent)")
    public Object handleIdempotency(ProceedingJoinPoint joinPoint, Idempotent idempotent)
            throws Throwable {

        HttpServletRequest request = getCurrentRequest();
        String key = request.getHeader(idempotent.keyHeader());

        if (key == null || key.isBlank()) {
            return joinPoint.proceed();  // 키 없으면 그냥 진행
        }

        // 중복 확인
        Optional<IdempotencyRecord> existing = idempotencyService.checkAndCreate(
            key,
            request.getRequestURI(),
            getRequestBody(joinPoint)
        );

        if (existing.isPresent()) {
            // 캐시된 응답 반환
            return deserializeResponse(existing.get(), joinPoint);
        }

        // 새 요청 처리
        try {
            Object result = joinPoint.proceed();
            idempotencyService.complete(key, 200, objectMapper.writeValueAsString(result));
            return result;
        } catch (Exception e) {
            // 에러도 저장 (선택적)
            idempotencyService.complete(key, 500, e.getMessage());
            throw e;
        }
    }
}
```

```java
// 사용
@RestController
@RequestMapping("/payments")
public class PaymentController {

    @PostMapping
    @Idempotent  // 이 어노테이션만 추가!
    public PaymentResponse processPayment(@RequestBody PaymentRequest request) {
        return paymentService.process(request);
    }
}
```

---

## 4. Redis 기반 구현

DB 대신 Redis를 사용하면 더 빠릅니다:

```java
@Service
@RequiredArgsConstructor
public class RedisIdempotencyService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "idempotency:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    public Optional<CachedResponse> checkAndLock(String key) {
        String redisKey = KEY_PREFIX + key;

        // SETNX로 원자적 락 획득
        Boolean acquired = redisTemplate.opsForValue()
            .setIfAbsent(redisKey, "PROCESSING", DEFAULT_TTL);

        if (Boolean.FALSE.equals(acquired)) {
            // 이미 키가 존재
            String value = redisTemplate.opsForValue().get(redisKey);

            if ("PROCESSING".equals(value)) {
                throw new IdempotencyConflictException("처리 중");
            }

            // 완료된 응답 반환
            return Optional.of(deserialize(value));
        }

        return Optional.empty();  // 새 요청
    }

    public void complete(String key, CachedResponse response) {
        String redisKey = KEY_PREFIX + key;
        redisTemplate.opsForValue().set(
            redisKey,
            serialize(response),
            DEFAULT_TTL
        );
    }
}
```

---

## 5. 클라이언트 측 구현

### Idempotency Key 생성 규칙

```javascript
// 프론트엔드 예시
const idempotencyKey = crypto.randomUUID();
// 또는
const idempotencyKey = `${userId}-${timestamp}-${randomString}`;

fetch('/api/payments', {
    method: 'POST',
    headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey
    },
    body: JSON.stringify(paymentData)
});
```

### 재시도 시 같은 키 사용

```javascript
async function createPaymentWithRetry(data, maxRetries = 3) {
    const idempotencyKey = crypto.randomUUID();  // 한 번만 생성!

    for (let i = 0; i < maxRetries; i++) {
        try {
            const response = await fetch('/api/payments', {
                method: 'POST',
                headers: {
                    'Idempotency-Key': idempotencyKey  // 재시도에도 같은 키
                },
                body: JSON.stringify(data)
            });
            return await response.json();
        } catch (error) {
            if (i === maxRetries - 1) throw error;
            await sleep(1000 * (i + 1));  // 백오프
        }
    }
}
```

---

## 6. 주의사항

### 6.1 키 범위

```java
// ✗ 너무 넓은 범위
"payment-request"  // 모든 결제가 하나의 키

// ✓ 적절한 범위
"payment-user123-order456-1704067200"  // 특정 사용자, 주문, 시간
```

### 6.2 TTL 설정

```java
// 너무 짧으면: 재시도 시 이미 만료
// 너무 길면: 저장 공간 낭비

// 권장: 재시도 가능 시간 + 여유
Duration ttl = Duration.ofHours(24);  // 일반적
Duration ttl = Duration.ofMinutes(5);  // 짧은 작업
```

### 6.3 요청 본문 검증

```java
// 같은 키로 다른 요청이 오면?
public void checkAndCreate(String key, String requestBody) {
    Optional<IdempotencyRecord> existing = repository.findByKey(key);

    if (existing.isPresent()) {
        // 요청 본문도 같은지 확인
        if (!existing.get().getRequestBody().equals(requestBody)) {
            throw new IdempotencyMismatchException(
                "같은 키로 다른 요청이 전송되었습니다"
            );
        }
    }
}
```

---

## 7. 우리 프로젝트 적용

### 결제 서비스에 적용

```java
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final IdempotencyService idempotencyService;

    @PostMapping
    public ResponseEntity<PaymentResponse> processPayment(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {

        // 멱등성 키 확인
        if (idempotencyKey != null) {
            Optional<IdempotencyRecord> cached = idempotencyService.check(idempotencyKey);
            if (cached.isPresent()) {
                return ResponseEntity.ok(cached.get().getResponse());
            }
        }

        // 결제 처리
        PaymentResponse response = paymentService.process(request);

        // 결과 캐싱
        if (idempotencyKey != null) {
            idempotencyService.complete(idempotencyKey, response);
        }

        return ResponseEntity.ok(response);
    }
}
```

---

## 8. MyBatis 기반 구현

### 8.1 왜 MyBatis로도 학습하는가?

```
┌─────────────────────────────────────────────────────────────────────┐
│                    멱등성 쿼리 학습 포인트                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [JPA 방식]                                                          │
│  repository.save(record);  // unique constraint로 중복 방지          │
│  → 내부에서 INSERT/UPDATE 어떻게 처리되는지 모름                      │
│                                                                      │
│  [MyBatis 방식]                                                      │
│  INSERT IGNORE INTO ...     // 중복 시 무시                          │
│  ON DUPLICATE KEY UPDATE    // 중복 시 업데이트                      │
│  SELECT ... FOR UPDATE      // 락 걸고 조회                          │
│  → SQL 레벨에서 중복 방지 원리 체감                                   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 8.2 테이블 스키마

```sql
-- V4__create_idempotency_keys_table.sql
CREATE TABLE idempotency_keys (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    idempotency_key VARCHAR(64) NOT NULL,
    request_path VARCHAR(255) NOT NULL,
    request_hash VARCHAR(64),           -- 요청 본문 해시 (선택)
    response_status INT,
    response_body TEXT,
    processing BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL,
    expires_at DATETIME NOT NULL,

    UNIQUE KEY uk_idempotency_key (idempotency_key),
    INDEX idx_expires_at (expires_at)
) ENGINE=InnoDB;
```

### 8.3 도메인 객체

```java
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class IdempotencyRecord {
    private Long id;
    private String idempotencyKey;
    private String requestPath;
    private String requestHash;
    private Integer responseStatus;
    private String responseBody;
    private boolean processing;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    public boolean isExpired() {
        return expiresAt.isBefore(LocalDateTime.now());
    }

    public boolean isCompleted() {
        return !processing && responseStatus != null;
    }
}
```

### 8.4 Mapper 인터페이스

```java
@Mapper
public interface IdempotencyMapper {

    // 기존 키 조회 (락 포함)
    Optional<IdempotencyRecord> findByKeyForUpdate(String idempotencyKey);

    // 기존 키 조회 (락 없음)
    Optional<IdempotencyRecord> findByKey(String idempotencyKey);

    // 새 키 등록 (중복 시 무시)
    int insertIgnore(IdempotencyRecord record);

    // 처리 완료 업데이트
    int updateResponse(
        @Param("idempotencyKey") String idempotencyKey,
        @Param("responseStatus") int responseStatus,
        @Param("responseBody") String responseBody
    );

    // 만료된 키 삭제
    int deleteExpired();

    // 처리 중 상태 해제 (타임아웃 복구용)
    int releaseStaleProcessing(@Param("threshold") LocalDateTime threshold);
}
```

### 8.5 Mapper XML

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">

<mapper namespace="com.example.payment.mapper.IdempotencyMapper">

    <resultMap id="IdempotencyResultMap" type="com.example.payment.domain.IdempotencyRecord">
        <id property="id" column="id"/>
        <result property="idempotencyKey" column="idempotency_key"/>
        <result property="requestPath" column="request_path"/>
        <result property="requestHash" column="request_hash"/>
        <result property="responseStatus" column="response_status"/>
        <result property="responseBody" column="response_body"/>
        <result property="processing" column="processing"/>
        <result property="createdAt" column="created_at"/>
        <result property="expiresAt" column="expires_at"/>
    </resultMap>

    <!--
        락을 걸고 조회 (동시 요청 처리용)
        FOR UPDATE: 다른 트랜잭션이 같은 키를 동시에 처리하는 것 방지
    -->
    <select id="findByKeyForUpdate" resultMap="IdempotencyResultMap">
        SELECT id, idempotency_key, request_path, request_hash,
               response_status, response_body, processing, created_at, expires_at
        FROM idempotency_keys
        WHERE idempotency_key = #{idempotencyKey}
        FOR UPDATE
    </select>

    <!-- 락 없이 조회 (캐시된 응답 반환용) -->
    <select id="findByKey" resultMap="IdempotencyResultMap">
        SELECT id, idempotency_key, request_path, request_hash,
               response_status, response_body, processing, created_at, expires_at
        FROM idempotency_keys
        WHERE idempotency_key = #{idempotencyKey}
    </select>

    <!--
        INSERT IGNORE: 중복 키가 있으면 무시 (에러 없이 0 rows affected)

        중요: unique key 충돌 시 INSERT 실패하지 않고 무시됨
        → affected rows로 신규 삽입 여부 판단
    -->
    <insert id="insertIgnore">
        INSERT IGNORE INTO idempotency_keys (
            idempotency_key, request_path, request_hash,
            processing, created_at, expires_at
        ) VALUES (
            #{idempotencyKey}, #{requestPath}, #{requestHash},
            TRUE, #{createdAt}, #{expiresAt}
        )
    </insert>

    <!--
        대안: ON DUPLICATE KEY UPDATE
        중복 시 특정 필드만 업데이트 (upsert 패턴)
    -->
    <insert id="upsert">
        INSERT INTO idempotency_keys (
            idempotency_key, request_path, request_hash,
            processing, created_at, expires_at
        ) VALUES (
            #{idempotencyKey}, #{requestPath}, #{requestHash},
            TRUE, #{createdAt}, #{expiresAt}
        )
        ON DUPLICATE KEY UPDATE
            request_path = VALUES(request_path),
            created_at = created_at  <!-- 기존 값 유지 (dummy update) -->
    </insert>

    <!-- 처리 완료 후 응답 저장 -->
    <update id="updateResponse">
        UPDATE idempotency_keys
        SET response_status = #{responseStatus},
            response_body = #{responseBody},
            processing = FALSE
        WHERE idempotency_key = #{idempotencyKey}
          AND processing = TRUE
    </update>

    <!-- 만료된 키 삭제 (스케줄러용) -->
    <delete id="deleteExpired">
        DELETE FROM idempotency_keys
        WHERE expires_at &lt; NOW()
    </delete>

    <!-- 처리 중 상태로 오래 남은 레코드 해제 (장애 복구용) -->
    <update id="releaseStaleProcessing">
        UPDATE idempotency_keys
        SET processing = FALSE
        WHERE processing = TRUE
          AND created_at &lt; #{threshold}
    </update>

</mapper>
```

### 8.6 서비스 구현

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final IdempotencyMapper idempotencyMapper;
    private final ObjectMapper objectMapper;

    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    /**
     * 멱등성 키 확인 및 등록
     *
     * @return Optional.empty() = 새 요청, 처리 진행
     *         Optional.present() = 캐시된 응답 반환
     */
    @Transactional
    public Optional<IdempotencyRecord> checkAndLock(String key, String path, String requestBody) {

        // 1. INSERT IGNORE로 새 키 등록 시도
        IdempotencyRecord newRecord = IdempotencyRecord.builder()
            .idempotencyKey(key)
            .requestPath(path)
            .requestHash(hash(requestBody))
            .createdAt(LocalDateTime.now())
            .expiresAt(LocalDateTime.now().plus(DEFAULT_TTL))
            .build();

        int inserted = idempotencyMapper.insertIgnore(newRecord);

        if (inserted > 0) {
            // 신규 삽입 성공 → 새 요청
            log.info("새 멱등성 키 등록: {}", key);
            return Optional.empty();
        }

        // 2. 기존 키 존재 → 락 걸고 조회
        Optional<IdempotencyRecord> existing = idempotencyMapper.findByKeyForUpdate(key);

        if (existing.isEmpty()) {
            // 동시에 삭제됨 (드문 케이스)
            throw new IdempotencyConflictException("키 상태 불일치");
        }

        IdempotencyRecord record = existing.get();

        // 3. 만료 확인
        if (record.isExpired()) {
            log.info("만료된 키 재사용: {}", key);
            // 만료된 키는 새로 처리 (삭제 후 재삽입 또는 업데이트)
            return Optional.empty();
        }

        // 4. 처리 중인지 확인
        if (record.isProcessing()) {
            throw new IdempotencyConflictException("이전 요청이 처리 중입니다");
        }

        // 5. 요청 본문 일치 확인 (선택)
        if (!record.getRequestHash().equals(hash(requestBody))) {
            throw new IdempotencyMismatchException("같은 키로 다른 요청 전송됨");
        }

        // 6. 캐시된 응답 반환
        log.info("캐시된 응답 반환: {}", key);
        return existing;
    }

    /**
     * 처리 완료 후 응답 저장
     */
    @Transactional
    public void complete(String key, int status, Object response) {
        try {
            String responseBody = objectMapper.writeValueAsString(response);
            int updated = idempotencyMapper.updateResponse(key, status, responseBody);

            if (updated == 0) {
                log.warn("멱등성 키 업데이트 실패 (이미 완료됨?): {}", key);
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("응답 직렬화 실패", e);
        }
    }

    private String hash(String content) {
        if (content == null) return "";
        return DigestUtils.sha256Hex(content);
    }
}
```

### 8.7 동시 요청 처리 흐름

```
┌─────────────────────────────────────────────────────────────────────┐
│                    동시 요청 처리 (FOR UPDATE 활용)                   │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [요청 A]                     [요청 B] (같은 키)                      │
│     │                            │                                   │
│     │ INSERT IGNORE             │                                   │
│     │ (성공, 1 row)             │                                   │
│     │                            │ INSERT IGNORE                     │
│     │                            │ (실패, 0 row - 중복)              │
│     │                            │                                   │
│     │ 처리 중...                 │ SELECT ... FOR UPDATE             │
│     │                            │ (락 대기...)                      │
│     │                            │      │                            │
│     │ UPDATE (완료)              │      │                            │
│     │ COMMIT                     │      │                            │
│     │                            │ ◀────┘ (락 획득)                  │
│     │                            │                                   │
│     │                            │ processing=FALSE 확인             │
│     │                            │ → 캐시된 응답 반환                 │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 8.8 테스트

```java
@SpringBootTest
class IdempotencyServiceTest {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private IdempotencyMapper idempotencyMapper;

    @Test
    @DisplayName("INSERT IGNORE로 중복 요청이 무시된다")
    void insertIgnore_ignoresDuplicate() {
        String key = "test-key-001";
        String path = "/payments";
        String body = "{\"amount\": 10000}";

        // 첫 번째 요청 - 신규 등록
        Optional<IdempotencyRecord> first = idempotencyService.checkAndLock(key, path, body);
        assertThat(first).isEmpty();  // 새 요청

        // 완료 처리
        idempotencyService.complete(key, 200, Map.of("status", "success"));

        // 두 번째 요청 - 캐시 반환
        Optional<IdempotencyRecord> second = idempotencyService.checkAndLock(key, path, body);
        assertThat(second).isPresent();
        assertThat(second.get().getResponseStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("같은 키로 다른 요청 본문이 오면 예외 발생")
    void differentRequestBody_throwsException() {
        String key = "test-key-002";

        idempotencyService.checkAndLock(key, "/payments", "{\"amount\": 10000}");
        idempotencyService.complete(key, 200, "OK");

        // 같은 키, 다른 본문
        assertThatThrownBy(() ->
            idempotencyService.checkAndLock(key, "/payments", "{\"amount\": 20000}")
        ).isInstanceOf(IdempotencyMismatchException.class);
    }

    @Test
    @DisplayName("동시 요청 시 하나만 처리된다")
    void concurrentRequests_onlyOneProcessed() throws Exception {
        String key = "test-key-003";
        String path = "/payments";
        String body = "{\"amount\": 10000}";

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(10);
        AtomicInteger newRequests = new AtomicInteger(0);
        AtomicInteger cachedResponses = new AtomicInteger(0);

        for (int i = 0; i < 10; i++) {
            executor.submit(() -> {
                try {
                    Optional<IdempotencyRecord> result =
                        idempotencyService.checkAndLock(key, path, body);

                    if (result.isEmpty()) {
                        newRequests.incrementAndGet();
                        Thread.sleep(100);  // 처리 시뮬레이션
                        idempotencyService.complete(key, 200, "OK");
                    } else {
                        cachedResponses.incrementAndGet();
                    }
                } catch (IdempotencyConflictException e) {
                    // 처리 중 충돌 - 재시도 필요
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // 정확히 1개만 신규 처리
        assertThat(newRequests.get()).isEqualTo(1);
    }
}
```

### 8.9 JPA vs MyBatis 비교

```
┌─────────────────────────────────────────────────────────────────────┐
│                    멱등성 구현 비교                                   │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [JPA 방식]                                                          │
│                                                                      │
│  try {                                                               │
│      repository.save(record);  // INSERT 시도                        │
│  } catch (DataIntegrityViolationException e) {                       │
│      // unique 제약조건 위반 → 중복                                  │
│      return repository.findByKey(key);                               │
│  }                                                                   │
│                                                                      │
│  → 예외 기반 처리, 성능 이슈 가능                                    │
│                                                                      │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [MyBatis 방식]                                                      │
│                                                                      │
│  int inserted = mapper.insertIgnore(record);                         │
│  if (inserted == 0) {                                                │
│      // 중복 - 기존 레코드 조회                                       │
│      return mapper.findByKeyForUpdate(key);                          │
│  }                                                                   │
│                                                                      │
│  → 반환값 기반 처리, 예외 없음, 명시적                                │
│                                                                      │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  핵심 SQL 패턴:                                                       │
│  • INSERT IGNORE - 중복 시 무시 (MySQL)                              │
│  • ON DUPLICATE KEY UPDATE - 중복 시 업데이트 (MySQL)                │
│  • INSERT ... ON CONFLICT DO NOTHING - PostgreSQL                    │
│  • SELECT ... FOR UPDATE - 동시 접근 제어                            │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 9. 실습 과제

### JPA 실습
1. IdempotencyRecord 엔티티 생성
2. IdempotencyService 구현
3. 결제 API에 멱등성 적용
4. 중복 요청 테스트
5. TTL 만료 테스트

### MyBatis 실습
6. idempotency_keys 테이블 생성 (Flyway)
7. IdempotencyMapper XML 작성 (INSERT IGNORE, FOR UPDATE)
8. 반환값으로 신규/중복 판단 로직 구현
9. 동시 요청 테스트 (FOR UPDATE 락 동작 확인)
10. ON DUPLICATE KEY UPDATE 패턴 비교 구현

---

## 참고 자료

- [Stripe Idempotency](https://stripe.com/docs/api/idempotent_requests)
- [IETF - The Idempotency-Key HTTP Header Field](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/)
- [Google API Design - Idempotency](https://google.aip.dev/154)

---

## 다음 단계

[06-bean-validation.md](./06-bean-validation.md) - 입력 검증으로 이동
