# Resilience4j - 장애 대응

## 이 문서에서 배우는 것

- 분산 시스템에서의 장애 유형 이해
- Resilience4j의 핵심 모듈 (Retry, CircuitBreaker, TimeLimiter, RateLimiter)
- Spring Boot에서의 설정 및 사용 방법
- 실제 적용 예시

---

## 1. 분산 시스템의 장애

### MSA 환경에서의 장애 시나리오

```
[시나리오 1: 서비스 일시 장애]
Order Service ──▶ Inventory Service (502 Bad Gateway)
                  └─▶ 잠시 후 다시 시도하면 성공할 수 있음

[시나리오 2: 네트워크 타임아웃]
Order Service ──▶ Payment Service (30초 응답 없음)
                  └─▶ 무한 대기? 사용자 경험 악화

[시나리오 3: 연쇄 장애 (Cascading Failure)]
A ──▶ B ──▶ C (장애)
      │
      └─▶ B도 응답 지연 → A도 응답 지연 → 전체 시스템 마비!
```

### 장애 대응 패턴

| 패턴 | 설명 | 사용 시나리오 |
|------|------|-------------|
| **Retry** | 실패 시 재시도 | 일시적 네트워크 오류 |
| **Circuit Breaker** | 장애 서비스 차단 | 연쇄 장애 방지 |
| **Time Limiter** | 타임아웃 설정 | 무한 대기 방지 |
| **Rate Limiter** | 호출 횟수 제한 | 서버 과부하 방지 |
| **Bulkhead** | 리소스 격리 | 장애 격리 |

---

## 2. Resilience4j 소개

### Resilience4j란?

Netflix Hystrix의 대안으로 개발된 **경량 장애 허용 라이브러리**입니다.

```
┌─────────────────────────────────────────────────────────────┐
│                      Resilience4j                            │
│                                                              │
│  ┌──────────┐ ┌──────────────┐ ┌───────────┐ ┌───────────┐ │
│  │  Retry   │ │CircuitBreaker│ │TimeLimiter│ │RateLimiter│ │
│  └──────────┘ └──────────────┘ └───────────┘ └───────────┘ │
│                                                              │
│  ┌──────────┐ ┌──────────────┐                              │
│  │ Bulkhead │ │    Cache     │                              │
│  └──────────┘ └──────────────┘                              │
└─────────────────────────────────────────────────────────────┘
```

### 의존성 추가

```groovy
// build.gradle
dependencies {
    implementation 'io.github.resilience4j:resilience4j-spring-boot3:2.2.0'
    implementation 'org.springframework.boot:spring-boot-starter-aop'  // AOP 필요
}
```

### 적용 단위

**Resilience4j는 호출하는 쪽(클라이언트)에서 메서드/API 단위로 적용합니다.**

```
┌─────────────────────────────────────────────────────────────────┐
│                        적용 위치                                 │
│                                                                  │
│  [Order Service]                      [Inventory Service]       │
│       │                                      │                  │
│       │  ┌─────────────────────┐             │                  │
│       └─▶│ InventoryClient     │────────────▶│                  │
│          │                     │             │                  │
│          │ @Retry              │             │                  │
│          │ @CircuitBreaker     │  ← 여기!    │                  │
│          └─────────────────────┘             │                  │
│                                                                  │
│  Resilience4j는 "호출하는 쪽"에서 적용                           │
│  (서버가 아니라 클라이언트에 설정)                               │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**메서드/API 별로 다르게 설정 가능:**

```java
@Service
public class ExternalClients {

    // 재고 서비스 - 재시도 3번, 서킷 50%
    @Retry(name = "inventoryService")
    @CircuitBreaker(name = "inventoryService")
    public Response callInventory() { ... }

    // 결제 서비스 - 재시도 2번, 서킷 30% (더 민감)
    @Retry(name = "paymentService")
    @CircuitBreaker(name = "paymentService")
    public Response callPayment() { ... }

    // 알림 서비스 - 재시도만, 서킷 없음 (덜 중요)
    @Retry(name = "notificationService")
    public Response callNotification() { ... }
}
```

**적용 단위 정리:**

| 질문 | 답변 |
|------|------|
| 어디에 적용? | **호출하는 쪽** (클라이언트) |
| 어떤 단위? | **메서드/API 단위** (인스턴스 이름으로 구분) |
| 서비스당 하나? | 아니오, **같은 서비스라도 API별로 다르게** 설정 가능 |
| 서버에 설정? | 아니오, **클라이언트에서 설정** |

**실무 예시:**

```
Order Service (호출하는 쪽)
├── InventoryClient
│   ├── reserveStock()  → @CircuitBreaker("inventory-reserve")
│   └── checkStock()    → @CircuitBreaker("inventory-check")  // 다른 설정 가능
│
├── PaymentClient
│   └── processPayment() → @CircuitBreaker("payment")
│
└── NotificationClient
    └── sendEmail()      → @Retry만 (서킷 없음, 덜 중요)
```

즉, **MSA 서비스 단위가 아니라**, 각 서비스 내에서 **외부 호출하는 메서드 단위**로 세밀하게 적용합니다.

---

## 3. Retry (재시도)

### 개념

일시적인 오류 시 **자동으로 재시도**합니다.

```
요청 ──▶ [1차 시도] ── 실패 ──▶ [대기] ──▶ [2차 시도] ── 실패 ──▶ [대기] ──▶ [3차 시도] ── 성공!
```

### 설정

```yaml
# application.yml
resilience4j:
  retry:
    instances:
      inventoryService:
        max-attempts: 3                    # 최대 3번 시도
        wait-duration: 1s                  # 재시도 간격 1초
        retry-exceptions:                  # 재시도할 예외
          - java.io.IOException
          - java.net.SocketTimeoutException
        ignore-exceptions:                 # 재시도하지 않을 예외
          - com.example.InsufficientStockException
```

### 사용 방법

```java
// 방법 1: 어노테이션 사용
@Service
public class InventoryServiceClient {

    @Retry(name = "inventoryService", fallbackMethod = "reserveStockFallback")
    public ReservationResponse reserveStock(ReservationRequest request) {
        return restTemplate.postForObject(
            "http://inventory-service/reservations",
            request,
            ReservationResponse.class
        );
    }

    // 모든 재시도 실패 시 호출되는 폴백 메서드
    private ReservationResponse reserveStockFallback(ReservationRequest request, Exception e) {
        log.error("재고 예약 실패 (재시도 초과): {}", e.getMessage());
        throw new ServiceUnavailableException("재고 서비스 일시 장애");
    }
}
```

```java
// 방법 2: 프로그래밍 방식
@Service
@RequiredArgsConstructor
public class InventoryServiceClient {

    private final RetryRegistry retryRegistry;

    public ReservationResponse reserveStock(ReservationRequest request) {
        Retry retry = retryRegistry.retry("inventoryService");

        return retry.executeSupplier(() -> {
            return restTemplate.postForObject(
                "http://inventory-service/reservations",
                request,
                ReservationResponse.class
            );
        });
    }
}
```

### 재시도 전략

```yaml
resilience4j:
  retry:
    instances:
      inventoryService:
        max-attempts: 3
        wait-duration: 500ms
        # 지수 백오프: 500ms → 1s → 2s
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
        # 또는 랜덤 지터 추가 (동시 재시도 분산)
        enable-randomized-wait: true
        randomized-wait-factor: 0.5  # 0.5~1.5배 랜덤
```

---

## 4. Circuit Breaker (서킷 브레이커)

### 개념

전기 회로의 차단기와 같이, **장애 서비스로의 호출을 차단**합니다.

```
┌─────────────────────────────────────────────────────────────┐
│                    서킷 브레이커 상태                        │
│                                                              │
│  ┌────────┐      실패율 임계값 초과      ┌────────┐         │
│  │ CLOSED │ ─────────────────────────▶ │  OPEN  │         │
│  │(정상)  │                             │(차단)  │         │
│  └────────┘                             └────┬───┘         │
│       ▲                                      │              │
│       │                                      │ 대기 시간 후  │
│       │     성공률 회복                      ▼              │
│       │                            ┌──────────────┐        │
│       └────────────────────────────│  HALF_OPEN   │        │
│                                    │(일부 허용)   │        │
│                                    └──────────────┘        │
└─────────────────────────────────────────────────────────────┘
```

### 상태 설명

| 상태 | 설명 |
|------|------|
| **CLOSED** | 정상 상태. 모든 요청 허용 |
| **OPEN** | 차단 상태. 모든 요청 즉시 실패 (빠른 실패) |
| **HALF_OPEN** | 일부 요청만 허용하여 복구 여부 확인 |

### 설정

```yaml
resilience4j:
  circuitbreaker:
    instances:
      paymentService:
        # 슬라이딩 윈도우 설정
        sliding-window-type: COUNT_BASED    # 또는 TIME_BASED
        sliding-window-size: 10             # 최근 10번의 호출 기준

        # OPEN 전환 조건
        failure-rate-threshold: 50          # 실패율 50% 이상이면 OPEN
        slow-call-rate-threshold: 80        # 느린 호출 80% 이상이면 OPEN
        slow-call-duration-threshold: 3s    # 3초 이상이면 느린 호출

        # OPEN 상태 유지 시간
        wait-duration-in-open-state: 30s    # 30초 후 HALF_OPEN

        # HALF_OPEN 상태 설정
        permitted-number-of-calls-in-half-open-state: 5  # 5개 요청 허용

        # 최소 호출 수 (이 이하면 서킷 작동 안 함)
        minimum-number-of-calls: 5

        # 기록할 예외
        record-exceptions:
          - java.io.IOException
          - java.net.SocketTimeoutException
        ignore-exceptions:
          - com.example.BusinessException
```

### 사용 방법

```java
@Service
public class PaymentServiceClient {

    @CircuitBreaker(name = "paymentService", fallbackMethod = "processPaymentFallback")
    public PaymentResponse processPayment(PaymentRequest request) {
        return restTemplate.postForObject(
            "http://payment-service/payments",
            request,
            PaymentResponse.class
        );
    }

    // 서킷이 OPEN 상태일 때 즉시 호출
    private PaymentResponse processPaymentFallback(PaymentRequest request, Exception e) {
        if (e instanceof CallNotPermittedException) {
            log.warn("서킷 브레이커 OPEN 상태 - 결제 서비스 일시 중단");
        }
        throw new ServiceUnavailableException("결제 서비스를 사용할 수 없습니다");
    }
}
```

### 서킷 브레이커 이벤트 모니터링

```java
@Component
@RequiredArgsConstructor
public class CircuitBreakerEventListener {

    private final CircuitBreakerRegistry registry;

    @PostConstruct
    public void registerEventListeners() {
        CircuitBreaker cb = registry.circuitBreaker("paymentService");

        cb.getEventPublisher()
            .onStateTransition(event -> {
                log.info("서킷 브레이커 상태 변경: {} → {}",
                    event.getStateTransition().getFromState(),
                    event.getStateTransition().getToState());
            })
            .onFailureRateExceeded(event -> {
                log.warn("실패율 임계값 초과: {}%", event.getFailureRate());
            });
    }
}
```

---

## 5. Time Limiter (타임아웃)

### 개념

**최대 대기 시간을 설정**하여 무한 대기를 방지합니다.

```
요청 ──▶ [서비스 호출] ──┬── 2초 내 응답 ──▶ 성공
                         │
                         └── 2초 초과 ──▶ TimeoutException
```

### 설정

```yaml
resilience4j:
  timelimiter:
    instances:
      inventoryService:
        timeout-duration: 3s           # 최대 3초 대기
        cancel-running-future: true    # 타임아웃 시 작업 취소
```

### 사용 방법

```java
@Service
public class InventoryServiceClient {

    @TimeLimiter(name = "inventoryService", fallbackMethod = "reserveStockTimeout")
    @CircuitBreaker(name = "inventoryService")  // 함께 사용 가능
    public CompletableFuture<ReservationResponse> reserveStockAsync(ReservationRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            return restTemplate.postForObject(
                "http://inventory-service/reservations",
                request,
                ReservationResponse.class
            );
        });
    }

    private CompletableFuture<ReservationResponse> reserveStockTimeout(
            ReservationRequest request, TimeoutException e) {
        log.error("재고 서비스 응답 타임아웃");
        return CompletableFuture.failedFuture(
            new ServiceTimeoutException("재고 서비스 응답 시간 초과")
        );
    }
}
```

**주의**: TimeLimiter는 `CompletableFuture`를 반환해야 합니다.

### RestTemplate 타임아웃 설정

TimeLimiter와 별개로 RestTemplate 자체 타임아웃도 설정해야 합니다:

```java
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        HttpComponentsClientHttpRequestFactory factory =
            new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(5000);   // 연결 타임아웃 5초
        factory.setReadTimeout(5000);      // 읽기 타임아웃 5초

        return new RestTemplate(factory);
    }
}
```

---

## 6. Rate Limiter (호출 제한)

### 개념

**일정 시간 내 호출 횟수를 제한**합니다.

```
1초에 최대 10번 호출 허용
──▶ ──▶ ──▶ ──▶ ──▶ ──▶ ──▶ ──▶ ──▶ ──▶ (10번 허용)
──▶ ──▶ ✗ ✗ ✗  (초과 시 거부 또는 대기)
```

### 설정

```yaml
resilience4j:
  ratelimiter:
    instances:
      externalApi:
        limit-for-period: 10          # 주기당 10번 허용
        limit-refresh-period: 1s      # 1초마다 리셋
        timeout-duration: 500ms       # 대기 최대 시간
```

### 사용 방법

```java
@Service
public class ExternalApiClient {

    @RateLimiter(name = "externalApi", fallbackMethod = "callApiRateLimited")
    public ApiResponse callExternalApi(ApiRequest request) {
        return restTemplate.postForObject(
            "http://external-api/endpoint",
            request,
            ApiResponse.class
        );
    }

    private ApiResponse callApiRateLimited(ApiRequest request, RequestNotPermitted e) {
        log.warn("API 호출 제한 초과");
        throw new TooManyRequestsException("잠시 후 다시 시도해주세요");
    }
}
```

---

## 7. 여러 패턴 조합

### 권장 순서

```
요청 ──▶ [RateLimiter] ──▶ [TimeLimiter] ──▶ [CircuitBreaker] ──▶ [Retry] ──▶ 서비스
```

### 어노테이션 조합 예시

```java
@Service
public class PaymentServiceClient {

    @RateLimiter(name = "paymentService")
    @TimeLimiter(name = "paymentService")
    @CircuitBreaker(name = "paymentService")
    @Retry(name = "paymentService")
    public CompletableFuture<PaymentResponse> processPayment(PaymentRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            return restTemplate.postForObject(
                "http://payment-service/payments",
                request,
                PaymentResponse.class
            );
        });
    }
}
```

### 우선순위 설정

```yaml
resilience4j:
  # 순서 설정 (숫자가 작을수록 먼저 실행)
  retry:
    retry-aspect-order: 1
  circuitbreaker:
    circuit-breaker-aspect-order: 2
  timelimiter:
    time-limiter-aspect-order: 3
  ratelimiter:
    rate-limiter-aspect-order: 4
```

---

## 8. 모니터링

### Actuator 연동

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,circuitbreakers,retries,ratelimiters
  endpoint:
    health:
      show-details: always
  health:
    circuitbreakers:
      enabled: true
```

### 엔드포인트 확인

```bash
# 서킷 브레이커 상태
curl http://localhost:8080/actuator/circuitbreakers

# 특정 서킷 브레이커
curl http://localhost:8080/actuator/circuitbreakers/paymentService

# 재시도 통계
curl http://localhost:8080/actuator/retries
```

### 메트릭 (Prometheus)

```yaml
# Prometheus 메트릭 노출
management:
  metrics:
    export:
      prometheus:
        enabled: true
```

```
# Prometheus에서 조회 가능한 메트릭
resilience4j_circuitbreaker_state
resilience4j_circuitbreaker_calls_total
resilience4j_retry_calls_total
resilience4j_timelimiter_calls_total
```

---

## 9. 우리 프로젝트 적용

### 오케스트레이터 설정

```yaml
# orchestrator-pure/src/main/resources/application.yml
resilience4j:
  retry:
    instances:
      orderService:
        max-attempts: 3
        wait-duration: 500ms
        retry-exceptions:
          - java.io.IOException
      inventoryService:
        max-attempts: 3
        wait-duration: 500ms
      paymentService:
        max-attempts: 2  # 결제는 적게 재시도
        wait-duration: 1s

  circuitbreaker:
    instances:
      orderService:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
      inventoryService:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
      paymentService:
        sliding-window-size: 10
        failure-rate-threshold: 30  # 결제는 더 민감하게
        wait-duration-in-open-state: 60s

  timelimiter:
    instances:
      orderService:
        timeout-duration: 3s
      inventoryService:
        timeout-duration: 3s
      paymentService:
        timeout-duration: 5s  # 결제는 좀 더 여유 있게
```

---

## 10. 실습 과제

1. Resilience4j 의존성 추가
2. Retry 설정 및 적용
3. CircuitBreaker 설정 및 적용
4. 장애 시뮬레이션 (서비스 중지 후 호출)
5. Actuator로 상태 모니터링

---

## 참고 자료

- [Resilience4j 공식 문서](https://resilience4j.readme.io/)
- [Resilience4j Spring Boot 3 가이드](https://resilience4j.readme.io/docs/getting-started-3)
- [Martin Fowler - Circuit Breaker](https://martinfowler.com/bliki/CircuitBreaker.html)

---

## 다음 단계

[03-distributed-lock.md](./03-distributed-lock.md) - 분산 락으로 이동
