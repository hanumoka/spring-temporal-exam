# OpenTelemetry + Grafana Tempo (분산 추적)

## 개요

### What (무엇인가)
OpenTelemetry는 분산 시스템에서 Traces, Metrics, Logs를 수집하는 표준화된 프레임워크입니다. Grafana Tempo는 OpenTelemetry 표준을 지원하는 분산 추적 백엔드입니다.

### Why (왜 Tempo인가)

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Zipkin vs Grafana Tempo                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  항목          │  Zipkin             │  Grafana Tempo               │
│  ──────────────┼─────────────────────┼───────────────────────────── │
│  유지보수      │  자원봉사자          │  Grafana Labs 지원           │
│  확장성        │  소규모 적합         │  대규모 최적화               │
│  스토리지      │  다양한 백엔드       │  객체 스토리지 (S3, GCS)     │
│  통합          │  독립적              │  Grafana 스택 통합           │
│  UI            │  별도 UI             │  Grafana 대시보드            │
│  ──────────────┼─────────────────────┼───────────────────────────── │
│                                                                      │
│  선택: Grafana Tempo                                                │
│  ├── 이미 Prometheus + Grafana + Loki 사용                          │
│  ├── 단일 Grafana UI에서 Metrics + Logs + Traces 통합 조회          │
│  └── 비용 효율적인 객체 스토리지 사용                               │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 1. 아키텍처

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Observability 스택 아키텍처                       │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [Spring Boot Apps]                                                  │
│  ├── Micrometer (Metrics)  ──────────────→  [Prometheus]             │
│  ├── OpenTelemetry (Traces) ─────────────→  [Grafana Tempo]          │
│  └── Logback (Logs)  ────────────────────→  [Loki]                   │
│                                                                      │
│                              │                                       │
│                              ▼                                       │
│                     ┌─────────────────┐                             │
│                     │    Grafana      │                             │
│                     │  ┌───────────┐  │                             │
│                     │  │ Dashboard │  │                             │
│                     │  │           │  │                             │
│                     │  │ Metrics   │  │                             │
│                     │  │ Logs      │  │                             │
│                     │  │ Traces    │  │                             │
│                     │  └───────────┘  │                             │
│                     └─────────────────┘                             │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 2. 의존성 설정

### 2.1 build.gradle

```gradle
dependencies {
    // OpenTelemetry
    implementation 'io.opentelemetry:opentelemetry-api'
    implementation 'io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter'

    // Micrometer Tracing Bridge
    implementation 'io.micrometer:micrometer-tracing-bridge-otel'

    // OTLP Exporter (Tempo로 전송)
    implementation 'io.opentelemetry:opentelemetry-exporter-otlp'
}
```

### 2.2 application.yml

```yaml
spring:
  application:
    name: service-order

management:
  tracing:
    sampling:
      probability: 1.0  # 개발: 100%, 프로덕션: 0.1~0.5

  otlp:
    tracing:
      endpoint: http://localhost:4318/v1/traces

  endpoints:
    web:
      exposure:
        include: health,info,prometheus

logging:
  pattern:
    level: "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]"
```

---

## 3. Docker Compose 설정

### 3.1 Tempo 추가

```yaml
# docker-compose.yml
services:
  # 기존 서비스들...

  tempo:
    image: grafana/tempo:2.3.0
    container_name: tempo
    command: ["-config.file=/etc/tempo.yaml"]
    volumes:
      - ./tempo/tempo.yaml:/etc/tempo.yaml
      - tempo-data:/var/tempo
    ports:
      - "3200:3200"   # Tempo UI
      - "4317:4317"   # OTLP gRPC
      - "4318:4318"   # OTLP HTTP
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:3200/ready"]
      interval: 10s
      timeout: 5s
      retries: 5

  grafana:
    image: grafana/grafana:10.2.0
    container_name: grafana
    ports:
      - "3000:3000"
    environment:
      - GF_AUTH_ANONYMOUS_ENABLED=true
      - GF_AUTH_ANONYMOUS_ORG_ROLE=Admin
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning
      - grafana-data:/var/lib/grafana
    depends_on:
      - prometheus
      - tempo
      - loki

volumes:
  tempo-data:
  grafana-data:
```

### 3.2 Tempo 설정 파일

```yaml
# tempo/tempo.yaml
server:
  http_listen_port: 3200

distributor:
  receivers:
    otlp:
      protocols:
        grpc:
          endpoint: 0.0.0.0:4317
        http:
          endpoint: 0.0.0.0:4318

storage:
  trace:
    backend: local
    local:
      path: /var/tempo/traces

compactor:
  compaction:
    block_retention: 48h
```

### 3.3 Grafana 데이터소스

```yaml
# grafana/provisioning/datasources/datasources.yml
apiVersion: 1

datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090

  - name: Tempo
    type: tempo
    access: proxy
    url: http://tempo:3200
    jsonData:
      tracesToLogsV2:
        datasourceUid: loki
        tags: ['service.name']
      tracesToMetrics:
        datasourceUid: prometheus
      serviceMap:
        datasourceUid: prometheus

  - name: Loki
    type: loki
    access: proxy
    url: http://loki:3100
```

---

## 4. 코드 구현

### 4.1 Trace 자동 전파

Spring Boot 3.x + Micrometer Tracing은 자동으로 트레이스를 전파합니다.

```java
@RestController
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;

    @PostMapping("/api/orders")
    public ApiResponse<OrderResponse> createOrder(@RequestBody CreateOrderRequest request) {
        // traceId, spanId 자동 포함
        log.info("주문 생성 요청: customerId={}", request.getCustomerId());

        Order order = orderService.createOrder(request);

        return ApiResponse.success(OrderResponse.from(order));
    }
}
```

### 4.2 RestClient 트레이스 전파

```java
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder(ObservationRegistry observationRegistry) {
        return RestClient.builder()
            .observationRegistry(observationRegistry);  // 자동 트레이스 전파
    }

    @Bean
    public RestClient orderServiceClient(RestClient.Builder builder) {
        return builder
            .baseUrl("http://localhost:8081")
            .build();
    }
}
```

### 4.3 커스텀 Span 생성

```java
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final Tracer tracer;  // io.micrometer.tracing.Tracer
    private final PaymentGateway paymentGateway;

    public PaymentResult processPayment(PaymentRequest request) {
        // 커스텀 Span 생성
        Span span = tracer.nextSpan().name("payment-gateway-call");

        try (Tracer.SpanInScope ws = tracer.withSpan(span.start())) {
            span.tag("payment.amount", String.valueOf(request.getAmount()));
            span.tag("payment.method", request.getMethod());

            PaymentResult result = paymentGateway.process(request);

            span.tag("payment.transactionId", result.getTransactionId());
            return result;

        } catch (Exception e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
```

---

## 5. Grafana에서 트레이스 조회

### 5.1 Service Map

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Grafana Service Map                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [orchestrator-pure]                                                 │
│         │                                                            │
│         ├──→ [service-order]     (avg: 45ms, err: 0.1%)             │
│         │                                                            │
│         ├──→ [service-inventory] (avg: 32ms, err: 0.5%)             │
│         │                                                            │
│         └──→ [service-payment]   (avg: 520ms, err: 2.1%)  ← 병목!   │
│                     │                                                │
│                     └──→ [fake-pg]                                   │
│                                                                      │
│  서비스 간 호출 관계와 지연 시간 한눈에 파악                         │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 5.2 Trace 상세 조회

```
┌─────────────────────────────────────────────────────────────────────┐
│  Trace: 3f2a8b1c9d0e4f5a6b7c8d9e0f1a2b3c                           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  orchestrator-pure POST /api/saga/order ───────────────────── 890ms │
│  │                                                                   │
│  ├── service-order POST /api/orders ─────────────────────────  45ms │
│  │                                                                   │
│  ├── service-inventory POST /api/inventory/reserve ──────────  32ms │
│  │                                                                   │
│  ├── service-payment POST /api/payments ─────────────────────  520ms│
│  │   │                                                               │
│  │   └── payment-gateway-call ──────────────────────────────  510ms │
│  │       └── Tags: amount=10000, method=CARD                         │
│  │                                                                   │
│  ├── service-order PUT /api/orders/{id}/confirm ─────────────  42ms │
│  │                                                                   │
│  ├── service-inventory POST /api/inventory/confirm ──────────  28ms │
│  │                                                                   │
│  └── service-payment PUT /api/payments/{id}/confirm ─────────  23ms │
│                                                                      │
│  Logs (연결됨):                                                      │
│  ├── 10:32:15.123 [orchestrator] Saga 시작: SAGA-001                │
│  ├── 10:32:15.168 [service-order] 주문 생성: ORD-001                │
│  └── ...                                                            │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 5.3 Logs ↔ Traces 연동

```yaml
# application.yml - 로그에 traceId 포함
logging:
  pattern:
    level: "%5p [${spring.application.name},%X{traceId},%X{spanId}]"
```

```
로그 출력:
INFO [service-order,3f2a8b1c9d0e4f5a,6b7c8d9e0f1a2b3c] 주문 생성 완료

Grafana에서 traceId 클릭 → 전체 Trace 조회
```

---

## 6. 핵심 학습 포인트

### 6.1 분산 추적의 가치

```
┌─────────────────────────────────────────────────────────────────────┐
│                    분산 추적 핵심 가치                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  1. 병목 구간 식별                                                   │
│     └── 어느 서비스가 느린지 한눈에 파악                            │
│                                                                      │
│  2. 오류 추적                                                        │
│     └── 에러 발생 위치와 전파 경로 추적                             │
│                                                                      │
│  3. 서비스 의존성 파악                                               │
│     └── Service Map으로 전체 아키텍처 시각화                        │
│                                                                      │
│  4. 로그 연관                                                        │
│     └── traceId로 분산된 로그 통합 조회                             │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 6.2 Temporal과의 통합

```
Temporal + OpenTelemetry:
├── Temporal Worker에 OpenTelemetry 자동 통합
├── Workflow/Activity 실행이 자동으로 Span 생성
└── Temporal UI + Grafana Tempo 상호 보완
```

---

## 관련 문서

- [D024 분산 추적 현대화](../../architecture/DECISIONS.md#d024-분산-추적-현대화)
- [06-prometheus-grafana.md](./06-prometheus-grafana.md)
- [07-loki.md](./07-loki.md)
