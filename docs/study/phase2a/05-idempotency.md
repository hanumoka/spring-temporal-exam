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

## 8. 실습 과제

1. IdempotencyRecord 엔티티 생성
2. IdempotencyService 구현
3. 결제 API에 멱등성 적용
4. 중복 요청 테스트
5. TTL 만료 테스트

---

## 참고 자료

- [Stripe Idempotency](https://stripe.com/docs/api/idempotent_requests)
- [IETF - The Idempotency-Key HTTP Header Field](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/)
- [Google API Design - Idempotency](https://google.aip.dev/154)

---

## 다음 단계

[06-bean-validation.md](./06-bean-validation.md) - 입력 검증으로 이동
