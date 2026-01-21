# 분산 추적 - OpenTelemetry와 Zipkin

## 이 문서에서 배우는 것

- 분산 추적(Distributed Tracing)의 개념과 필요성
- OpenTelemetry의 구성 요소
- Trace, Span, Context Propagation 이해
- Spring Boot에서 OpenTelemetry 설정
- Zipkin을 활용한 추적 데이터 시각화
- 실전 분산 추적 구현 패턴

---

## 1. 분산 추적이란?

### 마이크로서비스 환경의 문제

```
┌─────────────────────────────────────────────────────────────────────┐
│                    분산 시스템의 문제점                               │
│                                                                      │
│   요청이 어디서 느려지는지 어떻게 알 수 있을까?                       │
│                                                                      │
│   Client                                                             │
│     │                                                               │
│     │ 요청 (3초 후 응답)                                             │
│     ▼                                                               │
│   ┌────────────┐                                                    │
│   │   API      │ ───▶ 어디가 느린 걸까?                              │
│   │  Gateway   │                                                    │
│   └────┬───────┘                                                    │
│        │                                                            │
│   ┌────┴────────────────────────────────────────────┐              │
│   ▼                    ▼                    ▼       │              │
│ ┌────────┐         ┌────────┐         ┌────────┐   │              │
│ │ Order  │────────▶│ Stock  │────────▶│Payment │   │              │
│ │Service │         │Service │         │Service │   │              │
│ └───┬────┘         └───┬────┘         └───┬────┘   │              │
│     │                  │                  │         │              │
│     ▼                  ▼                  ▼         │              │
│ ┌────────┐         ┌────────┐         ┌────────┐   │              │
│ │  MySQL │         │  Redis │         │  PG DB │   │              │
│ └────────┘         └────────┘         └────────┘   │              │
│                                                     │              │
│   각 서비스 로그를 하나씩 확인?  → 비효율적!         │              │
│   연관된 요청을 어떻게 연결?     → 불가능!           │              │
└─────────────────────────────────────────────────────────────────────┘
```

### 분산 추적의 해결

```
┌─────────────────────────────────────────────────────────────────────┐
│                    분산 추적 적용 후                                  │
│                                                                      │
│   Trace ID: abc123 (하나의 요청 전체를 추적)                          │
│                                                                      │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                         Timeline                             │   │
│   │                                                              │   │
│   │  API Gateway  ████████████████████████████████████████  3.0s │   │
│   │  Span A                                                      │   │
│   │                                                              │   │
│   │    Order Svc    ████████████████████████████████      2.5s  │   │
│   │    Span B                                                    │   │
│   │                                                              │   │
│   │      MySQL        ████████                          0.5s    │   │
│   │      Span C                                                  │   │
│   │                                                              │   │
│   │      Stock Svc          ██████████████████          1.2s    │   │
│   │      Span D                                                  │   │
│   │                                                              │   │
│   │        Redis              ████                      0.3s    │   │
│   │        Span E                                                │   │
│   │                                                              │   │
│   │      Payment              ████████████              0.8s    │   │
│   │      Span F                 ↑                                │   │
│   │                         병목 지점 발견!                       │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│   한 눈에 전체 요청 흐름과 병목 지점을 파악!                          │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 2. 핵심 개념

### Trace와 Span

```
┌─────────────────────────────────────────────────────────────────────┐
│                       Trace와 Span 구조                              │
│                                                                      │
│   Trace: 하나의 요청에 대한 전체 여정                                 │
│   Span: Trace 내의 개별 작업 단위                                    │
│                                                                      │
│   Trace ID: abc123                                                  │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                                                              │   │
│   │   Span A (Root Span)                                        │   │
│   │   ID: span-001                                               │   │
│   │   Parent: null                                               │   │
│   │   Name: "GET /api/orders"                                    │   │
│   │   ├──────────────────────────────────────────────────────┤   │   │
│   │   │                                                      │   │   │
│   │   │   Span B (Child Span)                               │   │   │
│   │   │   ID: span-002                                       │   │   │
│   │   │   Parent: span-001                                   │   │   │
│   │   │   Name: "OrderService.createOrder"                   │   │   │
│   │   │   ├──────────────────────────────────────────────┤   │   │   │
│   │   │   │                                              │   │   │   │
│   │   │   │   Span C                                    │   │   │   │
│   │   │   │   Name: "MySQL INSERT"                      │   │   │   │
│   │   │   │                                              │   │   │   │
│   │   │   ├──────────────────────────────────────────────┤   │   │   │
│   │   │   │                                              │   │   │   │
│   │   │   │   Span D                                    │   │   │   │
│   │   │   │   Name: "HTTP POST /stock/reserve"          │   │   │   │
│   │   │   │                                              │   │   │   │
│   │   │   └──────────────────────────────────────────────┘   │   │   │
│   │   │                                                      │   │   │
│   │   └──────────────────────────────────────────────────────┘   │   │
│   │                                                              │   │
│   └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### Span 속성

```java
// Span에 포함되는 정보
public class SpanData {
    String traceId;          // 전체 추적 ID
    String spanId;           // 현재 Span ID
    String parentSpanId;     // 부모 Span ID
    String name;             // 작업 이름
    long startTime;          // 시작 시간
    long endTime;            // 종료 시간
    SpanKind kind;           // CLIENT, SERVER, PRODUCER, CONSUMER, INTERNAL
    StatusCode status;       // OK, ERROR
    Map<String, Object> attributes;  // 추가 속성
    List<Event> events;      // 이벤트 (예외 발생 등)
}
```

### Context Propagation

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Context Propagation                               │
│                                                                      │
│   서비스 간 Trace 정보를 전달하는 방법                                │
│                                                                      │
│   Service A                           Service B                     │
│   ┌─────────────────┐                ┌─────────────────┐            │
│   │                 │                │                 │            │
│   │  Trace Context  │ ──HTTP 헤더──▶ │  Trace Context  │            │
│   │                 │                │                 │            │
│   └─────────────────┘                └─────────────────┘            │
│                                                                      │
│   HTTP 헤더 예시 (W3C Trace Context):                                │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │  traceparent: 00-abc123...-def456...-01                     │   │
│   │               ↑  ↑           ↑          ↑                   │   │
│   │            version trace-id  span-id  flags                 │   │
│   │                                                              │   │
│   │  tracestate: vendor1=value1,vendor2=value2                  │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│   • HTTP: traceparent, tracestate 헤더                              │
│   • Kafka: 메시지 헤더에 포함                                        │
│   • gRPC: metadata에 포함                                           │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 3. OpenTelemetry 개요

### OpenTelemetry란?

**OpenTelemetry**는 분산 추적, 메트릭, 로그를 수집하기 위한 표준화된 프레임워크입니다.

```
┌─────────────────────────────────────────────────────────────────────┐
│                    OpenTelemetry 구성요소                            │
│                                                                      │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                    Application                               │   │
│   │                                                              │   │
│   │   ┌─────────────────────────────────────────────────────┐   │   │
│   │   │           OpenTelemetry SDK                         │   │   │
│   │   │                                                     │   │   │
│   │   │  ┌───────────┐ ┌───────────┐ ┌───────────┐         │   │   │
│   │   │  │  Traces   │ │  Metrics  │ │   Logs    │         │   │   │
│   │   │  │  Provider │ │  Provider │ │  Provider │         │   │   │
│   │   │  └─────┬─────┘ └─────┬─────┘ └─────┬─────┘         │   │   │
│   │   │        │             │             │               │   │   │
│   │   │        └─────────────┼─────────────┘               │   │   │
│   │   │                      │                              │   │   │
│   │   │              ┌───────▼───────┐                     │   │   │
│   │   │              │   Exporter    │                     │   │   │
│   │   │              │  (OTLP/Zipkin)│                     │   │   │
│   │   │              └───────┬───────┘                     │   │   │
│   │   └──────────────────────┼──────────────────────────────┘   │   │
│   └──────────────────────────┼──────────────────────────────────┘   │
│                              │                                      │
│                              ▼                                      │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │              Backend (Zipkin, Jaeger, etc.)                 │   │
│   └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### OpenTelemetry vs 기존 솔루션

| 특징 | OpenTelemetry | Sleuth | Zipkin |
|------|--------------|--------|--------|
| **역할** | 수집 표준 | Spring 통합 | 저장/시각화 |
| **표준화** | CNCF 표준 | Spring 전용 | - |
| **언어 지원** | 다양함 | Java/Kotlin | - |
| **벤더 종속** | 없음 | 없음 | Zipkin 전용 |
| **미래** | 표준 방향 | Micrometer로 이관 | 유지 |

---

## 4. Spring Boot 연동

### 의존성 설정

```groovy
// build.gradle
dependencies {
    // Spring Boot 3.x 기준
    implementation 'io.micrometer:micrometer-tracing-bridge-otel'
    implementation 'io.opentelemetry:opentelemetry-exporter-zipkin'

    // 또는 OTLP 사용 시
    // implementation 'io.opentelemetry:opentelemetry-exporter-otlp'

    // 자동 계측
    implementation 'io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter'
}
```

### 설정 파일

```yaml
# application.yml
spring:
  application:
    name: order-service

management:
  tracing:
    sampling:
      probability: 1.0  # 100% 샘플링 (운영에서는 0.1 등으로 조정)

  zipkin:
    tracing:
      endpoint: http://localhost:9411/api/v2/spans

# OpenTelemetry 설정
otel:
  exporter:
    zipkin:
      endpoint: http://localhost:9411/api/v2/spans
  resource:
    attributes:
      service.name: order-service
      deployment.environment: development
```

### Java Config (상세 설정)

```java
@Configuration
public class TracingConfig {

    @Bean
    public Tracer tracer(MeterRegistry meterRegistry) {
        // Micrometer Tracing을 통한 Tracer 생성
        return new DefaultTracer(
                new OtelCurrentTraceContext(),
                event -> {},
                new BaggageManager() {}
        );
    }

    // Span 커스터마이징
    @Bean
    public SpanCustomizer spanCustomizer(Tracer tracer) {
        return tracer.currentSpan();
    }
}
```

---

## 5. 코드에서 추적 구현

### 자동 계측

Spring Boot 3.x에서는 대부분의 계측이 자동으로 적용됩니다.

```java
// 자동으로 추적되는 항목:
// - HTTP 요청/응답 (RestTemplate, WebClient, RestClient)
// - JDBC 쿼리
// - @Async 메서드
// - @Scheduled 메서드
// - Kafka 메시지
// - Redis 명령

@RestController
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping("/orders/{id}")
    public OrderResponse getOrder(@PathVariable Long id) {
        // 자동으로 Span 생성됨
        // Span Name: "GET /orders/{id}"
        return orderService.findById(id);
    }
}
```

### 수동 Span 생성

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final Tracer tracer;
    private final OrderRepository orderRepository;
    private final StockClient stockClient;

    public OrderResponse createOrder(CreateOrderRequest request) {
        // 수동으로 Span 생성
        Span span = tracer.nextSpan().name("OrderService.createOrder");

        try (Tracer.SpanInScope ws = tracer.withSpan(span.start())) {
            // Span에 속성 추가
            span.tag("order.customerId", request.getCustomerId().toString());
            span.tag("order.itemCount", String.valueOf(request.getItems().size()));

            // 비즈니스 로직
            Order order = processOrder(request);

            // 이벤트 기록
            span.event("order.created");

            return OrderResponse.from(order);

        } catch (Exception e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    // 더 간단한 방식: @NewSpan 어노테이션
    @NewSpan("validateOrder")
    public void validateOrder(@SpanTag("orderId") Long orderId) {
        // 자동으로 Span 생성됨
        // 메서드 파라미터가 태그로 추가됨
    }
}
```

### 비동기 작업 추적

```java
@Service
@RequiredArgsConstructor
public class AsyncOrderService {

    private final Tracer tracer;
    private final AsyncTaskExecutor taskExecutor;

    public CompletableFuture<OrderResponse> createOrderAsync(CreateOrderRequest request) {
        // 현재 Span 컨텍스트 캡처
        Span currentSpan = tracer.currentSpan();

        return CompletableFuture.supplyAsync(() -> {
            // 비동기 작업에서 컨텍스트 복원
            try (Tracer.SpanInScope ws = tracer.withSpan(currentSpan)) {
                Span asyncSpan = tracer.nextSpan().name("async-createOrder").start();

                try (Tracer.SpanInScope asyncWs = tracer.withSpan(asyncSpan)) {
                    return doCreateOrder(request);
                } finally {
                    asyncSpan.end();
                }
            }
        }, taskExecutor);
    }
}
```

### HTTP 클라이언트 추적

```java
@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        // RestClient는 자동으로 추적됨
        return builder
                .baseUrl("http://stock-service:8080")
                .build();
    }

    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        // WebClient도 자동으로 추적됨
        return builder
                .baseUrl("http://payment-service:8080")
                .build();
    }
}
```

```java
@Service
@RequiredArgsConstructor
public class StockClient {

    private final RestClient restClient;

    public StockResponse reserveStock(Long productId, int quantity) {
        // 자동으로 Span 생성 및 Context Propagation
        // traceparent 헤더가 자동으로 추가됨
        return restClient.post()
                .uri("/stock/reserve")
                .body(new StockReserveRequest(productId, quantity))
                .retrieve()
                .body(StockResponse.class);
    }
}
```

---

## 6. Zipkin 설정

### Docker로 Zipkin 실행

```yaml
# docker-compose.yml
version: '3.8'
services:
  zipkin:
    image: openzipkin/zipkin:latest
    container_name: zipkin
    ports:
      - "9411:9411"
    environment:
      - STORAGE_TYPE=mem  # 또는 elasticsearch, mysql
```

```bash
# 단일 실행
docker run -d -p 9411:9411 openzipkin/zipkin
```

### Zipkin UI 사용

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Zipkin UI 화면                                │
│                                                                      │
│   http://localhost:9411                                             │
│                                                                      │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │  Find Traces                                                 │   │
│   │                                                              │   │
│   │  Service: [order-service ▼]  Span Name: [all ▼]             │   │
│   │                                                              │   │
│   │  Tags:    [                                        ]         │   │
│   │                                                              │   │
│   │  [Search] [Clear]                                           │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │  Traces                                                      │   │
│   │                                                              │   │
│   │  Trace ID        Service         Duration   Spans  Time     │   │
│   │  ─────────────────────────────────────────────────────────  │   │
│   │  abc123...       order-service   2.5s       8      10:30    │   │
│   │  def456...       order-service   0.8s       5      10:29    │   │
│   │  ghi789...       order-service   5.2s       12     10:28    │   │
│   │                                  ↑                           │   │
│   │                              느린 요청!                       │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│   Trace 클릭 시 상세 타임라인 확인 가능                              │
└─────────────────────────────────────────────────────────────────────┘
```

### Zipkin과 영속 저장소 연동

```yaml
# docker-compose.yml - Elasticsearch 사용
version: '3.8'
services:
  zipkin:
    image: openzipkin/zipkin:latest
    ports:
      - "9411:9411"
    environment:
      - STORAGE_TYPE=elasticsearch
      - ES_HOSTS=http://elasticsearch:9200
    depends_on:
      - elasticsearch

  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.11.0
    environment:
      - discovery.type=single-node
      - xpack.security.enabled=false
    ports:
      - "9200:9200"
```

---

## 7. Kafka 메시지 추적

### Kafka Producer 추적

```java
@Service
@RequiredArgsConstructor
public class OrderEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Tracer tracer;
    private final ObjectMapper objectMapper;

    public void publishOrderCreated(OrderCreatedEvent event) {
        // 현재 Trace 컨텍스트가 자동으로 Kafka 메시지 헤더에 포함됨
        try {
            String payload = objectMapper.writeValueAsString(event);

            kafkaTemplate.send("order-events", event.getOrderId(), payload)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            tracer.currentSpan().error(ex);
                        }
                    });

        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
```

### Kafka Consumer 추적

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventConsumer {

    private final InventoryService inventoryService;
    private final Tracer tracer;

    @KafkaListener(topics = "order-events", groupId = "inventory-service")
    public void handleOrderEvent(ConsumerRecord<String, String> record) {
        // Kafka 메시지 헤더에서 Trace 컨텍스트가 자동으로 추출됨
        // 부모 Trace와 연결된 새로운 Span이 생성됨

        Span span = tracer.currentSpan();
        span.tag("kafka.topic", record.topic());
        span.tag("kafka.partition", String.valueOf(record.partition()));
        span.tag("kafka.offset", String.valueOf(record.offset()));

        try {
            OrderCreatedEvent event = parseEvent(record.value());
            inventoryService.reserveStock(event);

        } catch (Exception e) {
            span.error(e);
            throw e;
        }
    }
}
```

---

## 8. Best Practices

### 샘플링 전략

```yaml
# application.yml
management:
  tracing:
    sampling:
      # 개발 환경: 100%
      probability: 1.0

---
spring:
  config:
    activate:
      on-profile: production

management:
  tracing:
    sampling:
      # 운영 환경: 10%
      probability: 0.1
```

### 의미 있는 Span 이름

```java
// 좋은 예
@NewSpan("OrderService.createOrder")
@NewSpan("PaymentGateway.processPayment")
@NewSpan("InventoryService.reserveStock")

// 나쁜 예
@NewSpan("process")
@NewSpan("doSomething")
@NewSpan("method1")
```

### 유용한 태그 추가

```java
public OrderResponse createOrder(CreateOrderRequest request) {
    Span span = tracer.currentSpan();

    // 비즈니스 컨텍스트
    span.tag("order.customerId", request.getCustomerId().toString());
    span.tag("order.totalAmount", request.getTotalAmount().toString());
    span.tag("order.itemCount", String.valueOf(request.getItems().size()));

    // 시스템 정보
    span.tag("server.instance", serverInstanceId);
    span.tag("db.shard", determineShardId(request.getCustomerId()));

    // ...
}
```

### 에러 추적

```java
try {
    // 비즈니스 로직
} catch (BusinessException e) {
    Span span = tracer.currentSpan();
    span.tag("error", "true");
    span.tag("error.type", e.getClass().getSimpleName());
    span.tag("error.message", e.getMessage());
    span.event("business-error");
    throw e;
} catch (Exception e) {
    Span span = tracer.currentSpan();
    span.error(e);  // 전체 스택트레이스 기록
    throw e;
}
```

---

## 9. 실습 과제

### 과제 1: 기본 추적 설정
1. OpenTelemetry 의존성 추가
2. Zipkin 연동 설정
3. Docker로 Zipkin 실행
4. 기본 추적 동작 확인

### 과제 2: 수동 Span 생성
1. @NewSpan 어노테이션 사용
2. Tracer를 이용한 수동 Span 생성
3. 태그와 이벤트 추가
4. 에러 추적 구현

### 과제 3: 서비스 간 추적
1. 두 개 이상의 서비스 준비
2. HTTP 통신 추적 확인
3. Kafka 메시지 추적 확인
4. Zipkin에서 전체 Trace 확인

### 과제 4: 고급 설정
1. 샘플링 전략 설정
2. 커스텀 태그 추가
3. 배포 환경별 설정

### 체크리스트
```
[ ] OpenTelemetry/Micrometer 의존성 설정
[ ] Zipkin 연동 설정
[ ] Docker로 Zipkin 실행
[ ] 자동 계측 동작 확인
[ ] @NewSpan 어노테이션 사용
[ ] 수동 Span 생성 구현
[ ] 태그 및 이벤트 추가
[ ] 서비스 간 Context Propagation 확인
[ ] Kafka 메시지 추적 확인
[ ] 에러 추적 구현
```

---

## 참고 자료

- [OpenTelemetry 공식 문서](https://opentelemetry.io/docs/)
- [Micrometer Tracing](https://micrometer.io/docs/tracing)
- [Spring Boot Observability](https://docs.spring.io/spring-boot/reference/actuator/observability.html)
- [Zipkin 공식 문서](https://zipkin.io/)
- [W3C Trace Context](https://www.w3.org/TR/trace-context/)

---

## 다음 단계

[06-prometheus-grafana.md](./06-prometheus-grafana.md) - 메트릭 모니터링으로 이동
