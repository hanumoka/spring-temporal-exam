# Redis Stream

## 이 문서에서 배우는 것

- Redis Stream의 개념과 특징
- 메시지 큐로서의 Redis Stream 활용
- Consumer Group을 통한 메시지 분산 처리
- Spring Boot에서 Redis Stream 사용 방법
- 메시지 처리 실패 시 재처리 전략

---

## 1. Redis Stream이란?

### 정의

**Redis Stream**은 Redis 5.0에서 도입된 로그형 데이터 구조로, Kafka와 유사한 메시지 스트리밍 기능을 제공합니다.

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Redis Stream                                 │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │                    Stream (orders)                           │    │
│  │                                                              │    │
│  │  1609459200000-0  →  {orderId: "001", product: "A", qty: 2}  │    │
│  │  1609459200001-0  →  {orderId: "002", product: "B", qty: 1}  │    │
│  │  1609459200002-0  →  {orderId: "003", product: "C", qty: 3}  │    │
│  │  1609459200003-0  →  {orderId: "004", product: "A", qty: 1}  │    │
│  │         ↑                                                    │    │
│  │    Entry ID (타임스탬프-시퀀스)                               │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  특징:                                                              │
│  • 추가만 가능 (Append-only)                                        │
│  • 영속성 보장                                                       │
│  • Consumer Group 지원                                              │
│  • 메시지 ID로 범위 조회 가능                                        │
└─────────────────────────────────────────────────────────────────────┘
```

### 다른 메시지 시스템과 비교

| 특징 | Redis Stream | Redis Pub/Sub | Kafka |
|------|-------------|---------------|-------|
| **메시지 영속성** | O | X | O |
| **Consumer Group** | O | X | O |
| **메시지 재처리** | O | X | O |
| **순서 보장** | O | O | O (파티션 내) |
| **설치 복잡도** | 낮음 | 낮음 | 높음 |
| **처리량** | 중간 | 높음 | 매우 높음 |

### Redis Stream을 선택하는 경우

```
✅ Redis Stream이 적합한 경우:
   • 이미 Redis를 사용 중인 프로젝트
   • 중간 규모의 메시지 처리 (초당 수만 건 이하)
   • 간단한 이벤트 스트리밍이 필요한 경우
   • Kafka 도입이 부담되는 소규모 프로젝트

❌ Redis Stream이 부적합한 경우:
   • 초대용량 데이터 처리 (초당 수십만 건 이상)
   • 장기간 메시지 보관이 필요한 경우
   • 복잡한 스트림 처리가 필요한 경우
```

---

## 2. 기본 명령어

### 메시지 추가 (XADD)

```bash
# 자동 ID 생성
XADD orders * orderId "001" product "laptop" quantity "1"
# 결과: "1609459200000-0"

# 수동 ID 지정
XADD orders 1609459200001-0 orderId "002" product "mouse" quantity "2"

# MAXLEN으로 크기 제한
XADD orders MAXLEN ~ 1000 * orderId "003" product "keyboard" quantity "1"
```

### 메시지 조회 (XREAD, XRANGE)

```bash
# 범위 조회
XRANGE orders - +              # 전체 조회
XRANGE orders 1609459200000-0 +  # 특정 ID 이후
XRANGE orders - + COUNT 10     # 최대 10개

# 실시간 읽기 (blocking)
XREAD BLOCK 5000 STREAMS orders $  # 새 메시지 대기 (5초 타임아웃)
XREAD BLOCK 0 STREAMS orders $     # 무한 대기

# 여러 스트림 동시 읽기
XREAD STREAMS orders payments 0 0
```

### 메시지 삭제

```bash
# 특정 메시지 삭제
XDEL orders 1609459200000-0

# 스트림 길이 조회
XLEN orders

# 스트림 정보 조회
XINFO STREAM orders
```

---

## 3. Consumer Group

### Consumer Group 개념

```
┌─────────────────────────────────────────────────────────────────────┐
│                     Consumer Group 아키텍처                          │
│                                                                      │
│   Stream: orders                                                     │
│   ┌───────────────────────────────────────────────────────┐         │
│   │ msg1 │ msg2 │ msg3 │ msg4 │ msg5 │ msg6 │ msg7 │ msg8 │         │
│   └───────────────────────────────────────────────────────┘         │
│                           │                                          │
│              ┌────────────┴────────────┐                             │
│              ▼                         ▼                             │
│   ┌─────────────────────┐   ┌─────────────────────┐                 │
│   │  Group: order-group │   │  Group: stock-group │                 │
│   │                     │   │                     │                 │
│   │  last_delivered_id  │   │  last_delivered_id  │                 │
│   │  pending_entries    │   │  pending_entries    │                 │
│   │                     │   │                     │                 │
│   │  ┌───────────────┐  │   │  ┌───────────────┐  │                 │
│   │  │ Consumer A    │  │   │  │ Consumer X    │  │                 │
│   │  │ msg1, msg3    │  │   │  │ msg1, msg2    │  │                 │
│   │  └───────────────┘  │   │  └───────────────┘  │                 │
│   │  ┌───────────────┐  │   │  ┌───────────────┐  │                 │
│   │  │ Consumer B    │  │   │  │ Consumer Y    │  │                 │
│   │  │ msg2, msg4    │  │   │  └───────────────┘  │                 │
│   │  └───────────────┘  │   │                     │                 │
│   └─────────────────────┘   └─────────────────────┘                 │
│                                                                      │
│   • 같은 그룹 내 Consumer들은 메시지를 분산 처리                      │
│   • 다른 그룹은 모든 메시지를 독립적으로 처리                          │
└─────────────────────────────────────────────────────────────────────┘
```

### Consumer Group 명령어

```bash
# Consumer Group 생성
XGROUP CREATE orders order-processors $ MKSTREAM
# $: 새 메시지부터, 0: 처음부터

# Consumer Group으로 읽기
XREADGROUP GROUP order-processors consumer-1 COUNT 10 STREAMS orders >
# >: 아직 전달되지 않은 새 메시지만

# 처리 완료 확인 (ACK)
XACK orders order-processors 1609459200000-0

# Pending 메시지 조회
XPENDING orders order-processors
XPENDING orders order-processors - + 10  # 상세 조회

# Consumer Group 정보
XINFO GROUPS orders
XINFO CONSUMERS orders order-processors
```

### 메시지 재처리 (Claim)

```bash
# 오래된 Pending 메시지 가져오기 (다른 Consumer가 처리 못한 메시지)
XCLAIM orders order-processors consumer-2 60000 1609459200000-0
# 60000: 60초 이상 처리되지 않은 메시지

# 자동 Claim (XAUTOCLAIM)
XAUTOCLAIM orders order-processors consumer-2 60000 0-0 COUNT 10
```

---

## 4. Spring Boot 연동

### 의존성 설정

```groovy
// build.gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    implementation 'io.lettuce:lettuce-core'
}
```

### 설정 클래스

```java
@Configuration
public class RedisStreamConfig {

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        return template;
    }

    @Bean
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>>
            streamMessageListenerContainer(RedisConnectionFactory factory) {

        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                        .builder()
                        .pollTimeout(Duration.ofSeconds(1))
                        .build();

        return StreamMessageListenerContainer.create(factory, options);
    }
}
```

### Producer 구현

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderEventProducer {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String STREAM_KEY = "orders";

    public String publishOrderCreated(OrderCreatedEvent event) {
        try {
            Map<String, String> message = new HashMap<>();
            message.put("eventType", "ORDER_CREATED");
            message.put("orderId", event.getOrderId());
            message.put("customerId", event.getCustomerId());
            message.put("totalAmount", event.getTotalAmount().toString());
            message.put("timestamp", Instant.now().toString());
            message.put("payload", objectMapper.writeValueAsString(event));

            StringRecord record = StreamRecords.string(message).withStreamKey(STREAM_KEY);
            RecordId recordId = redisTemplate.opsForStream().add(record);

            log.info("Published order event: {}, recordId: {}", event.getOrderId(), recordId);
            return recordId.getValue();

        } catch (JsonProcessingException e) {
            throw new EventPublishException("Failed to serialize event", e);
        }
    }

    // 스트림 크기 제한과 함께 발행
    public String publishWithMaxLen(OrderCreatedEvent event, long maxLen) {
        Map<String, String> message = createMessage(event);

        StringRecord record = StreamRecords.string(message).withStreamKey(STREAM_KEY);

        // MAXLEN ~ 옵션으로 대략적인 크기 제한
        RecordId recordId = redisTemplate.opsForStream()
                .add(record, RedisStreamCommands.XAddOptions.maxlen(maxLen).approximateTrimming(true));

        return recordId.getValue();
    }
}
```

### Consumer 구현 (StreamListener)

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private final OrderService orderService;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String STREAM_KEY = "orders";
    private static final String GROUP_NAME = "order-processors";
    private static final String CONSUMER_NAME = "consumer-1";

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        try {
            log.info("Received message: {}", message.getId());

            Map<String, String> data = message.getValue();
            String eventType = data.get("eventType");
            String payload = data.get("payload");

            switch (eventType) {
                case "ORDER_CREATED":
                    OrderCreatedEvent event = objectMapper.readValue(payload, OrderCreatedEvent.class);
                    orderService.processOrderCreated(event);
                    break;
                // 다른 이벤트 타입 처리
            }

            // 처리 완료 ACK
            redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, message.getId());
            log.info("Acknowledged message: {}", message.getId());

        } catch (Exception e) {
            log.error("Failed to process message: {}", message.getId(), e);
            // ACK하지 않으면 Pending 상태 유지 -> 재처리 가능
        }
    }
}
```

### Consumer Group 등록

```java
@Configuration
@RequiredArgsConstructor
@Slf4j
public class StreamConsumerConfig {

    private final RedisConnectionFactory connectionFactory;
    private final OrderEventConsumer orderEventConsumer;
    private final RedisTemplate<String, String> redisTemplate;

    private static final String STREAM_KEY = "orders";
    private static final String GROUP_NAME = "order-processors";
    private static final String CONSUMER_NAME = "consumer-1";

    @Bean
    public Subscription orderStreamSubscription(
            StreamMessageListenerContainer<String, MapRecord<String, String, String>> container) {

        // Consumer Group 생성 (없는 경우)
        createConsumerGroupIfNotExists();

        // Consumer 등록
        Subscription subscription = container.receive(
                Consumer.from(GROUP_NAME, CONSUMER_NAME),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()),
                orderEventConsumer
        );

        container.start();
        log.info("Started stream consumer: {}", CONSUMER_NAME);

        return subscription;
    }

    private void createConsumerGroupIfNotExists() {
        try {
            redisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0"), GROUP_NAME);
            log.info("Created consumer group: {}", GROUP_NAME);
        } catch (RedisSystemException e) {
            if (e.getCause() instanceof RedisCommandExecutionException &&
                    e.getCause().getMessage().contains("BUSYGROUP")) {
                log.info("Consumer group already exists: {}", GROUP_NAME);
            } else {
                throw e;
            }
        }
    }
}
```

---

## 5. Pending 메시지 처리 (재처리)

### Pending 메시지 모니터링

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class PendingMessageProcessor {

    private final RedisTemplate<String, String> redisTemplate;
    private final OrderService orderService;

    private static final String STREAM_KEY = "orders";
    private static final String GROUP_NAME = "order-processors";
    private static final String CONSUMER_NAME = "recovery-consumer";
    private static final Duration IDLE_THRESHOLD = Duration.ofMinutes(5);

    // 5분마다 실행
    @Scheduled(fixedRate = 300000)
    public void processPendingMessages() {
        log.info("Checking pending messages...");

        // Pending 메시지 조회
        PendingMessages pending = redisTemplate.opsForStream()
                .pending(STREAM_KEY, GROUP_NAME, Range.unbounded(), 100L);

        for (PendingMessage message : pending) {
            // 일정 시간 이상 처리되지 않은 메시지
            if (message.getElapsedTimeSinceLastDelivery().compareTo(IDLE_THRESHOLD) > 0) {
                claimAndProcess(message);
            }
        }
    }

    private void claimAndProcess(PendingMessage pendingMessage) {
        try {
            // 메시지 Claim
            List<MapRecord<String, String, String>> claimed = redisTemplate.opsForStream()
                    .claim(STREAM_KEY, GROUP_NAME, CONSUMER_NAME,
                           IDLE_THRESHOLD, pendingMessage.getId());

            if (!claimed.isEmpty()) {
                MapRecord<String, String, String> message = claimed.get(0);
                log.info("Claimed pending message: {}, attempts: {}",
                        message.getId(), pendingMessage.getTotalDeliveryCount());

                // 재시도 횟수 초과 시 Dead Letter Queue로 이동
                if (pendingMessage.getTotalDeliveryCount() > 3) {
                    moveToDeadLetterQueue(message);
                    acknowledgeMessage(message.getId());
                    return;
                }

                // 재처리 시도
                processMessage(message);
                acknowledgeMessage(message.getId());
            }
        } catch (Exception e) {
            log.error("Failed to process pending message: {}", pendingMessage.getId(), e);
        }
    }

    private void moveToDeadLetterQueue(MapRecord<String, String, String> message) {
        String dlqKey = STREAM_KEY + ":dlq";
        redisTemplate.opsForStream().add(
                StreamRecords.string(message.getValue()).withStreamKey(dlqKey));
        log.warn("Moved message to DLQ: {}", message.getId());
    }

    private void acknowledgeMessage(RecordId recordId) {
        redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, recordId);
    }
}
```

---

## 6. Pending List 심화: 문제와 대응 전략

### 6.1 Pending List 구조 이해

```
┌─────────────────────────────────────────────────────────────────────┐
│                 Pending Entries List (PEL) 상세 구조                  │
│                                                                       │
│   Consumer Group: order-processors                                   │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │  PEL Entry 구조:                                             │   │
│   │  ┌─────────────────────────────────────────────────────────┐│   │
│   │  │ Message ID      │ 1609459200000-0                       ││   │
│   │  │ Consumer Name   │ consumer-1                            ││   │
│   │  │ Delivery Time   │ 2024-01-15 10:30:00 (첫 전달 시간)     ││   │
│   │  │ Delivery Count  │ 3 (전달 횟수)                          ││   │
│   │  └─────────────────────────────────────────────────────────┘│   │
│   │                                                              │   │
│   │  중요 속성:                                                   │   │
│   │  • idle time: 마지막 전달 후 경과 시간                        │   │
│   │  • delivery count: XCLAIM/XREADGROUP 호출 횟수                │   │
│   └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### 6.2 Pending List 주요 문제

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Pending List 문제 시나리오                         │
│                                                                       │
│   [문제 1: 고아 메시지 (Orphaned Messages)]                           │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │  1. Consumer-1이 msg1, msg2 수신                             │   │
│   │  2. Consumer-1 처리 중 크래시! 💥                            │   │
│   │  3. msg1, msg2는 PEL에 영원히 남음 (ACK 불가)                 │   │
│   │  4. Consumer-1 재시작해도 새 메시지만 수신 (>)                │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                       │
│   [문제 2: 무한 재시도 루프]                                          │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │  1. msg1 수신 → 처리 실패 → ACK 안함                          │   │
│   │  2. XCLAIM으로 다시 가져옴 → 또 실패                          │   │
│   │  3. 반복... delivery_count만 증가                             │   │
│   │  4. 독이 된 메시지가 계속 시스템 리소스 소모                   │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                       │
│   [문제 3: 메모리 누수]                                               │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │  • PEL은 Redis 메모리에 저장                                  │   │
│   │  • ACK 안 된 메시지가 쌓이면 메모리 고갈                       │   │
│   │  • Stream MAXLEN과 별개로 PEL은 계속 성장                     │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                       │
│   [문제 4: 중복 처리 (Duplicate Processing)]                          │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │  T0: Consumer-1이 msg1 수신, 처리 시작                        │   │
│   │  T5: Consumer-1이 느려서 idle time 초과                       │   │
│   │  T6: Consumer-2가 XCLAIM으로 msg1 가져감                      │   │
│   │  T7: Consumer-1, Consumer-2 둘 다 msg1 처리 완료! 💥          │   │
│   │      → 중복 처리 발생                                         │   │
│   └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### 6.3 Pending 메시지 조회 명령어

```bash
# Pending 요약 정보
XPENDING orders order-processors
# 결과: 총 개수, 최소 ID, 최대 ID, Consumer별 개수

# Pending 상세 조회
XPENDING orders order-processors - + 100
# 결과: [message-id, consumer-name, idle-time, delivery-count]

# 특정 Consumer의 Pending 조회
XPENDING orders order-processors - + 100 consumer-1

# idle time이 긴 메시지만 조회 (60초 이상)
XPENDING orders order-processors IDLE 60000 - + 100
```

### 6.4 대응 전략 1: 체계적인 Pending 복구

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class PendingMessageRecoveryService {

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    private static final String STREAM_KEY = "orders";
    private static final String GROUP_NAME = "order-processors";
    private static final String RECOVERY_CONSUMER = "recovery-consumer";

    // Pending 복구 설정
    private static final Duration IDLE_THRESHOLD = Duration.ofMinutes(5);
    private static final int MAX_DELIVERY_COUNT = 3;
    private static final int BATCH_SIZE = 100;

    /**
     * 고아 메시지 복구 스케줄러
     * - idle time이 임계값을 초과한 메시지 탐지
     * - delivery count에 따라 재처리 또는 DLQ 이동
     */
    @Scheduled(fixedRate = 60000)  // 1분마다
    public void recoverOrphanedMessages() {
        log.info("Starting pending message recovery...");

        try {
            // 1. Pending 메시지 조회
            PendingMessages pending = redisTemplate.opsForStream()
                    .pending(STREAM_KEY, GROUP_NAME, Range.unbounded(), BATCH_SIZE);

            int recovered = 0;
            int movedToDlq = 0;

            for (PendingMessage msg : pending) {
                // 2. idle time 체크
                if (msg.getElapsedTimeSinceLastDelivery().compareTo(IDLE_THRESHOLD) < 0) {
                    continue;  // 아직 처리 중일 수 있음
                }

                // 3. delivery count 체크
                if (msg.getTotalDeliveryCount() >= MAX_DELIVERY_COUNT) {
                    // DLQ로 이동
                    moveToDeadLetterQueue(msg);
                    movedToDlq++;
                } else {
                    // 재처리를 위해 XCLAIM
                    claimAndRequeue(msg);
                    recovered++;
                }
            }

            // 4. 메트릭 기록
            meterRegistry.counter("redis.stream.pending.recovered").increment(recovered);
            meterRegistry.counter("redis.stream.pending.dlq").increment(movedToDlq);

            log.info("Recovery completed: recovered={}, movedToDlq={}", recovered, movedToDlq);

        } catch (Exception e) {
            log.error("Pending recovery failed", e);
            meterRegistry.counter("redis.stream.pending.recovery.error").increment();
        }
    }

    private void claimAndRequeue(PendingMessage pendingMsg) {
        try {
            // XCLAIM으로 메시지 소유권 가져오기
            List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream()
                    .claim(STREAM_KEY, GROUP_NAME, RECOVERY_CONSUMER,
                           IDLE_THRESHOLD, pendingMsg.getId());

            if (!claimed.isEmpty()) {
                log.info("Claimed message for recovery: id={}, deliveryCount={}",
                        pendingMsg.getId(), pendingMsg.getTotalDeliveryCount());

                // 재처리 로직 또는 재처리 큐에 추가
                for (MapRecord<String, Object, Object> record : claimed) {
                    processRecoveredMessage(record);
                }
            }
        } catch (Exception e) {
            log.error("Failed to claim message: {}", pendingMsg.getId(), e);
        }
    }

    private void moveToDeadLetterQueue(PendingMessage pendingMsg) {
        try {
            // 1. 원본 메시지 조회
            List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream()
                    .range(STREAM_KEY, Range.closed(
                            pendingMsg.getId().getValue(),
                            pendingMsg.getId().getValue()));

            if (!messages.isEmpty()) {
                MapRecord<String, Object, Object> original = messages.get(0);

                // 2. DLQ에 메시지 복사 (메타데이터 추가)
                Map<String, Object> dlqMessage = new HashMap<>(original.getValue());
                dlqMessage.put("_original_id", pendingMsg.getId().getValue());
                dlqMessage.put("_delivery_count", String.valueOf(pendingMsg.getTotalDeliveryCount()));
                dlqMessage.put("_failed_at", Instant.now().toString());
                dlqMessage.put("_consumer", pendingMsg.getConsumerName());

                redisTemplate.opsForStream().add(
                        StreamRecords.mapBacked(dlqMessage).withStreamKey(STREAM_KEY + ":dlq"));

                // 3. 원본 ACK (PEL에서 제거)
                redisTemplate.opsForStream()
                        .acknowledge(STREAM_KEY, GROUP_NAME, pendingMsg.getId());

                log.warn("Moved to DLQ: id={}, deliveryCount={}",
                        pendingMsg.getId(), pendingMsg.getTotalDeliveryCount());
            }
        } catch (Exception e) {
            log.error("Failed to move to DLQ: {}", pendingMsg.getId(), e);
        }
    }

    private void processRecoveredMessage(MapRecord<String, Object, Object> record) {
        // 복구된 메시지 처리 로직
        // 처리 성공 시 ACK
    }
}
```

### 6.5 대응 전략 2: 중복 처리 방지 (멱등성)

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotentStreamConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private final StringRedisTemplate redisTemplate;
    private final OrderService orderService;

    private static final String STREAM_KEY = "orders";
    private static final String GROUP_NAME = "order-processors";
    private static final String PROCESSED_KEY_PREFIX = "processed:";
    private static final Duration PROCESSED_TTL = Duration.ofHours(24);

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        String messageId = message.getId().getValue();
        String processedKey = PROCESSED_KEY_PREFIX + messageId;

        try {
            // 1. 이미 처리된 메시지인지 확인 (SETNX)
            Boolean isNew = redisTemplate.opsForValue()
                    .setIfAbsent(processedKey, "processing", PROCESSED_TTL);

            if (Boolean.FALSE.equals(isNew)) {
                // 이미 처리 중이거나 완료된 메시지
                log.info("Message already processed, skipping: {}", messageId);
                acknowledgeMessage(message);
                return;
            }

            // 2. 메시지 처리
            processMessage(message);

            // 3. 처리 완료 마킹
            redisTemplate.opsForValue().set(processedKey, "completed", PROCESSED_TTL);

            // 4. ACK
            acknowledgeMessage(message);

            log.debug("Message processed successfully: {}", messageId);

        } catch (Exception e) {
            log.error("Failed to process message: {}", messageId, e);

            // 처리 실패 시 processed 키 삭제 (재시도 허용)
            redisTemplate.delete(processedKey);

            // ACK 안함 → Pending 상태 유지 → 나중에 XCLAIM으로 재처리
        }
    }

    private void processMessage(MapRecord<String, String, String> message) {
        // 실제 비즈니스 로직
        String eventType = message.getValue().get("eventType");
        String payload = message.getValue().get("payload");

        if ("ORDER_CREATED".equals(eventType)) {
            orderService.processOrderCreated(payload);
        }
    }

    private void acknowledgeMessage(MapRecord<String, String, String> message) {
        redisTemplate.opsForStream()
                .acknowledge(STREAM_KEY, GROUP_NAME, message.getId());
    }
}
```

### 6.6 대응 전략 3: Consumer 헬스체크 및 자동 정리

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class ConsumerHealthManager {

    private final StringRedisTemplate redisTemplate;

    private static final String STREAM_KEY = "orders";
    private static final String GROUP_NAME = "order-processors";
    private static final Duration CONSUMER_INACTIVE_THRESHOLD = Duration.ofMinutes(30);

    /**
     * 비활성 Consumer 정리
     * - 오랫동안 메시지를 읽지 않은 Consumer 제거
     * - 해당 Consumer의 Pending 메시지는 다른 Consumer가 XCLAIM
     */
    @Scheduled(fixedRate = 300000)  // 5분마다
    public void cleanupInactiveConsumers() {
        try {
            StreamInfo.XInfoConsumers consumers = redisTemplate.opsForStream()
                    .consumers(STREAM_KEY, GROUP_NAME);

            for (StreamInfo.XInfoConsumer consumer : consumers) {
                Duration idleTime = consumer.idleTime();

                if (idleTime.compareTo(CONSUMER_INACTIVE_THRESHOLD) > 0) {
                    // Pending 메시지가 있는지 확인
                    long pendingCount = consumer.pendingCount();

                    if (pendingCount == 0) {
                        // Pending 없으면 Consumer 삭제
                        redisTemplate.opsForStream()
                                .deleteConsumer(STREAM_KEY, GROUP_NAME, consumer.consumerName());

                        log.info("Removed inactive consumer: name={}, idleTime={}",
                                consumer.consumerName(), idleTime);
                    } else {
                        log.warn("Inactive consumer has pending messages: name={}, pending={}, idleTime={}",
                                consumer.consumerName(), pendingCount, idleTime);
                        // Pending 메시지는 Recovery 스케줄러가 처리
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to cleanup inactive consumers", e);
        }
    }
}
```

### 6.7 Pending 모니터링 대시보드

```java
@RestController
@RequestMapping("/admin/stream")
@RequiredArgsConstructor
public class StreamMonitoringController {

    private final StringRedisTemplate redisTemplate;

    @GetMapping("/pending/summary")
    public PendingSummary getPendingSummary(
            @RequestParam String streamKey,
            @RequestParam String groupName) {

        PendingMessagesSummary summary = redisTemplate.opsForStream()
                .pending(streamKey, groupName);

        return PendingSummary.builder()
                .totalPending(summary.getTotalPendingMessages())
                .minId(summary.minMessageId())
                .maxId(summary.maxMessageId())
                .consumerPendingCounts(summary.getPendingMessagesPerConsumer())
                .build();
    }

    @GetMapping("/pending/details")
    public List<PendingMessageDetail> getPendingDetails(
            @RequestParam String streamKey,
            @RequestParam String groupName,
            @RequestParam(defaultValue = "100") int limit) {

        PendingMessages pending = redisTemplate.opsForStream()
                .pending(streamKey, groupName, Range.unbounded(), limit);

        return pending.stream()
                .map(msg -> PendingMessageDetail.builder()
                        .messageId(msg.getId().getValue())
                        .consumerName(msg.getConsumerName())
                        .idleTimeMs(msg.getElapsedTimeSinceLastDelivery().toMillis())
                        .deliveryCount(msg.getTotalDeliveryCount())
                        .build())
                .toList();
    }

    @GetMapping("/consumers")
    public List<ConsumerInfo> getConsumers(
            @RequestParam String streamKey,
            @RequestParam String groupName) {

        return redisTemplate.opsForStream()
                .consumers(streamKey, groupName)
                .stream()
                .map(c -> ConsumerInfo.builder()
                        .name(c.consumerName())
                        .pendingCount(c.pendingCount())
                        .idleTimeMs(c.idleTime().toMillis())
                        .build())
                .toList();
    }
}
```

### 6.8 Pending 관련 메트릭

```java
@Component
@RequiredArgsConstructor
public class StreamPendingMetrics {

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    @Scheduled(fixedRate = 30000)
    public void collectPendingMetrics() {
        String streamKey = "orders";
        String groupName = "order-processors";

        try {
            // 전체 Pending 수
            PendingMessagesSummary summary = redisTemplate.opsForStream()
                    .pending(streamKey, groupName);

            meterRegistry.gauge("redis.stream.pending.total",
                    Tags.of("stream", streamKey, "group", groupName),
                    summary.getTotalPendingMessages());

            // Consumer별 Pending
            summary.getPendingMessagesPerConsumer().forEach((consumer, count) ->
                    meterRegistry.gauge("redis.stream.pending.by_consumer",
                            Tags.of("stream", streamKey, "group", groupName, "consumer", consumer),
                            count));

            // 오래된 Pending (5분 이상)
            PendingMessages oldPending = redisTemplate.opsForStream()
                    .pending(streamKey, Consumer.from(groupName, "*"),
                            Range.unbounded(), 1000);

            long oldCount = oldPending.stream()
                    .filter(msg -> msg.getElapsedTimeSinceLastDelivery().toMinutes() > 5)
                    .count();

            meterRegistry.gauge("redis.stream.pending.old",
                    Tags.of("stream", streamKey, "group", groupName),
                    oldCount);

        } catch (Exception e) {
            // 메트릭 수집 실패 로깅
        }
    }
}
```

### 6.9 Pending 문제 대응 체크리스트

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Pending 문제 대응 체크리스트                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  예방 (Prevention)                                                    │
│  [ ] Consumer에서 적절한 타임아웃 설정                                 │
│  [ ] 처리 완료 후 즉시 ACK                                            │
│  [ ] 멱등성 처리로 중복 처리 방지                                      │
│  [ ] Consumer 장애 시 graceful shutdown으로 ACK 완료                   │
│                                                                       │
│  탐지 (Detection)                                                     │
│  [ ] Pending 메시지 수 모니터링                                        │
│  [ ] 오래된 Pending (idle time) 알림                                   │
│  [ ] Consumer별 Pending 불균형 감지                                    │
│  [ ] DLQ 메시지 수 모니터링                                            │
│                                                                       │
│  복구 (Recovery)                                                       │
│  [ ] XCLAIM으로 고아 메시지 복구                                       │
│  [ ] 최대 재시도 횟수 초과 시 DLQ 이동                                 │
│  [ ] 비활성 Consumer 자동 정리                                         │
│  [ ] DLQ 메시지 수동/자동 재처리                                       │
│                                                                       │
│  운영 (Operation)                                                      │
│  [ ] Pending 현황 대시보드                                             │
│  [ ] DLQ 처리 프로세스 정의                                            │
│  [ ] 장애 시 복구 런북 작성                                            │
│                                                                       │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 7. 실전 패턴: 주문 이벤트 처리

### 전체 아키텍처

```
┌─────────────────────────────────────────────────────────────────────┐
│                     주문 이벤트 처리 시스템                           │
│                                                                      │
│   ┌─────────────┐                                                   │
│   │   Client    │                                                   │
│   └──────┬──────┘                                                   │
│          │ POST /orders                                             │
│          ▼                                                          │
│   ┌─────────────┐     ┌─────────────┐                               │
│   │   Order     │────▶│   Redis     │                               │
│   │   Service   │     │   Stream    │                               │
│   └─────────────┘     │  (orders)   │                               │
│          │            └──────┬──────┘                               │
│          │                   │                                      │
│          │         ┌─────────┴─────────┐                            │
│          ▼         ▼                   ▼                            │
│   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                 │
│   │    MySQL    │  │  Inventory  │  │   Payment   │                 │
│   │     (DB)    │  │   Consumer  │  │   Consumer  │                 │
│   └─────────────┘  │             │  │             │                 │
│                    │  재고 감소   │  │  결제 처리  │                 │
│                    └─────────────┘  └─────────────┘                 │
│                            │                │                       │
│                            ▼                ▼                       │
│                    ┌─────────────┐  ┌─────────────┐                 │
│                    │  Inventory  │  │   Payment   │                 │
│                    │    Redis    │  │   Service   │                 │
│                    │   Stream    │  │             │                 │
│                    └─────────────┘  └─────────────┘                 │
└─────────────────────────────────────────────────────────────────────┘
```

### 주문 서비스 구현

```java
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderEventProducer eventProducer;

    public OrderResponse createOrder(CreateOrderRequest request) {
        // 1. 주문 생성
        Order order = Order.builder()
                .customerId(request.getCustomerId())
                .items(request.getItems())
                .totalAmount(calculateTotal(request.getItems()))
                .status(OrderStatus.CREATED)
                .build();

        Order savedOrder = orderRepository.save(order);

        // 2. 이벤트 발행
        OrderCreatedEvent event = OrderCreatedEvent.builder()
                .orderId(savedOrder.getId())
                .customerId(savedOrder.getCustomerId())
                .items(savedOrder.getItems())
                .totalAmount(savedOrder.getTotalAmount())
                .build();

        eventProducer.publishOrderCreated(event);

        log.info("Order created: {}", savedOrder.getId());
        return OrderResponse.from(savedOrder);
    }

    public void processOrderCreated(OrderCreatedEvent event) {
        log.info("Processing order created event: {}", event.getOrderId());
        // 비즈니스 로직 처리
    }
}
```

### 재고 Consumer 구현

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private final InventoryService inventoryService;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        try {
            String eventType = message.getValue().get("eventType");

            if ("ORDER_CREATED".equals(eventType)) {
                String payload = message.getValue().get("payload");
                OrderCreatedEvent event = objectMapper.readValue(payload, OrderCreatedEvent.class);

                // 재고 감소
                for (OrderItem item : event.getItems()) {
                    inventoryService.decreaseStock(item.getProductId(), item.getQuantity());
                }

                log.info("Inventory decreased for order: {}", event.getOrderId());
            }

            // ACK
            redisTemplate.opsForStream()
                    .acknowledge("orders", "inventory-processors", message.getId());

        } catch (InsufficientStockException e) {
            log.error("Insufficient stock for message: {}", message.getId());
            // 보상 트랜잭션 이벤트 발행
            publishStockFailedEvent(message);
        } catch (Exception e) {
            log.error("Failed to process inventory: {}", message.getId(), e);
        }
    }
}
```

---

## 7. 모니터링 및 운영

### 스트림 상태 확인 API

```java
@RestController
@RequestMapping("/admin/streams")
@RequiredArgsConstructor
public class StreamMonitorController {

    private final RedisTemplate<String, String> redisTemplate;

    @GetMapping("/{streamKey}/info")
    public StreamInfo getStreamInfo(@PathVariable String streamKey) {
        return redisTemplate.opsForStream().info(streamKey);
    }

    @GetMapping("/{streamKey}/groups")
    public XInfoGroups getGroups(@PathVariable String streamKey) {
        return redisTemplate.opsForStream().groups(streamKey);
    }

    @GetMapping("/{streamKey}/groups/{groupName}/pending")
    public PendingMessagesSummary getPendingSummary(
            @PathVariable String streamKey,
            @PathVariable String groupName) {
        return redisTemplate.opsForStream().pending(streamKey, groupName);
    }

    @GetMapping("/{streamKey}/groups/{groupName}/consumers")
    public XInfoConsumers getConsumers(
            @PathVariable String streamKey,
            @PathVariable String groupName) {
        return redisTemplate.opsForStream().consumers(streamKey, groupName);
    }
}
```

### 메트릭 수집

```java
@Component
@RequiredArgsConstructor
public class StreamMetrics {

    private final RedisTemplate<String, String> redisTemplate;
    private final MeterRegistry meterRegistry;

    @Scheduled(fixedRate = 30000)
    public void collectMetrics() {
        String streamKey = "orders";
        String groupName = "order-processors";

        // 스트림 길이
        Long length = redisTemplate.opsForStream().size(streamKey);
        meterRegistry.gauge("redis.stream.length",
                Tags.of("stream", streamKey), length);

        // Pending 메시지 수
        PendingMessagesSummary pending = redisTemplate.opsForStream()
                .pending(streamKey, groupName);
        meterRegistry.gauge("redis.stream.pending",
                Tags.of("stream", streamKey, "group", groupName),
                pending.getTotalPendingMessages());
    }
}
```

---

## 8. 실습 과제

### 과제 1: 기본 Stream 구현
1. Redis Stream 생성 및 메시지 발행
2. Consumer로 메시지 읽기
3. Consumer Group 설정 및 분산 처리

### 과제 2: 주문 이벤트 시스템
1. 주문 생성 시 이벤트 발행
2. 재고 서비스에서 이벤트 구독
3. 결제 서비스에서 이벤트 구독

### 과제 3: 에러 처리
1. Pending 메시지 모니터링 구현
2. Dead Letter Queue 구현
3. 재시도 로직 구현

### 체크리스트
```
[ ] Redis Stream 생성 및 메시지 발행
[ ] Consumer Group 생성
[ ] StreamListener 구현
[ ] ACK 처리
[ ] Pending 메시지 재처리
[ ] DLQ 구현
[ ] 모니터링 API 구현
```

---

## 참고 자료

- [Redis Streams 공식 문서](https://redis.io/docs/data-types/streams/)
- [Redis Streams Tutorial](https://redis.io/docs/data-types/streams-tutorial/)
- [Spring Data Redis - Streams](https://docs.spring.io/spring-data/redis/reference/redis/redis-streams.html)
- [Redis Streams vs Kafka](https://redis.io/blog/redis-streams-vs-kafka/)

---

## 다음 단계

[03-redisson.md](./03-redisson.md) - Redisson 분산 락으로 이동
