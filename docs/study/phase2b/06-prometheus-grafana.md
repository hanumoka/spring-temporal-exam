# 메트릭 모니터링 - Prometheus와 Grafana

> **버전 참고** (2026년 1월 기준)
>
> | 컴포넌트 | 권장 버전 |
> |----------|-----------|
> | Prometheus | v3.9.x |
> | Grafana | 12.3.x |
>
> 이 문서의 Docker 이미지 버전은 학습 목적으로 작성되었습니다.
> 최신 버전은 [TECH-STACK.md](../../architecture/TECH-STACK.md) 참조

## 이 문서에서 배우는 것

- 메트릭(Metrics)의 개념과 종류
- Prometheus의 아키텍처와 동작 원리
- Spring Boot Actuator와 Micrometer
- Prometheus 메트릭 수집 설정
- Grafana 대시보드 구성
- 커스텀 메트릭 생성

---

## 1. 메트릭이란?

### 모니터링의 세 가지 축

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Observability의 세 가지 축                        │
│                                                                      │
│   ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐    │
│   │    Metrics      │  │     Logs        │  │     Traces      │    │
│   │    (메트릭)      │  │     (로그)       │  │     (추적)       │    │
│   │                 │  │                 │  │                 │    │
│   │  무엇이 발생?    │  │  왜 발생?       │  │  어디서 발생?    │    │
│   │  (What)         │  │  (Why)          │  │  (Where)        │    │
│   │                 │  │                 │  │                 │    │
│   │  • CPU 사용률   │  │  • 에러 메시지   │  │  • 요청 경로     │    │
│   │  • 메모리 사용량 │  │  • 스택 트레이스 │  │  • 서비스 호출   │    │
│   │  • 요청 수      │  │  • 디버그 정보   │  │  • 지연 시간     │    │
│   │  • 에러 비율    │  │                 │  │                 │    │
│   │                 │  │                 │  │                 │    │
│   │  Prometheus     │  │  Loki           │  │  Zipkin/Jaeger  │    │
│   │  + Grafana      │  │  + Grafana      │  │  + Zipkin UI    │    │
│   └─────────────────┘  └─────────────────┘  └─────────────────┘    │
│                                                                      │
│   이 문서에서는 Metrics를 다룹니다                                    │
└─────────────────────────────────────────────────────────────────────┘
```

### 메트릭 유형

```
┌─────────────────────────────────────────────────────────────────────┐
│                       메트릭 유형                                    │
│                                                                      │
│   1. Counter (카운터)                                               │
│      • 단조 증가하는 값                                              │
│      • 예: 총 요청 수, 에러 발생 횟수                                 │
│                                                                      │
│      http_requests_total{method="GET", status="200"} = 1234         │
│                                                                      │
│   ─────────────────────────────────────────────────────────────     │
│                                                                      │
│   2. Gauge (게이지)                                                 │
│      • 증가하거나 감소할 수 있는 값                                   │
│      • 예: 현재 메모리 사용량, 활성 연결 수                           │
│                                                                      │
│      jvm_memory_used_bytes{area="heap"} = 52428800                  │
│                                                                      │
│   ─────────────────────────────────────────────────────────────     │
│                                                                      │
│   3. Histogram (히스토그램)                                         │
│      • 값의 분포를 버킷으로 관찰                                      │
│      • 예: 응답 시간 분포                                            │
│                                                                      │
│      http_request_duration_seconds_bucket{le="0.1"} = 500           │
│      http_request_duration_seconds_bucket{le="0.5"} = 800           │
│      http_request_duration_seconds_bucket{le="1.0"} = 950           │
│      http_request_duration_seconds_bucket{le="+Inf"} = 1000         │
│                                                                      │
│   ─────────────────────────────────────────────────────────────     │
│                                                                      │
│   4. Summary (서머리)                                               │
│      • 클라이언트 측에서 분위수 계산                                  │
│      • 예: p50, p90, p99 응답 시간                                   │
│                                                                      │
│      http_request_duration_seconds{quantile="0.5"} = 0.05           │
│      http_request_duration_seconds{quantile="0.9"} = 0.12           │
│      http_request_duration_seconds{quantile="0.99"} = 0.35          │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 2. Prometheus 아키텍처

### 전체 아키텍처

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Prometheus 아키텍처                               │
│                                                                      │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                      Prometheus Server                       │   │
│   │   ┌─────────────────────────────────────────────────────┐   │   │
│   │   │                    Retrieval                         │   │   │
│   │   │   (Pull 방식으로 메트릭 수집)                        │   │   │
│   │   └──────────────────────┬──────────────────────────────┘   │   │
│   │                          │                                   │   │
│   │   ┌─────────────────┐    │    ┌─────────────────┐          │   │
│   │   │   Service       │    │    │  Push Gateway   │          │   │
│   │   │   Discovery     │    │    │  (Short-lived   │          │   │
│   │   │   (K8s, DNS)    │    │    │   jobs용)       │          │   │
│   │   └─────────────────┘    │    └─────────────────┘          │   │
│   │                          │                                   │   │
│   │   ┌──────────────────────▼──────────────────────────────┐   │   │
│   │   │                   TSDB (Storage)                     │   │   │
│   │   │         시계열 데이터베이스                          │   │   │
│   │   └──────────────────────┬──────────────────────────────┘   │   │
│   │                          │                                   │   │
│   │   ┌──────────────────────▼──────────────────────────────┐   │   │
│   │   │                  PromQL Engine                       │   │   │
│   │   │            쿼리 언어 처리                            │   │   │
│   │   └──────────────────────┬──────────────────────────────┘   │   │
│   │                          │                                   │   │
│   └──────────────────────────┼──────────────────────────────────┘   │
│                              │                                      │
│              ┌───────────────┼───────────────┐                     │
│              ▼               ▼               ▼                     │
│   ┌─────────────────┐ ┌─────────────┐ ┌─────────────────┐         │
│   │    Grafana      │ │ Alertmanager│ │   HTTP API      │         │
│   │  (시각화)       │ │  (알람)     │ │                 │         │
│   └─────────────────┘ └─────────────┘ └─────────────────┘         │
└─────────────────────────────────────────────────────────────────────┘

Pull 방식:
┌──────────────┐        ┌──────────────┐
│  Prometheus  │───────▶│  Application │
│              │  GET   │  /actuator/  │
│              │        │  prometheus  │
└──────────────┘        └──────────────┘
```

### Pull vs Push 방식

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Pull vs Push 방식 비교                            │
│                                                                      │
│   Pull 방식 (Prometheus 기본)                                       │
│   ┌────────────────────────────────────────────────────────────┐    │
│   │  장점:                                                     │    │
│   │  • 애플리케이션은 메트릭만 노출, 전송 로직 불필요           │    │
│   │  • Prometheus가 스케줄링 관리                              │    │
│   │  • 애플리케이션 장애 시에도 "up" 메트릭으로 감지 가능       │    │
│   │                                                            │    │
│   │  단점:                                                     │    │
│   │  • Short-lived job에 부적합                                │    │
│   │  • 네트워크 방화벽 문제 가능                               │    │
│   └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│   Push 방식 (Push Gateway 사용)                                     │
│   ┌────────────────────────────────────────────────────────────┐    │
│   │  사용 사례:                                                │    │
│   │  • 배치 작업                                               │    │
│   │  • 크론 잡                                                 │    │
│   │  • Lambda/서버리스                                         │    │
│   └────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 3. Spring Boot 연동

### 의존성 설정

```groovy
// build.gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'io.micrometer:micrometer-registry-prometheus'
}
```

### 설정 파일

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus, metrics
  endpoint:
    prometheus:
      enabled: true
    health:
      show-details: always
  metrics:
    tags:
      application: ${spring.application.name}
      environment: ${ENVIRONMENT:local}
    distribution:
      percentiles-histogram:
        http.server.requests: true
      percentiles:
        http.server.requests: 0.5, 0.9, 0.95, 0.99
      slo:
        http.server.requests: 100ms, 500ms, 1s, 5s

spring:
  application:
    name: order-service
```

### 메트릭 엔드포인트 확인

```bash
# 메트릭 엔드포인트 확인
curl http://localhost:8080/actuator/prometheus

# 출력 예시
# HELP jvm_memory_used_bytes The amount of used memory
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{area="heap",id="G1 Eden Space",} 4.194304E7
jvm_memory_used_bytes{area="heap",id="G1 Old Gen",} 1.048576E7

# HELP http_server_requests_seconds
# TYPE http_server_requests_seconds histogram
http_server_requests_seconds_bucket{method="GET",uri="/api/orders",status="200",le="0.1",} 45.0
http_server_requests_seconds_bucket{method="GET",uri="/api/orders",status="200",le="0.5",} 48.0
```

---

## 4. 커스텀 메트릭 생성

### Counter 메트릭

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final MeterRegistry meterRegistry;
    private final Counter orderCreatedCounter;
    private final Counter orderFailedCounter;

    @PostConstruct
    public void initMetrics() {
        orderCreatedCounter = Counter.builder("orders.created.total")
                .description("Total number of orders created")
                .tag("service", "order-service")
                .register(meterRegistry);

        orderFailedCounter = Counter.builder("orders.failed.total")
                .description("Total number of failed orders")
                .tag("service", "order-service")
                .register(meterRegistry);
    }

    public OrderResponse createOrder(CreateOrderRequest request) {
        try {
            Order order = processOrder(request);

            // 성공 카운터 증가
            orderCreatedCounter.increment();

            // 태그별 카운터
            meterRegistry.counter("orders.created",
                    "type", order.getType().name(),
                    "region", order.getRegion()
            ).increment();

            return OrderResponse.from(order);

        } catch (Exception e) {
            // 실패 카운터 증가
            orderFailedCounter.increment();
            meterRegistry.counter("orders.failed",
                    "reason", e.getClass().getSimpleName()
            ).increment();
            throw e;
        }
    }
}
```

### Gauge 메트릭

```java
@Component
@RequiredArgsConstructor
public class QueueMetrics {

    private final MeterRegistry meterRegistry;
    private final OrderQueue orderQueue;

    @PostConstruct
    public void initMetrics() {
        // Gauge: 현재 대기열 크기
        Gauge.builder("order.queue.size", orderQueue, OrderQueue::size)
                .description("Current size of order queue")
                .tag("service", "order-service")
                .register(meterRegistry);

        // Gauge: 처리 대기 중인 주문 수
        Gauge.builder("order.pending.count", this, QueueMetrics::getPendingCount)
                .description("Number of pending orders")
                .register(meterRegistry);
    }

    private double getPendingCount() {
        return orderQueue.getPendingOrders().size();
    }
}
```

### Timer 메트릭

```java
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final MeterRegistry meterRegistry;
    private final Timer paymentProcessTimer;

    @PostConstruct
    public void initMetrics() {
        paymentProcessTimer = Timer.builder("payment.process.duration")
                .description("Time taken to process payment")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .publishPercentileHistogram()
                .sla(Duration.ofMillis(100), Duration.ofMillis(500), Duration.ofSeconds(1))
                .register(meterRegistry);
    }

    public PaymentResult processPayment(PaymentRequest request) {
        return paymentProcessTimer.record(() -> {
            // 결제 처리 로직
            return doProcessPayment(request);
        });
    }

    // 또는 수동 타이밍
    public PaymentResult processPaymentManual(PaymentRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            PaymentResult result = doProcessPayment(request);
            sample.stop(meterRegistry.timer("payment.process.duration",
                    "status", "success",
                    "method", request.getMethod().name()));
            return result;
        } catch (Exception e) {
            sample.stop(meterRegistry.timer("payment.process.duration",
                    "status", "failed",
                    "error", e.getClass().getSimpleName()));
            throw e;
        }
    }
}
```

### @Timed 어노테이션

```java
@Service
public class ProductService {

    // 메서드 실행 시간 자동 측정
    @Timed(value = "product.find.duration",
           description = "Time to find product",
           percentiles = {0.5, 0.9, 0.99})
    public Product findById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    @Timed(value = "product.search.duration",
           extraTags = {"operation", "search"})
    public List<Product> search(String keyword) {
        return productRepository.findByNameContaining(keyword);
    }
}
```

```java
// @Timed 활성화를 위한 설정
@Configuration
public class MetricsConfig {

    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }
}
```

---

## 5. Prometheus 설정

### Docker Compose 설정

```yaml
# docker-compose.yml
version: '3.8'

services:
  prometheus:
    image: prom/prometheus:v2.48.0
    container_name: prometheus
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus_data:/prometheus
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.path=/prometheus'
      - '--web.console.libraries=/etc/prometheus/console_libraries'
      - '--web.console.templates=/etc/prometheus/consoles'
      - '--web.enable-lifecycle'

  grafana:
    image: grafana/grafana:10.2.0
    container_name: grafana
    ports:
      - "3000:3000"
    volumes:
      - grafana_data:/var/lib/grafana
      - ./grafana/provisioning:/etc/grafana/provisioning
    environment:
      - GF_SECURITY_ADMIN_USER=admin
      - GF_SECURITY_ADMIN_PASSWORD=admin

volumes:
  prometheus_data:
  grafana_data:
```

### Prometheus 설정 파일

```yaml
# prometheus/prometheus.yml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

alerting:
  alertmanagers:
    - static_configs:
        - targets:
          # - alertmanager:9093

rule_files:
  # - "alert.rules.yml"

scrape_configs:
  # Prometheus 자체 메트릭
  - job_name: 'prometheus'
    static_configs:
      - targets: ['localhost:9090']

  # Spring Boot 애플리케이션
  - job_name: 'order-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8080']
        labels:
          application: 'order-service'
          environment: 'development'

  - job_name: 'payment-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8081']
        labels:
          application: 'payment-service'
          environment: 'development'

  # Kubernetes Service Discovery (K8s 환경)
  # - job_name: 'kubernetes-pods'
  #   kubernetes_sd_configs:
  #     - role: pod
  #   relabel_configs:
  #     - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
  #       action: keep
  #       regex: true
```

---

## 6. PromQL 기본

### 기본 쿼리

```promql
# 현재 값 조회
http_server_requests_seconds_count

# 레이블 필터링
http_server_requests_seconds_count{status="200"}
http_server_requests_seconds_count{uri="/api/orders", method="POST"}

# 정규식 매칭
http_server_requests_seconds_count{uri=~"/api/.*"}
http_server_requests_seconds_count{status!~"5.."}
```

### 집계 함수

```promql
# 합계
sum(http_server_requests_seconds_count)

# 레이블별 합계
sum by (status) (http_server_requests_seconds_count)
sum by (uri, method) (http_server_requests_seconds_count)

# 평균
avg(jvm_memory_used_bytes{area="heap"})

# 최대/최소
max(http_server_requests_seconds_max)
min(http_server_requests_seconds_max)
```

### Rate와 Increase

```promql
# 초당 요청 수 (Rate)
rate(http_server_requests_seconds_count[5m])

# 레이블별 초당 요청 수
sum by (uri) (rate(http_server_requests_seconds_count[5m]))

# 5분간 총 증가량
increase(http_server_requests_seconds_count[5m])

# 에러율 계산
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
/
sum(rate(http_server_requests_seconds_count[5m]))
```

### Histogram 쿼리

```promql
# 평균 응답 시간
rate(http_server_requests_seconds_sum[5m])
/
rate(http_server_requests_seconds_count[5m])

# 95 퍼센타일 응답 시간
histogram_quantile(0.95,
  sum by (le) (rate(http_server_requests_seconds_bucket[5m]))
)

# URI별 99 퍼센타일
histogram_quantile(0.99,
  sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m]))
)
```

---

## 7. Grafana 대시보드

### 데이터 소스 설정

```yaml
# grafana/provisioning/datasources/prometheus.yml
apiVersion: 1

datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
```

### 대시보드 예시

```
┌─────────────────────────────────────────────────────────────────────┐
│                   Spring Boot 대시보드                               │
│                                                                      │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │  Service Health                                              │   │
│   │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐    │   │
│   │  │   UP     │  │  2.5k    │  │  0.3%    │  │  125ms   │    │   │
│   │  │  Status  │  │   RPS    │  │  Error   │  │   P99    │    │   │
│   │  └──────────┘  └──────────┘  └──────────┘  └──────────┘    │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│   ┌────────────────────────────┐  ┌────────────────────────────┐   │
│   │  Request Rate              │  │  Response Time             │   │
│   │  ╭──────────────────────╮  │  │  ╭──────────────────────╮  │   │
│   │  │    ╱╲    ╱╲         │  │  │  │  p50 ────────────    │  │   │
│   │  │ ──╱──╲──╱──╲────── │  │  │  │  p99 ╱╲  ╱╲        │  │   │
│   │  │╱        ╲          │  │  │  │     ╱  ╲╱  ╲────── │  │   │
│   │  ╰──────────────────────╯  │  │  ╰──────────────────────╯  │   │
│   └────────────────────────────┘  └────────────────────────────┘   │
│                                                                      │
│   ┌────────────────────────────┐  ┌────────────────────────────┐   │
│   │  JVM Memory                │  │  CPU Usage                 │   │
│   │  ╭──────────────────────╮  │  │  ╭──────────────────────╮  │   │
│   │  │ Heap ████████░░ 80%  │  │  │  │  ████████░░░░ 65%   │  │   │
│   │  │ Non  ██████░░░░ 60%  │  │  │  │                      │  │   │
│   │  ╰──────────────────────╯  │  │  ╰──────────────────────╯  │   │
│   └────────────────────────────┘  └────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### 대시보드 JSON 예시

```json
{
  "title": "Spring Boot Application",
  "panels": [
    {
      "title": "Request Rate",
      "type": "graph",
      "targets": [
        {
          "expr": "sum(rate(http_server_requests_seconds_count{application=\"$application\"}[1m]))",
          "legendFormat": "requests/sec"
        }
      ]
    },
    {
      "title": "Response Time (p99)",
      "type": "graph",
      "targets": [
        {
          "expr": "histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{application=\"$application\"}[5m])))",
          "legendFormat": "p99"
        }
      ]
    },
    {
      "title": "Error Rate",
      "type": "gauge",
      "targets": [
        {
          "expr": "sum(rate(http_server_requests_seconds_count{application=\"$application\",status=~\"5..\"}[5m])) / sum(rate(http_server_requests_seconds_count{application=\"$application\"}[5m])) * 100",
          "legendFormat": "error %"
        }
      ]
    },
    {
      "title": "JVM Heap Usage",
      "type": "graph",
      "targets": [
        {
          "expr": "jvm_memory_used_bytes{application=\"$application\",area=\"heap\"} / jvm_memory_max_bytes{application=\"$application\",area=\"heap\"} * 100",
          "legendFormat": "heap %"
        }
      ]
    }
  ]
}
```

---

## 8. 유용한 메트릭 패턴

### RED 메트릭 (Rate, Errors, Duration)

```java
@Component
@RequiredArgsConstructor
public class REDMetrics {

    private final MeterRegistry registry;

    // Rate: 초당 요청 수
    public void recordRequest(String endpoint) {
        registry.counter("app.requests.total",
                "endpoint", endpoint).increment();
    }

    // Errors: 에러 수
    public void recordError(String endpoint, String errorType) {
        registry.counter("app.errors.total",
                "endpoint", endpoint,
                "type", errorType).increment();
    }

    // Duration: 응답 시간
    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void stopTimer(Timer.Sample sample, String endpoint, String status) {
        sample.stop(registry.timer("app.request.duration",
                "endpoint", endpoint,
                "status", status));
    }
}
```

### USE 메트릭 (Utilization, Saturation, Errors)

```java
@Component
@RequiredArgsConstructor
public class USEMetrics {

    private final MeterRegistry registry;
    private final ThreadPoolTaskExecutor executor;

    @PostConstruct
    public void init() {
        // Utilization: 리소스 사용률
        Gauge.builder("executor.utilization", executor,
                e -> (double) e.getActiveCount() / e.getMaxPoolSize())
                .register(registry);

        // Saturation: 대기열 크기
        Gauge.builder("executor.queue.size", executor,
                e -> e.getThreadPoolExecutor().getQueue().size())
                .register(registry);
    }

    // Errors: 거부된 작업 수
    public void recordRejection() {
        registry.counter("executor.rejected.total").increment();
    }
}
```

### 비즈니스 메트릭

```java
@Component
@RequiredArgsConstructor
public class BusinessMetrics {

    private final MeterRegistry registry;

    public void recordOrderCreated(Order order) {
        // 주문 금액 분포
        registry.summary("order.amount",
                "type", order.getType().name())
                .record(order.getTotalAmount().doubleValue());

        // 상품별 주문 수
        for (OrderItem item : order.getItems()) {
            registry.counter("order.items.total",
                    "product", item.getProductId().toString(),
                    "category", item.getCategory())
                    .increment(item.getQuantity());
        }
    }

    public void recordPaymentProcessed(Payment payment) {
        registry.counter("payment.processed.total",
                "method", payment.getMethod().name(),
                "status", payment.getStatus().name())
                .increment();

        registry.summary("payment.amount",
                "method", payment.getMethod().name())
                .record(payment.getAmount().doubleValue());
    }
}
```

---

## 9. 실습 과제

### 과제 1: 기본 메트릭 설정
1. Spring Boot Actuator 의존성 추가
2. Prometheus 메트릭 엔드포인트 활성화
3. /actuator/prometheus 확인

### 과제 2: Prometheus + Grafana 설정
1. Docker Compose로 Prometheus, Grafana 실행
2. Prometheus scrape 설정
3. Grafana 데이터소스 연결

### 과제 3: 커스텀 메트릭 구현
1. Counter: 주문 생성 수
2. Gauge: 현재 대기 주문 수
3. Timer: 주문 처리 시간
4. @Timed 어노테이션 사용

### 과제 4: 대시보드 구성
1. RPS (Requests Per Second) 패널
2. 응답 시간 (p50, p99) 패널
3. 에러율 패널
4. JVM 메모리 패널

### 체크리스트
```
[ ] Actuator + Micrometer Prometheus 의존성 추가
[ ] Prometheus 메트릭 엔드포인트 확인
[ ] Docker로 Prometheus 실행
[ ] Docker로 Grafana 실행
[ ] Prometheus scrape 설정
[ ] 커스텀 Counter 메트릭 생성
[ ] 커스텀 Gauge 메트릭 생성
[ ] 커스텀 Timer 메트릭 생성
[ ] PromQL 쿼리 작성
[ ] Grafana 대시보드 생성
```

---

## 참고 자료

- [Prometheus 공식 문서](https://prometheus.io/docs/)
- [Grafana 공식 문서](https://grafana.com/docs/)
- [Micrometer 공식 문서](https://micrometer.io/docs)
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/reference/actuator/)
- [PromQL 가이드](https://prometheus.io/docs/prometheus/latest/querying/basics/)

---

## 다음 단계

[07-loki.md](./07-loki.md) - 로그 수집으로 이동
