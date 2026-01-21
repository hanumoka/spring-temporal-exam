# 로그 수집 - Loki

## 이 문서에서 배우는 것

- 중앙 집중식 로깅의 필요성
- Loki의 아키텍처와 특징
- Promtail을 통한 로그 수집
- Spring Boot 로그 설정
- Grafana에서 로그 조회
- 로그와 메트릭/트레이스 연동

---

## 1. 중앙 집중식 로깅

### 분산 시스템의 로깅 문제

```
┌─────────────────────────────────────────────────────────────────────┐
│                  분산 시스템에서의 로그 문제                          │
│                                                                      │
│   문제: 각 서버에 흩어진 로그를 어떻게 통합 분석할까?                 │
│                                                                      │
│   ┌────────────┐  ┌────────────┐  ┌────────────┐                   │
│   │  Server 1  │  │  Server 2  │  │  Server 3  │                   │
│   │            │  │            │  │            │                   │
│   │  app.log   │  │  app.log   │  │  app.log   │                   │
│   │  ─────────  │  │  ─────────  │  │  ─────────  │                   │
│   │  10:01 ERR │  │  10:01 INFO│  │  10:02 ERR │                   │
│   │  10:02 INFO│  │  10:02 WARN│  │  10:03 INFO│                   │
│   │  10:03 ERR │  │  10:03 ERR │  │  10:04 WARN│                   │
│   └────────────┘  └────────────┘  └────────────┘                   │
│        │               │               │                            │
│        └───────────────┼───────────────┘                            │
│                        │                                            │
│                        ▼                                            │
│   문제점:                                                           │
│   • 각 서버에 SSH 접속하여 로그 확인?                                │
│   • 관련 로그를 어떻게 연결?                                         │
│   • 로그 검색이 느림                                                 │
│   • 서버 디스크 용량 관리                                            │
│                                                                      │
│   해결책: 중앙 집중식 로깅                                           │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                     Log Aggregator (Loki)                    │   │
│   │                                                              │   │
│   │   모든 로그를 한 곳에서 검색, 분석, 알림                      │   │
│   └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### Loki vs ELK Stack

```
┌─────────────────────────────────────────────────────────────────────┐
│                     Loki vs ELK Stack 비교                          │
│                                                                      │
│   ┌─────────────────────────────┐  ┌─────────────────────────────┐  │
│   │          Loki               │  │        ELK Stack            │  │
│   │                             │  │                             │  │
│   │  • 레이블 기반 인덱싱       │  │  • 전문(Full-text) 인덱싱   │  │
│   │  • 낮은 리소스 사용         │  │  • 높은 리소스 사용         │  │
│   │  • Grafana와 네이티브 통합  │  │  • Kibana 필요              │  │
│   │  • 설정이 간단              │  │  • 설정이 복잡              │  │
│   │  • Prometheus 생태계        │  │  • 독자 생태계              │  │
│   │                             │  │                             │  │
│   │  적합한 경우:               │  │  적합한 경우:               │  │
│   │  • 이미 Prometheus 사용 중  │  │  • 복잡한 로그 분석 필요    │  │
│   │  • 리소스 제약이 있음       │  │  • 전문 검색이 많음         │  │
│   │  • 간단한 로그 조회         │  │  • 대규모 로그 분석         │  │
│   └─────────────────────────────┘  └─────────────────────────────┘  │
│                                                                      │
│   Loki의 철학: "Like Prometheus, but for logs"                       │
│   → 로그 내용을 인덱싱하지 않고, 레이블만 인덱싱                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 2. Loki 아키텍처

### 구성 요소

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Loki 아키텍처                                 │
│                                                                      │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                      Applications                            │   │
│   │   ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐       │   │
│   │   │  App 1  │  │  App 2  │  │  App 3  │  │  App 4  │       │   │
│   │   │  logs   │  │  logs   │  │  logs   │  │  logs   │       │   │
│   │   └────┬────┘  └────┬────┘  └────┬────┘  └────┬────┘       │   │
│   └────────┼────────────┼────────────┼────────────┼─────────────┘   │
│            │            │            │            │                 │
│            ▼            ▼            ▼            ▼                 │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                       Promtail                               │   │
│   │   • 로그 파일 수집                                           │   │
│   │   • 레이블 추가                                              │   │
│   │   • Loki로 전송                                              │   │
│   └─────────────────────────┬───────────────────────────────────┘   │
│                             │                                       │
│                             ▼                                       │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                         Loki                                 │   │
│   │                                                              │   │
│   │   ┌───────────────┐  ┌───────────────┐  ┌───────────────┐   │   │
│   │   │  Distributor  │  │    Ingester   │  │    Querier    │   │   │
│   │   │  (분배)       │  │  (저장)       │  │  (조회)       │   │   │
│   │   └───────────────┘  └───────────────┘  └───────────────┘   │   │
│   │                                                              │   │
│   │   ┌─────────────────────────────────────────────────────┐   │   │
│   │   │                Storage (S3, GCS, etc.)               │   │   │
│   │   │   • Index: 레이블 인덱스                             │   │   │
│   │   │   • Chunks: 로그 데이터                              │   │   │
│   │   └─────────────────────────────────────────────────────┘   │   │
│   └──────────────────────────┬──────────────────────────────────┘   │
│                              │                                      │
│                              ▼                                      │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                       Grafana                                │   │
│   │                    (로그 조회 UI)                            │   │
│   └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### 레이블 기반 인덱싱

```
┌─────────────────────────────────────────────────────────────────────┐
│                     Loki의 레이블 시스템                             │
│                                                                      │
│   로그 스트림 = 레이블 조합                                          │
│                                                                      │
│   {job="order-service", env="production", level="error"}            │
│   ├── 2024-01-15 10:00:01 Order failed: insufficient stock          │
│   ├── 2024-01-15 10:00:03 Payment timeout for order 123             │
│   └── 2024-01-15 10:00:05 Database connection lost                  │
│                                                                      │
│   {job="order-service", env="production", level="info"}             │
│   ├── 2024-01-15 10:00:00 Order created: 123                        │
│   ├── 2024-01-15 10:00:02 Order processed: 124                      │
│   └── 2024-01-15 10:00:04 Order shipped: 125                        │
│                                                                      │
│   ─────────────────────────────────────────────────────────────     │
│                                                                      │
│   인덱싱 대상: 레이블 (job, env, level)                              │
│   인덱싱 안함: 로그 내용                                             │
│                                                                      │
│   장점:                                                              │
│   • 인덱스 크기가 작음                                               │
│   • 저장 비용 절감                                                   │
│   • 높은 수집 처리량                                                 │
│                                                                      │
│   단점:                                                              │
│   • 전문 검색 느림 (grep과 유사)                                     │
│   • 레이블 선택이 중요                                               │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 3. Docker Compose 설정

### 전체 스택 설정

```yaml
# docker-compose.yml
version: '3.8'

services:
  loki:
    image: grafana/loki:2.9.0
    container_name: loki
    ports:
      - "3100:3100"
    volumes:
      - ./loki/loki-config.yml:/etc/loki/local-config.yaml
      - loki_data:/loki
    command: -config.file=/etc/loki/local-config.yaml

  promtail:
    image: grafana/promtail:2.9.0
    container_name: promtail
    volumes:
      - ./promtail/promtail-config.yml:/etc/promtail/config.yml
      - /var/log:/var/log:ro                          # 시스템 로그
      - ./logs:/app/logs:ro                           # 애플리케이션 로그
    command: -config.file=/etc/promtail/config.yml
    depends_on:
      - loki

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
    depends_on:
      - loki

volumes:
  loki_data:
  grafana_data:
```

### Loki 설정 파일

```yaml
# loki/loki-config.yml
auth_enabled: false

server:
  http_listen_port: 3100

common:
  path_prefix: /loki
  storage:
    filesystem:
      chunks_directory: /loki/chunks
      rules_directory: /loki/rules
  replication_factor: 1
  ring:
    kvstore:
      store: inmemory

schema_config:
  configs:
    - from: 2020-10-24
      store: boltdb-shipper
      object_store: filesystem
      schema: v11
      index:
        prefix: index_
        period: 24h

storage_config:
  boltdb_shipper:
    active_index_directory: /loki/boltdb-shipper-active
    cache_location: /loki/boltdb-shipper-cache
    cache_ttl: 24h
    shared_store: filesystem
  filesystem:
    directory: /loki/chunks

limits_config:
  reject_old_samples: true
  reject_old_samples_max_age: 168h  # 7일

compactor:
  working_directory: /loki/boltdb-shipper-compactor
  shared_store: filesystem
```

### Promtail 설정 파일

```yaml
# promtail/promtail-config.yml
server:
  http_listen_port: 9080
  grpc_listen_port: 0

positions:
  filename: /tmp/positions.yaml

clients:
  - url: http://loki:3100/loki/api/v1/push

scrape_configs:
  # Spring Boot 애플리케이션 로그
  - job_name: spring-boot
    static_configs:
      - targets:
          - localhost
        labels:
          job: spring-boot
          __path__: /app/logs/*.log

    pipeline_stages:
      # 멀티라인 로그 처리 (스택트레이스)
      - multiline:
          firstline: '^\d{4}-\d{2}-\d{2}'
          max_wait_time: 3s

      # JSON 로그 파싱
      - json:
          expressions:
            level: level
            logger: logger
            message: message
            traceId: traceId
            spanId: spanId
            timestamp: timestamp

      # 레이블 추가
      - labels:
          level:
          logger:
          traceId:

      # 타임스탬프 설정
      - timestamp:
          source: timestamp
          format: '2006-01-02T15:04:05.000Z07:00'

  # Docker 컨테이너 로그
  - job_name: containers
    static_configs:
      - targets:
          - localhost
        labels:
          job: containers
          __path__: /var/lib/docker/containers/*/*.log

    pipeline_stages:
      - json:
          expressions:
            output: log
            stream: stream
            time: time
      - output:
          source: output
```

---

## 4. Spring Boot 로그 설정

### 의존성 설정

```groovy
// build.gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter'

    // JSON 로그 포맷 (Logstash encoder)
    implementation 'net.logstash.logback:logstash-logback-encoder:7.4'
}
```

### Logback 설정 (JSON 포맷)

```xml
<!-- src/main/resources/logback-spring.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>

    <!-- JSON 포맷 (Loki/ELK용) -->
    <appender name="JSON" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/application.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/application.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>7</maxHistory>
        </rollingPolicy>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeMdcKeyName>traceId</includeMdcKeyName>
            <includeMdcKeyName>spanId</includeMdcKeyName>
            <includeMdcKeyName>userId</includeMdcKeyName>
            <customFields>{"service":"order-service","env":"${ENV:-local}"}</customFields>
        </encoder>
    </appender>

    <!-- 콘솔 출력 (개발용) -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId:-},%X{spanId:-}] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- 프로필별 설정 -->
    <springProfile name="local">
        <root level="INFO">
            <appender-ref ref="CONSOLE"/>
            <appender-ref ref="JSON"/>
        </root>
    </springProfile>

    <springProfile name="production">
        <root level="INFO">
            <appender-ref ref="JSON"/>
        </root>
    </springProfile>
</configuration>
```

### 구조화된 로깅

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    public OrderResponse createOrder(CreateOrderRequest request) {
        // 구조화된 로그 (MDC 사용)
        try (MDCCloseable ignored = MDC.putCloseable("orderId", request.getOrderId());
             MDCCloseable ignored2 = MDC.putCloseable("customerId", request.getCustomerId())) {

            log.info("Creating order started");

            // 비즈니스 로직
            Order order = processOrder(request);

            log.info("Order created successfully, totalAmount={}", order.getTotalAmount());

            return OrderResponse.from(order);

        } catch (InsufficientStockException e) {
            log.warn("Order failed due to insufficient stock, productId={}, requested={}, available={}",
                    e.getProductId(), e.getRequested(), e.getAvailable());
            throw e;

        } catch (Exception e) {
            log.error("Unexpected error while creating order", e);
            throw e;
        }
    }
}
```

### 로그 출력 예시 (JSON)

```json
{
  "@timestamp": "2024-01-15T10:30:00.123Z",
  "@version": "1",
  "message": "Order created successfully, totalAmount=150000",
  "logger_name": "com.example.OrderService",
  "thread_name": "http-nio-8080-exec-1",
  "level": "INFO",
  "level_value": 20000,
  "traceId": "abc123def456",
  "spanId": "789ghi",
  "orderId": "ORD-001",
  "customerId": "CUST-123",
  "service": "order-service",
  "env": "production"
}
```

---

## 5. LogQL 쿼리

### 기본 쿼리

```logql
# 레이블로 로그 선택
{job="order-service"}
{job="order-service", level="error"}
{job=~"order.*|payment.*"}

# 로그 내용 필터링
{job="order-service"} |= "error"
{job="order-service"} |~ "order.*failed"
{job="order-service"} != "health"
{job="order-service"} !~ "debug|trace"
```

### JSON 파싱

```logql
# JSON 필드 추출
{job="order-service"} | json

# 특정 필드로 필터링
{job="order-service"} | json | level="ERROR"
{job="order-service"} | json | orderId="ORD-001"

# 필드 값으로 집계
{job="order-service"} | json | level="ERROR" | line_format "{{.message}}"
```

### 메트릭 쿼리

```logql
# 로그 카운트 (에러 발생 횟수)
count_over_time({job="order-service", level="error"}[5m])

# 서비스별 에러 비율
sum by (job) (count_over_time({level="error"}[5m]))

# 초당 로그 라인 수
rate({job="order-service"}[1m])

# 에러 비율
sum(count_over_time({job="order-service", level="error"}[5m]))
/
sum(count_over_time({job="order-service"}[5m]))
```

### 패턴 파싱

```logql
# 정규식으로 필드 추출
{job="order-service"}
| pattern `<timestamp> <level> <logger> - Order <orderId> <status>`
| status="failed"

# 정규식 캡처 그룹
{job="order-service"}
| regexp `orderId=(?P<orderId>\w+)`
| orderId="ORD-001"
```

---

## 6. Grafana 로그 탐색기

### 데이터 소스 설정

```yaml
# grafana/provisioning/datasources/loki.yml
apiVersion: 1

datasources:
  - name: Loki
    type: loki
    access: proxy
    url: http://loki:3100
    jsonData:
      maxLines: 1000
      derivedFields:
        - datasourceUid: tempo
          matcherRegex: "traceId=(\\w+)"
          name: TraceID
          url: "$${__value.raw}"
```

### 대시보드에서 로그 패널

```
┌─────────────────────────────────────────────────────────────────────┐
│                     Grafana 로그 탐색기                              │
│                                                                      │
│   Query: {job="order-service"} |= "error"                           │
│                                                                      │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │  Time                   Log                                  │   │
│   │  ──────────────────────────────────────────────────────────  │   │
│   │  10:30:01.123          {"level":"ERROR","message":"Order     │   │
│   │                         failed: insufficient stock",          │   │
│   │                         "orderId":"ORD-001",...}             │   │
│   │                                                              │   │
│   │  10:30:03.456          {"level":"ERROR","message":"Payment   │   │
│   │                         timeout","orderId":"ORD-002",...}    │   │
│   │                                                              │   │
│   │  10:30:05.789          {"level":"ERROR","message":"Database  │   │
│   │                         connection failed",...}              │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│   [Show context]  [Copy]  [Explore]                                 │
│                                                                      │
│   Labels:                                                           │
│   job=order-service  level=error  traceId=abc123                   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 7. 로그-메트릭-트레이스 연동

### Trace ID로 연동

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Observability 연동                                │
│                                                                      │
│   1. 메트릭 알림 수신                                                │
│      → "order-service 에러율 5% 초과"                                │
│                                                                      │
│   2. Grafana 메트릭 대시보드에서 확인                                │
│      → 10:30경 에러 급증 확인                                        │
│                                                                      │
│   3. 로그 탐색                                                       │
│      → {job="order-service", level="error"} 쿼리                    │
│      → 특정 에러 로그 발견 (traceId=abc123)                          │
│                                                                      │
│   4. 트레이스 조회                                                   │
│      → Zipkin/Tempo에서 traceId=abc123 검색                         │
│      → 전체 요청 흐름 확인                                           │
│      → payment-service에서 2초 지연 발견                             │
│                                                                      │
│   5. 원인 파악                                                       │
│      → payment-service 로그 확인                                     │
│      → 외부 PG사 타임아웃 확인                                       │
└─────────────────────────────────────────────────────────────────────┘
```

### Grafana에서 연동 설정

```yaml
# 파생 필드 (Derived Fields) 설정
# Loki 데이터소스 설정에서

derivedFields:
  - name: TraceID
    matcherRegex: '"traceId":"(\w+)"'
    url: "http://zipkin:9411/zipkin/traces/${__value.raw}"
    datasourceUid: zipkin
```

---

## 8. 실전 패턴

### 에러 로그 알림

```yaml
# Loki Recording Rules
groups:
  - name: error-alerts
    rules:
      - alert: HighErrorRate
        expr: |
          sum(count_over_time({job="order-service", level="error"}[5m])) > 100
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "High error rate in order-service"
          description: "More than 100 errors in the last 5 minutes"
```

### 로그 수준별 필터링

```java
// 로그 수준 가이드라인
@Slf4j
public class LoggingGuideline {

    public void exampleLogging() {
        // ERROR: 즉각적인 조치가 필요한 심각한 문제
        log.error("Payment processing failed, orderId={}", orderId);

        // WARN: 잠재적 문제, 모니터링 필요
        log.warn("Retry attempt {} for external API call", retryCount);

        // INFO: 중요한 비즈니스 이벤트
        log.info("Order created successfully, orderId={}", orderId);

        // DEBUG: 개발/디버깅용 상세 정보
        log.debug("Processing item: {}", item);

        // TRACE: 매우 상세한 디버깅 정보
        log.trace("Method entry: processOrder({})", request);
    }
}
```

### 민감 정보 마스킹

```java
@Component
public class SensitiveDataMasker {

    public String maskCreditCard(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 4) {
            return "****";
        }
        return "**** **** **** " + cardNumber.substring(cardNumber.length() - 4);
    }

    public String maskEmail(String email) {
        if (email == null) return "***";
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) return "***";
        return email.charAt(0) + "***" + email.substring(atIndex);
    }
}

// 사용 예
log.info("Payment processed for card {}", masker.maskCreditCard(cardNumber));
// 출력: Payment processed for card **** **** **** 1234
```

---

## 9. 실습 과제

### 과제 1: Loki 스택 설정
1. Docker Compose로 Loki, Promtail, Grafana 실행
2. Promtail 설정 작성
3. Grafana 데이터소스 연결

### 과제 2: Spring Boot 로그 설정
1. JSON 로그 포맷 설정 (Logstash encoder)
2. MDC로 컨텍스트 정보 추가
3. 구조화된 로깅 적용

### 과제 3: LogQL 쿼리 작성
1. 기본 레이블 필터링
2. 로그 내용 검색
3. 메트릭 쿼리 (count, rate)
4. JSON 파싱 쿼리

### 과제 4: 대시보드 구성
1. 로그 스트림 패널
2. 에러 로그 카운트 패널
3. 서비스별 로그 분포 패널
4. TraceID 연동 설정

### 체크리스트
```
[ ] Docker로 Loki 실행
[ ] Docker로 Promtail 실행
[ ] Promtail 설정 (로그 경로, 레이블)
[ ] Spring Boot JSON 로그 설정
[ ] MDC 컨텍스트 추가 (traceId, userId)
[ ] Grafana Loki 데이터소스 연결
[ ] 기본 LogQL 쿼리 작성
[ ] 메트릭 LogQL 쿼리 작성
[ ] 로그 대시보드 생성
[ ] Trace 연동 설정
```

---

## 참고 자료

- [Grafana Loki 공식 문서](https://grafana.com/docs/loki/)
- [Promtail 설정](https://grafana.com/docs/loki/latest/clients/promtail/)
- [LogQL 문서](https://grafana.com/docs/loki/latest/logql/)
- [Logstash Logback Encoder](https://github.com/logfellow/logstash-logback-encoder)
- [Spring Boot 로깅](https://docs.spring.io/spring-boot/reference/features/logging.html)

---

## 다음 단계

[08-alertmanager.md](./08-alertmanager.md) - 알람으로 이동
