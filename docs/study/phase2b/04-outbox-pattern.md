# Outbox 패턴 - 이벤트 발행 신뢰성

## 이 문서에서 배우는 것

- Outbox 패턴의 개념과 필요성
- 이중 쓰기(Dual Write) 문제 해결
- Outbox 테이블 설계
- 트랜잭션 아웃박스 구현
- Polling Publisher vs Transaction Log Tailing
- Spring Boot에서 Outbox 패턴 구현

---

## 1. 문제: 이중 쓰기 (Dual Write)

### 이중 쓰기란?

데이터베이스와 메시지 브로커에 **동시에 데이터를 쓰는 것**을 말합니다.

```
┌─────────────────────────────────────────────────────────────────────┐
│                     이중 쓰기 문제 시나리오                           │
│                                                                      │
│   시나리오 1: DB 성공, 메시지 실패                                   │
│   ┌─────────────┐                                                   │
│   │   Service   │                                                   │
│   │             │                                                   │
│   │ 1. DB 저장  │───▶ [MySQL] ✅ 성공                               │
│   │             │                                                   │
│   │ 2. 이벤트   │───▶ [Kafka] ❌ 실패 (네트워크 오류)               │
│   │    발행     │                                                   │
│   └─────────────┘                                                   │
│                                                                      │
│   결과: 주문은 저장됨, 하지만 이벤트 누락                            │
│         → 재고 감소 안됨, 결제 처리 안됨                             │
│                                                                      │
│   ─────────────────────────────────────────────────────────────     │
│                                                                      │
│   시나리오 2: DB 실패 후 메시지 발행                                 │
│   ┌─────────────┐                                                   │
│   │   Service   │                                                   │
│   │             │                                                   │
│   │ 1. DB 저장  │───▶ [MySQL] ✅ 성공                               │
│   │             │                                                   │
│   │ 2. 이벤트   │───▶ [Kafka] ✅ 성공                               │
│   │    발행     │                                                   │
│   │             │                                                   │
│   │ 3. DB 커밋  │───▶ [MySQL] ❌ 실패 (deadlock)                    │
│   └─────────────┘                                                   │
│                                                                      │
│   결과: 이벤트는 발행됨, 하지만 주문 롤백                            │
│         → 없는 주문에 대해 재고 감소, 결제 처리됨                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 왜 트랜잭션으로 해결할 수 없는가?

```java
// 이 코드는 안전하지 않습니다!
@Transactional
public void createOrder(Order order) {
    // 1. DB 저장
    orderRepository.save(order);  // 트랜잭션 내

    // 2. 이벤트 발행
    kafkaTemplate.send("orders", order);  // 트랜잭션 외!

    // 문제: Kafka는 DB 트랜잭션에 참여하지 않음
    // - Kafka 전송 후 DB 커밋 실패 → 이벤트는 발행됨
    // - Kafka 전송 실패 → 예외 발생, DB 롤백... 하지만 이미 전송 시도됨
}
```

---

## 2. Outbox 패턴 개요

### 해결 방법

**Outbox 테이블**을 사용하여 이벤트를 DB 트랜잭션 내에서 함께 저장합니다.

```
┌─────────────────────────────────────────────────────────────────────┐
│                       Outbox 패턴 아키텍처                           │
│                                                                      │
│   ┌─────────────┐                                                   │
│   │   Service   │                                                   │
│   └──────┬──────┘                                                   │
│          │                                                          │
│          │  단일 트랜잭션                                            │
│          ▼                                                          │
│   ┌─────────────────────────────────────────────────────┐          │
│   │                    Database                          │          │
│   │                                                      │          │
│   │   ┌─────────────────┐    ┌─────────────────┐        │          │
│   │   │   orders 테이블  │    │  outbox 테이블  │        │          │
│   │   │                 │    │                 │        │          │
│   │   │  id: 1          │    │  id: 1          │        │          │
│   │   │  product: A     │    │  type: ORDER    │        │          │
│   │   │  quantity: 5    │    │  payload: {...} │        │          │
│   │   │  status: CREATED│    │  status: PENDING│        │          │
│   │   └─────────────────┘    └────────┬────────┘        │          │
│   │                                   │                  │          │
│   └───────────────────────────────────┼──────────────────┘          │
│                                       │                             │
│                                       │ 별도 프로세스               │
│                                       ▼                             │
│   ┌─────────────────────────────────────────────────────┐          │
│   │              Message Relay (Polling/CDC)             │          │
│   └──────────────────────┬──────────────────────────────┘          │
│                          │                                          │
│                          │ 이벤트 발행                               │
│                          ▼                                          │
│   ┌─────────────────────────────────────────────────────┐          │
│   │                 Message Broker (Kafka)               │          │
│   └─────────────────────────────────────────────────────┘          │
└─────────────────────────────────────────────────────────────────────┘
```

### 핵심 원리

```
1. 비즈니스 데이터와 이벤트를 같은 트랜잭션으로 저장
   → 원자성 보장 (둘 다 성공하거나 둘 다 실패)

2. 별도 프로세스가 Outbox 테이블을 읽어 메시지 브로커로 발행
   → 최소 한 번 전달 보장 (At Least Once)

3. 발행 성공 후 Outbox 레코드 상태 업데이트
   → 중복 발행 방지
```

---

## 3. Outbox 테이블 설계

### 테이블 스키마

```sql
CREATE TABLE outbox_event (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    aggregate_type  VARCHAR(255) NOT NULL,      -- 도메인 타입 (Order, Payment, etc.)
    aggregate_id    VARCHAR(255) NOT NULL,      -- 도메인 ID
    event_type      VARCHAR(255) NOT NULL,      -- 이벤트 타입 (OrderCreated, etc.)
    payload         JSON NOT NULL,               -- 이벤트 데이터 (JSON)
    status          VARCHAR(50) NOT NULL DEFAULT 'PENDING',  -- 상태
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at    TIMESTAMP NULL,
    retry_count     INT DEFAULT 0,
    last_error      TEXT NULL,

    INDEX idx_status_created (status, created_at),
    INDEX idx_aggregate (aggregate_type, aggregate_id)
);
```

### Entity 클래스

```java
@Entity
@Table(name = "outbox_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String aggregateType;

    @Column(nullable = false)
    private String aggregateId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false, columnDefinition = "JSON")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime publishedAt;

    private int retryCount = 0;

    private String lastError;

    @Builder
    public OutboxEvent(String aggregateType, String aggregateId,
                       String eventType, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = LocalDateTime.now();
    }

    public void markAsPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
    }

    public void markAsFailed(String error) {
        this.status = OutboxStatus.FAILED;
        this.retryCount++;
        this.lastError = error;
    }

    public void markForRetry() {
        this.status = OutboxStatus.PENDING;
    }
}

public enum OutboxStatus {
    PENDING,    // 발행 대기
    PUBLISHED,  // 발행 완료
    FAILED      // 발행 실패
}
```

---

## 4. Spring Boot 구현

### 의존성 설정

```groovy
// build.gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.kafka:spring-kafka'
    implementation 'com.fasterxml.jackson.core:jackson-databind'
}
```

### Outbox Repository

```java
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query("SELECT e FROM OutboxEvent e WHERE e.status = :status ORDER BY e.createdAt ASC")
    List<OutboxEvent> findByStatus(@Param("status") OutboxStatus status, Pageable pageable);

    @Query("SELECT e FROM OutboxEvent e WHERE e.status = 'PENDING' " +
           "AND e.createdAt < :threshold ORDER BY e.createdAt ASC")
    List<OutboxEvent> findPendingOlderThan(@Param("threshold") LocalDateTime threshold,
                                            Pageable pageable);

    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.status = 'PUBLISHED' " +
           "AND e.publishedAt < :threshold")
    int deletePublishedOlderThan(@Param("threshold") LocalDateTime threshold);
}
```

### Outbox 서비스

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxService {

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public OutboxEvent save(String aggregateType, String aggregateId,
                            String eventType, Object eventData) {
        try {
            String payload = objectMapper.writeValueAsString(eventData);

            OutboxEvent event = OutboxEvent.builder()
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .payload(payload)
                    .build();

            return outboxRepository.save(event);

        } catch (JsonProcessingException e) {
            throw new OutboxException("Failed to serialize event", e);
        }
    }

    @Transactional(readOnly = true)
    public List<OutboxEvent> findPendingEvents(int limit) {
        return outboxRepository.findByStatus(
                OutboxStatus.PENDING,
                PageRequest.of(0, limit)
        );
    }

    @Transactional
    public void markAsPublished(Long eventId) {
        outboxRepository.findById(eventId)
                .ifPresent(OutboxEvent::markAsPublished);
    }

    @Transactional
    public void markAsFailed(Long eventId, String error) {
        outboxRepository.findById(eventId)
                .ifPresent(event -> event.markAsFailed(error));
    }
}
```

### 비즈니스 서비스에서 사용

```java
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final OutboxService outboxService;

    public OrderResponse createOrder(CreateOrderRequest request) {
        // 1. 주문 생성 및 저장
        Order order = Order.builder()
                .customerId(request.getCustomerId())
                .items(request.getItems())
                .totalAmount(calculateTotal(request.getItems()))
                .status(OrderStatus.CREATED)
                .build();

        Order savedOrder = orderRepository.save(order);

        // 2. Outbox 이벤트 저장 (같은 트랜잭션!)
        OrderCreatedEvent event = OrderCreatedEvent.builder()
                .orderId(savedOrder.getId())
                .customerId(savedOrder.getCustomerId())
                .items(savedOrder.getItems())
                .totalAmount(savedOrder.getTotalAmount())
                .createdAt(savedOrder.getCreatedAt())
                .build();

        outboxService.save(
                "Order",
                savedOrder.getId().toString(),
                "OrderCreated",
                event
        );

        log.info("Order created with outbox event: {}", savedOrder.getId());
        return OrderResponse.from(savedOrder);
    }

    public void cancelOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        order.cancel();

        OrderCancelledEvent event = OrderCancelledEvent.builder()
                .orderId(order.getId())
                .reason("Customer requested")
                .cancelledAt(LocalDateTime.now())
                .build();

        outboxService.save(
                "Order",
                order.getId().toString(),
                "OrderCancelled",
                event
        );
    }
}
```

---

## 5. Message Relay 구현

### 방법 1: Polling Publisher

```
┌─────────────────────────────────────────────────────────────────────┐
│                     Polling Publisher 방식                           │
│                                                                      │
│   ┌─────────────────┐                                               │
│   │  Scheduler      │  매 1초마다 실행                               │
│   │  (Polling)      │                                               │
│   └────────┬────────┘                                               │
│            │                                                        │
│            │ 1. PENDING 이벤트 조회                                  │
│            ▼                                                        │
│   ┌─────────────────┐                                               │
│   │    Database     │                                               │
│   │   (outbox)      │                                               │
│   └────────┬────────┘                                               │
│            │                                                        │
│            │ 2. 이벤트 목록 반환                                     │
│            ▼                                                        │
│   ┌─────────────────┐                                               │
│   │   Publisher     │                                               │
│   └────────┬────────┘                                               │
│            │                                                        │
│            │ 3. 메시지 발행                                          │
│            ▼                                                        │
│   ┌─────────────────┐                                               │
│   │     Kafka       │                                               │
│   └─────────────────┘                                               │
│                                                                      │
│   장점: 구현 간단, 추가 인프라 불필요                                 │
│   단점: 폴링 주기만큼 지연, DB 부하                                   │
└─────────────────────────────────────────────────────────────────────┘
```

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPollingPublisher {

    private final OutboxService outboxService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final int BATCH_SIZE = 100;

    @Scheduled(fixedDelay = 1000)  // 1초마다 실행
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxService.findPendingEvents(BATCH_SIZE);

        for (OutboxEvent event : events) {
            try {
                publishEvent(event);
                outboxService.markAsPublished(event.getId());
                log.debug("Published event: {} ({})", event.getId(), event.getEventType());

            } catch (Exception e) {
                log.error("Failed to publish event: {}", event.getId(), e);
                outboxService.markAsFailed(event.getId(), e.getMessage());
            }
        }
    }

    private void publishEvent(OutboxEvent event) {
        String topic = resolveTopic(event.getAggregateType());
        String key = event.getAggregateId();

        kafkaTemplate.send(topic, key, event.getPayload())
                .get(5, TimeUnit.SECONDS);  // 동기 전송
    }

    private String resolveTopic(String aggregateType) {
        return switch (aggregateType) {
            case "Order" -> "order-events";
            case "Payment" -> "payment-events";
            case "Inventory" -> "inventory-events";
            default -> "domain-events";
        };
    }
}
```

### 방법 2: Transaction Log Tailing (Debezium)

```
┌─────────────────────────────────────────────────────────────────────┐
│               Transaction Log Tailing (CDC) 방식                     │
│                                                                      │
│   ┌─────────────────┐                                               │
│   │   Application   │                                               │
│   └────────┬────────┘                                               │
│            │ INSERT                                                 │
│            ▼                                                        │
│   ┌─────────────────┐                                               │
│   │    Database     │                                               │
│   │    (MySQL)      │                                               │
│   │                 │                                               │
│   │  ┌───────────┐  │                                               │
│   │  │  binlog   │──┼──▶ ┌─────────────────┐                        │
│   │  └───────────┘  │    │    Debezium     │                        │
│   └─────────────────┘    │  (CDC Connector) │                        │
│                          └────────┬────────┘                        │
│                                   │                                 │
│                                   │ 변경 이벤트                      │
│                                   ▼                                 │
│                          ┌─────────────────┐                        │
│                          │     Kafka       │                        │
│                          └─────────────────┘                        │
│                                                                      │
│   장점: 실시간 처리, 폴링 부하 없음                                   │
│   단점: Debezium 설정 필요, 인프라 복잡도 증가                        │
└─────────────────────────────────────────────────────────────────────┘
```

### Debezium 설정 예시

```json
{
  "name": "outbox-connector",
  "config": {
    "connector.class": "io.debezium.connector.mysql.MySqlConnector",
    "database.hostname": "mysql",
    "database.port": "3306",
    "database.user": "debezium",
    "database.password": "dbz",
    "database.server.id": "1",
    "database.server.name": "order-service",
    "table.include.list": "order_db.outbox_event",
    "database.history.kafka.bootstrap.servers": "kafka:9092",
    "database.history.kafka.topic": "schema-changes.order-service",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.table.field.event.key": "aggregate_id",
    "transforms.outbox.table.field.event.type": "event_type",
    "transforms.outbox.table.field.event.payload": "payload",
    "transforms.outbox.route.by.field": "aggregate_type",
    "transforms.outbox.route.topic.replacement": "${routedByValue}-events"
  }
}
```

---

## 6. 재시도 및 실패 처리

### 재시도 스케줄러

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRetryScheduler {

    private final OutboxEventRepository outboxRepository;
    private final OutboxPollingPublisher publisher;

    private static final int MAX_RETRY_COUNT = 5;
    private static final int RETRY_BATCH_SIZE = 50;

    // 5분마다 실패한 이벤트 재시도
    @Scheduled(fixedRate = 300000)
    public void retryFailedEvents() {
        List<OutboxEvent> failedEvents = outboxRepository.findByStatus(
                OutboxStatus.FAILED,
                PageRequest.of(0, RETRY_BATCH_SIZE)
        );

        for (OutboxEvent event : failedEvents) {
            if (event.getRetryCount() >= MAX_RETRY_COUNT) {
                log.warn("Event exceeded max retries, moving to DLQ: {}", event.getId());
                moveToDeadLetterQueue(event);
                continue;
            }

            // 지수 백오프 적용
            if (shouldRetry(event)) {
                event.markForRetry();
                outboxRepository.save(event);
                log.info("Marked event for retry: {} (attempt {})",
                        event.getId(), event.getRetryCount() + 1);
            }
        }
    }

    private boolean shouldRetry(OutboxEvent event) {
        // 지수 백오프: 2^retryCount 분 후 재시도
        long waitMinutes = (long) Math.pow(2, event.getRetryCount());
        LocalDateTime retryAfter = event.getCreatedAt().plusMinutes(waitMinutes);
        return LocalDateTime.now().isAfter(retryAfter);
    }

    private void moveToDeadLetterQueue(OutboxEvent event) {
        // DLQ 테이블로 이동 또는 알림 발송
    }
}
```

### 정리 스케줄러

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxCleanupScheduler {

    private final OutboxEventRepository outboxRepository;

    // 매일 자정에 오래된 이벤트 삭제
    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void cleanupOldEvents() {
        // 7일 이상 된 발행 완료 이벤트 삭제
        LocalDateTime threshold = LocalDateTime.now().minusDays(7);
        int deleted = outboxRepository.deletePublishedOlderThan(threshold);
        log.info("Cleaned up {} old outbox events", deleted);
    }
}
```

---

## 7. 멱등성 처리 (Consumer 측)

### Consumer에서 중복 처리 방지

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventConsumer {

    private final ProcessedEventRepository processedEventRepository;
    private final InventoryService inventoryService;

    @KafkaListener(topics = "order-events", groupId = "inventory-service")
    public void handleOrderEvent(ConsumerRecord<String, String> record) {
        String eventId = extractEventId(record);

        // 멱등성 체크
        if (processedEventRepository.existsById(eventId)) {
            log.info("Event already processed, skipping: {}", eventId);
            return;
        }

        try {
            OrderCreatedEvent event = parseEvent(record.value());
            inventoryService.reserveStock(event);

            // 처리 완료 기록
            processedEventRepository.save(new ProcessedEvent(eventId, LocalDateTime.now()));

        } catch (Exception e) {
            log.error("Failed to process event: {}", eventId, e);
            throw e;  // 재처리를 위해 예외 전파
        }
    }

    private String extractEventId(ConsumerRecord<String, String> record) {
        // 헤더에서 이벤트 ID 추출 또는 record의 고유 정보로 생성
        return record.topic() + "-" + record.partition() + "-" + record.offset();
    }
}
```

```java
@Entity
@Table(name = "processed_event")
public class ProcessedEvent {

    @Id
    private String eventId;

    private LocalDateTime processedAt;

    // 일정 기간 후 삭제 가능
}
```

---

## 8. 전체 아키텍처

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Outbox 패턴 전체 흐름                              │
│                                                                      │
│   ┌──────────┐      ┌──────────────────────────────────────────┐   │
│   │  Client  │─────▶│              Order Service               │   │
│   └──────────┘      │                                          │   │
│                     │  @Transactional                          │   │
│                     │  ┌────────────────┐  ┌────────────────┐  │   │
│                     │  │  Order 저장    │  │  Outbox 저장   │  │   │
│                     │  └───────┬────────┘  └───────┬────────┘  │   │
│                     │          │                   │           │   │
│                     │          └─────────┬─────────┘           │   │
│                     │                    │                      │   │
│                     └────────────────────┼──────────────────────┘   │
│                                          │                          │
│                                          ▼                          │
│   ┌─────────────────────────────────────────────────────────────┐  │
│   │                       MySQL Database                         │  │
│   │   ┌────────────────────┐     ┌────────────────────┐         │  │
│   │   │     orders         │     │     outbox_event   │         │  │
│   │   └────────────────────┘     └──────────┬─────────┘         │  │
│   └─────────────────────────────────────────┼───────────────────┘  │
│                                             │                       │
│         ┌───────────────────────────────────┘                       │
│         │                                                           │
│         ▼                                                           │
│   ┌─────────────────┐                                              │
│   │ Polling/CDC     │                                              │
│   │ Publisher       │                                              │
│   └────────┬────────┘                                              │
│            │                                                        │
│            ▼                                                        │
│   ┌─────────────────┐                                              │
│   │     Kafka       │                                              │
│   │  (order-events) │                                              │
│   └────────┬────────┘                                              │
│            │                                                        │
│      ┌─────┴──────┬──────────────┐                                 │
│      ▼            ▼              ▼                                 │
│   ┌────────┐  ┌────────┐  ┌────────────┐                          │
│   │Inventory│  │Payment │  │Notification│                          │
│   │Service  │  │Service │  │  Service   │                          │
│   └────────┘  └────────┘  └────────────┘                          │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 9. 실습 과제

### 과제 1: Outbox 테이블 구현
1. outbox_event 테이블 생성
2. OutboxEvent 엔티티 작성
3. OutboxService 구현

### 과제 2: Polling Publisher 구현
1. 스케줄러로 PENDING 이벤트 조회
2. Kafka로 메시지 발행
3. 상태 업데이트 처리

### 과제 3: 재시도 로직 구현
1. 실패 이벤트 재시도 스케줄러
2. 지수 백오프 적용
3. 최대 재시도 횟수 제한
4. Dead Letter Queue 구현

### 과제 4: Consumer 멱등성
1. ProcessedEvent 테이블 생성
2. 중복 처리 방지 로직 구현
3. 처리 완료 기록

### 체크리스트
```
[ ] Outbox 테이블 설계 및 생성
[ ] OutboxEvent 엔티티 구현
[ ] OutboxService 구현
[ ] 비즈니스 로직에 Outbox 적용
[ ] Polling Publisher 구현
[ ] 재시도 스케줄러 구현
[ ] 정리 스케줄러 구현
[ ] Consumer 멱등성 처리
```

---

## 참고 자료

- [Microservices Patterns - Chris Richardson](https://microservices.io/patterns/data/transactional-outbox.html)
- [Debezium Outbox Pattern](https://debezium.io/documentation/reference/transformations/outbox-event-router.html)
- [Reliable Microservices Data Exchange With the Outbox Pattern](https://debezium.io/blog/2019/02/19/reliable-microservices-data-exchange-with-the-outbox-pattern/)
- [Spring Kafka Documentation](https://docs.spring.io/spring-kafka/reference/)

---

## 다음 단계

[05-opentelemetry-zipkin.md](./05-opentelemetry-zipkin.md) - 분산 추적으로 이동
