# MDC 로깅 - 요청 추적

## 이 문서에서 배우는 것

- MDC(Mapped Diagnostic Context)의 개념
- 요청 추적 ID 구현
- 분산 환경에서의 로그 추적
- Spring Boot에서의 활용

---

## 1. 왜 요청 추적이 필요한가?

### 문제 상황: 로그 뒤섞임

```
// 동시에 여러 요청이 들어오면 로그가 뒤섞임
2024-01-15 10:30:00 INFO  주문 생성 시작
2024-01-15 10:30:00 INFO  재고 확인
2024-01-15 10:30:00 INFO  주문 생성 시작     ← 어떤 요청?
2024-01-15 10:30:01 INFO  결제 처리
2024-01-15 10:30:01 ERROR 결제 실패!        ← 어떤 주문의 결제?
2024-01-15 10:30:01 INFO  주문 완료
```

### MDC 적용 후

```
// 각 요청에 고유 ID 부여
2024-01-15 10:30:00 [req-abc123] INFO  주문 생성 시작
2024-01-15 10:30:00 [req-abc123] INFO  재고 확인
2024-01-15 10:30:00 [req-xyz789] INFO  주문 생성 시작
2024-01-15 10:30:01 [req-xyz789] INFO  결제 처리
2024-01-15 10:30:01 [req-xyz789] ERROR 결제 실패!  ← req-xyz789 추적 가능!
2024-01-15 10:30:01 [req-abc123] INFO  주문 완료
```

---

## 2. MDC란?

### Mapped Diagnostic Context

**MDC**는 스레드 로컬(ThreadLocal)에 컨텍스트 정보를 저장하여 로그에 자동으로 포함시키는 기능입니다.

```
┌─────────────────────────────────────────────────────────────┐
│                        MDC 동작                              │
│                                                              │
│  요청 A (스레드 1)              요청 B (스레드 2)            │
│       │                              │                       │
│       ▼                              ▼                       │
│  ┌─────────────┐               ┌─────────────┐              │
│  │ MDC (TL)    │               │ MDC (TL)    │              │
│  │ requestId=A │               │ requestId=B │              │
│  └─────────────┘               └─────────────┘              │
│       │                              │                       │
│       ▼                              ▼                       │
│  [req-A] 주문 생성            [req-B] 결제 처리             │
│  [req-A] 재고 확인            [req-B] 결제 완료             │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 기본 사용법

```java
import org.slf4j.MDC;

// 값 설정
MDC.put("requestId", "abc123");

// 로그 출력 (자동으로 requestId 포함)
log.info("주문 생성");  // [abc123] 주문 생성

// 값 제거
MDC.remove("requestId");

// 전체 클리어
MDC.clear();
```

---

## 3. 필터로 요청 ID 설정

### RequestIdFilter 구현

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)  // 가장 먼저 실행
public class RequestIdFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final String MDC_REQUEST_ID = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        try {
            // 1. 요청 헤더에서 ID 가져오기 (없으면 새로 생성)
            String requestId = request.getHeader(REQUEST_ID_HEADER);
            if (requestId == null || requestId.isBlank()) {
                requestId = generateRequestId();
            }

            // 2. MDC에 설정
            MDC.put(MDC_REQUEST_ID, requestId);

            // 3. 응답 헤더에도 추가 (클라이언트가 추적 가능)
            response.setHeader(REQUEST_ID_HEADER, requestId);

            // 4. 다음 필터 실행
            filterChain.doFilter(request, response);

        } finally {
            // 5. MDC 정리 (메모리 누수 방지)
            MDC.remove(MDC_REQUEST_ID);
        }
    }

    private String generateRequestId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
```

---

## 4. Logback 설정

### logback-spring.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <!-- 콘솔 출력 -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>
                %d{yyyy-MM-dd HH:mm:ss.SSS} [%X{requestId:-NO_REQ}] [%thread] %-5level %logger{36} - %msg%n
            </pattern>
        </encoder>
    </appender>

    <!-- 파일 출력 (JSON 형식) -->
    <appender name="FILE_JSON" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/application.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/application.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeMdcKeyName>requestId</includeMdcKeyName>
            <includeMdcKeyName>userId</includeMdcKeyName>
            <includeMdcKeyName>traceId</includeMdcKeyName>
        </encoder>
    </appender>

    <!-- 로거 설정 -->
    <root level="INFO">
        <appender-ref ref="CONSOLE" />
        <appender-ref ref="FILE_JSON" />
    </root>

    <logger name="com.example" level="DEBUG" />
</configuration>
```

### 패턴 설명

```
%X{requestId:-NO_REQ}

%X{key}     → MDC에서 key 값 가져오기
:-NO_REQ   → 값이 없으면 "NO_REQ" 출력
```

### 출력 예시

```
2024-01-15 10:30:00.123 [abc12345] [http-nio-8080-exec-1] INFO  OrderController - 주문 생성 요청
2024-01-15 10:30:00.456 [abc12345] [http-nio-8080-exec-1] DEBUG OrderService - 재고 확인: productId=123
2024-01-15 10:30:00.789 [abc12345] [http-nio-8080-exec-1] INFO  OrderService - 주문 생성 완료: orderId=456
```

---

## 5. 추가 컨텍스트 정보

### 사용자 정보 추가

```java
@Component
public class UserContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        try {
            // 인증된 사용자 정보 설정
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()) {
                MDC.put("userId", auth.getName());
            }

            // 클라이언트 IP
            MDC.put("clientIp", getClientIp(request));

            // 요청 경로
            MDC.put("requestPath", request.getRequestURI());

            filterChain.doFilter(request, response);

        } finally {
            MDC.remove("userId");
            MDC.remove("clientIp");
            MDC.remove("requestPath");
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
```

---

## 6. 분산 환경에서의 추적

### 서비스 간 Request ID 전파

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Client    │     │   Order     │     │  Payment    │
│             │     │   Service   │     │   Service   │
└──────┬──────┘     └──────┬──────┘     └──────┬──────┘
       │                   │                   │
       │ X-Request-ID: abc │                   │
       │──────────────────▶│                   │
       │                   │ X-Request-ID: abc │
       │                   │──────────────────▶│
       │                   │                   │
       │                   │    [abc] 결제 처리 │
       │    [abc] 주문 생성│                   │
```

### RestTemplate/WebClient에 헤더 전파

```java
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        RestTemplate restTemplate = new RestTemplate();

        restTemplate.getInterceptors().add((request, body, execution) -> {
            // MDC에서 requestId 가져와서 헤더에 추가
            String requestId = MDC.get("requestId");
            if (requestId != null) {
                request.getHeaders().add("X-Request-ID", requestId);
            }
            return execution.execute(request, body);
        });

        return restTemplate;
    }
}
```

### WebClient 설정

```java
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient() {
        return WebClient.builder()
            .filter((request, next) -> {
                String requestId = MDC.get("requestId");
                if (requestId != null) {
                    request = ClientRequest.from(request)
                        .header("X-Request-ID", requestId)
                        .build();
                }
                return next.exchange(request);
            })
            .build();
    }
}
```

---

## 7. 비동기 처리 시 주의

### 문제: 비동기에서 MDC 유실

```java
@Async
public void asyncProcess() {
    log.info("비동기 처리");  // MDC 값이 없음! (다른 스레드)
}
```

### 해결: TaskDecorator 사용

```java
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-");

        // MDC 복사 데코레이터 설정
        executor.setTaskDecorator(new MdcTaskDecorator());

        executor.initialize();
        return executor;
    }
}

public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // 현재 스레드의 MDC 복사
        Map<String, String> contextMap = MDC.getCopyOfContextMap();

        return () -> {
            try {
                // 새 스레드에 MDC 설정
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
```

### CompletableFuture 사용 시

```java
@Service
public class AsyncService {

    public CompletableFuture<String> asyncMethod() {
        // MDC 복사
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();

        return CompletableFuture.supplyAsync(() -> {
            try {
                if (mdcContext != null) {
                    MDC.setContextMap(mdcContext);
                }
                log.info("비동기 처리 시작");
                // 작업 수행
                return "result";
            } finally {
                MDC.clear();
            }
        });
    }
}
```

---

## 8. 로그 검색 활용

### Trace ID로 로그 검색

```bash
# 특정 요청의 모든 로그 검색
grep "abc12345" application.log

# 또는 JSON 로그에서
cat application.log | jq 'select(.requestId == "abc12345")'
```

### Grafana Loki 쿼리

```
{app="order-service"} |= "abc12345"
{app="order-service"} | json | requestId = "abc12345"
```

---

## 9. 우리 프로젝트 적용

### 오케스트레이터에서 각 서비스 호출 시

```java
@Component
@RequiredArgsConstructor
public class OrderServiceClient {

    private final RestTemplate restTemplate;

    public OrderResponse createOrder(OrderRequest request) {
        String requestId = MDC.get("requestId");

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-ID", requestId);

        HttpEntity<OrderRequest> entity = new HttpEntity<>(request, headers);

        log.info("Order Service 호출 시작");

        OrderResponse response = restTemplate.postForObject(
            "http://order-service/orders",
            entity,
            OrderResponse.class
        );

        log.info("Order Service 호출 완료: orderId={}", response.getOrderId());

        return response;
    }
}
```

### 로그 출력 예시

```
[abc12345] Orchestrator - Saga 시작: customerId=100
[abc12345] Orchestrator - Order Service 호출 시작
[abc12345] OrderController - 주문 생성 요청
[abc12345] OrderService - 주문 생성 완료: orderId=456
[abc12345] Orchestrator - Order Service 호출 완료: orderId=456
[abc12345] Orchestrator - Inventory Service 호출 시작
[abc12345] InventoryController - 재고 예약 요청
[abc12345] InventoryService - 재고 예약 완료
[abc12345] Orchestrator - Inventory Service 호출 완료
[abc12345] Orchestrator - Saga 완료: orderId=456
```

---

## 10. 실습 과제

1. RequestIdFilter 구현
2. logback-spring.xml 설정
3. 서비스 간 헤더 전파 구현
4. 비동기 MDC 전파 구현
5. 로그 검색으로 요청 추적 테스트

---

## 참고 자료

- [SLF4J MDC](https://www.slf4j.org/api/org/slf4j/MDC.html)
- [Logback MDC](https://logback.qos.ch/manual/mdc.html)
- [Spring Boot Logging](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.logging)

---

## 다음 단계

Phase 2-A 학습 완료! [Phase 2-B: Redis 기초](../phase2b/01-redis-basics.md)로 이동
