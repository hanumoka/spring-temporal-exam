# Redis Stream

## 이 문서에서 배우는 것

- Redis Stream의 개념과 특징
- **핵심 객체 상세**: Producer, Consumer, Consumer Group의 역할과 구조
- **ACK vs XDEL**: 메시지 확인과 삭제의 차이, 생명주기 관리
- **메시지 순서 보장**: 4가지 전략 (단일 Consumer, 파티셔닝, 시퀀스 검증, 버퍼링)
- Consumer Group을 통한 메시지 분산 처리
- **Pending Entry List (PEL)**: 구조, 문제점, 복구 전략
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

## 7. 핵심 객체 상세: Producer, Consumer, Consumer Group

### 7.1 Redis Stream의 핵심 구성 요소

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      Redis Stream 핵심 객체 관계도                            │
│                                                                              │
│   ┌─────────────┐                                                           │
│   │  Producer   │  ← Redis 클라이언트 (Spring Data Redis, Lettuce, Jedis)   │
│   │             │     XADD 명령어로 메시지 발행                               │
│   └──────┬──────┘                                                           │
│          │ XADD orders * field1 value1 field2 value2                        │
│          ▼                                                                   │
│   ┌─────────────────────────────────────────────────────────────────┐       │
│   │                        Stream (orders)                           │       │
│   │  ┌─────────────────────────────────────────────────────────┐    │       │
│   │  │  Entry ID         │  Fields (Hash-like structure)        │    │       │
│   │  ├───────────────────┼──────────────────────────────────────┤    │       │
│   │  │ 1609459200000-0   │ {orderId: "001", status: "created"}  │    │       │
│   │  │ 1609459200001-0   │ {orderId: "002", status: "created"}  │    │       │
│   │  │ 1609459200002-0   │ {orderId: "003", status: "pending"}  │    │       │
│   │  └───────────────────┴──────────────────────────────────────┘    │       │
│   │                                                                  │       │
│   │  metadata: length, first-entry, last-entry, etc.                │       │
│   └─────────────────────────────────────────────────────────────────┘       │
│          │                                                                   │
│          │ XREAD / XREADGROUP                                               │
│          ▼                                                                   │
│   ┌──────────────────────────────────────────────────────────────────┐      │
│   │                    Consumer Group (order-processors)               │      │
│   │  ┌────────────────────────────────────────────────────────────┐  │      │
│   │  │  metadata:                                                   │  │      │
│   │  │  • name: "order-processors"                                 │  │      │
│   │  │  • last-delivered-id: "1609459200002-0" (마지막 전달 ID)     │  │      │
│   │  │  • pending-entries: 3 (전달됐지만 ACK 안 된 메시지 수)       │  │      │
│   │  └────────────────────────────────────────────────────────────┘  │      │
│   │                                                                    │      │
│   │  ┌──────────────────────────────────────────────────────────────┐│      │
│   │  │                  PEL (Pending Entries List)                   ││      │
│   │  │  ┌────────────┬────────────┬──────────────┬───────────────┐  ││      │
│   │  │  │ Message ID │ Consumer   │ Delivery Time│ Delivery Count│  ││      │
│   │  │  ├────────────┼────────────┼──────────────┼───────────────┤  ││      │
│   │  │  │ ...200000-0│ consumer-1 │ 10:30:00     │ 1             │  ││      │
│   │  │  │ ...200001-0│ consumer-2 │ 10:30:01     │ 2             │  ││      │
│   │  │  │ ...200002-0│ consumer-1 │ 10:30:02     │ 1             │  ││      │
│   │  │  └────────────┴────────────┴──────────────┴───────────────┘  ││      │
│   │  └──────────────────────────────────────────────────────────────┘│      │
│   │                           │                                        │      │
│   │           ┌───────────────┼───────────────┐                       │      │
│   │           ▼               ▼               ▼                       │      │
│   │  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐              │      │
│   │  │  Consumer    │ │  Consumer    │ │  Consumer    │              │      │
│   │  │  consumer-1  │ │  consumer-2  │ │  consumer-3  │              │      │
│   │  │              │ │              │ │              │              │      │
│   │  │  pending: 2  │ │  pending: 1  │ │  pending: 0  │              │      │
│   │  │  idle: 5sec  │ │  idle: 3sec  │ │  idle: 1sec  │              │      │
│   │  └──────────────┘ └──────────────┘ └──────────────┘              │      │
│   └──────────────────────────────────────────────────────────────────┘      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 7.2 Producer (메시지 발행자)

**Producer는 별도의 Redis 객체가 아니라**, 메시지를 발행하는 클라이언트(역할)를 의미합니다.

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Producer 특징                                │
│                                                                      │
│   1. 별도 등록 불필요                                                │
│      • Consumer Group처럼 미리 생성할 필요 없음                       │
│      • XADD 명령을 실행하는 모든 클라이언트가 Producer                │
│                                                                      │
│   2. 여러 Producer가 동시에 발행 가능                                 │
│      • Redis는 단일 스레드이므로 원자성 보장                          │
│      • Entry ID의 시퀀스 번호로 동일 시간 충돌 방지                    │
│                                                                      │
│   3. Entry ID 생성 방식                                               │
│      • 자동: XADD orders * field value (Redis가 ID 생성)             │
│      • 수동: XADD orders 1234567890123-0 field value                 │
│                                                                      │
│   4. 백프레셔 옵션                                                    │
│      • MAXLEN: 스트림 최대 길이 제한                                  │
│      • MINID: 특정 ID 이전 메시지 삭제                                │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

```java
// Producer 구현 예시
@Service
@RequiredArgsConstructor
@Slf4j
public class EventProducer {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 메시지 발행 (자동 ID 생성)
     *
     * @return Entry ID (예: "1609459200000-0")
     */
    public String publish(String streamKey, Object event) {
        try {
            Map<String, String> message = new HashMap<>();
            message.put("eventType", event.getClass().getSimpleName());
            message.put("payload", objectMapper.writeValueAsString(event));
            message.put("timestamp", Instant.now().toString());
            message.put("producerId", getProducerId());  // 선택적: 추적용

            StringRecord record = StreamRecords.string(message)
                    .withStreamKey(streamKey);

            RecordId recordId = redisTemplate.opsForStream().add(record);

            log.info("Published to {}: id={}", streamKey, recordId.getValue());
            return recordId.getValue();

        } catch (JsonProcessingException e) {
            throw new EventPublishException("Serialization failed", e);
        }
    }

    /**
     * 스트림 크기 제한과 함께 발행
     * MAXLEN ~: 대략적 트리밍 (성능 최적화)
     */
    public String publishWithMaxLen(String streamKey, Object event, long maxLen) {
        Map<String, String> message = createMessage(event);

        RecordId recordId = redisTemplate.opsForStream().add(
                StreamRecords.string(message).withStreamKey(streamKey),
                RedisStreamCommands.XAddOptions.maxlen(maxLen).approximateTrimming(true)
        );

        return recordId.getValue();
    }

    private String getProducerId() {
        // 인스턴스 식별자 (예: hostname + port)
        return System.getenv("HOSTNAME") + ":" + System.getenv("SERVER_PORT");
    }
}
```

### 7.3 Consumer Group (소비자 그룹)

**Consumer Group은 Redis에 저장되는 실제 객체**입니다.

```
┌─────────────────────────────────────────────────────────────────────┐
│                      Consumer Group 핵심 개념                         │
│                                                                      │
│   [1. 메시지 분산]                                                    │
│   • 같은 그룹의 Consumer들은 메시지를 "나눠서" 처리                    │
│   • 하나의 메시지는 그룹 내 하나의 Consumer에게만 전달                 │
│   • 수평 확장: Consumer 추가로 처리량 증가                            │
│                                                                      │
│   [2. 독립적 그룹]                                                    │
│   • 다른 그룹은 같은 메시지를 "독립적으로" 모두 처리                   │
│   • Kafka의 Consumer Group과 동일한 개념                              │
│                                                                      │
│   [3. 상태 추적]                                                       │
│   • last-delivered-id: 마지막으로 전달한 메시지 ID                    │
│   • PEL: 전달됐지만 ACK되지 않은 메시지 목록                          │
│                                                                      │
│   [4. 생성 방식]                                                       │
│   • XGROUP CREATE stream group-name <id> [MKSTREAM]                  │
│   • $ : 새 메시지부터                                                 │
│   • 0 : 처음부터                                                      │
│   • 특정 ID: 해당 ID 이후부터                                          │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

```
┌─────────────────────────────────────────────────────────────────────┐
│                  Consumer Group 명령어 정리                           │
│                                                                      │
│   생성:                                                               │
│   XGROUP CREATE orders order-processors $ MKSTREAM                   │
│   XGROUP CREATE orders order-processors 0 MKSTREAM                   │
│                                                                      │
│   삭제:                                                               │
│   XGROUP DESTROY orders order-processors                             │
│                                                                      │
│   Consumer 삭제:                                                      │
│   XGROUP DELCONSUMER orders order-processors consumer-1              │
│                                                                      │
│   시작 ID 변경:                                                        │
│   XGROUP SETID orders order-processors 0                             │
│   XGROUP SETID orders order-processors $                             │
│                                                                      │
│   정보 조회:                                                          │
│   XINFO GROUPS orders                                                │
│   XINFO CONSUMERS orders order-processors                            │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 7.4 Consumer (소비자)

**Consumer는 Consumer Group 내에서 자동 생성되는 논리적 엔티티**입니다.

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Consumer 특징                                 │
│                                                                      │
│   [자동 생성]                                                         │
│   • XREADGROUP 호출 시 자동 등록                                      │
│   • 별도 생성 명령 없음                                               │
│   • 첫 XREADGROUP 호출이 "암묵적 등록"                                │
│                                                                      │
│   [추적 정보]                                                         │
│   • name: Consumer 이름                                              │
│   • pending: 해당 Consumer에 전달된 미ACK 메시지 수                   │
│   • idle: 마지막 활동 후 경과 시간                                    │
│                                                                      │
│   [식별자 설계]                                                        │
│   • 고유해야 함 (같은 이름 = 같은 Consumer)                           │
│   • 권장: hostname + process-id + thread-id                          │
│   • 또는: UUID                                                        │
│                                                                      │
│   [생명주기]                                                          │
│   • 생성: 첫 XREADGROUP 호출 시                                       │
│   • 유지: 메시지 읽기/ACK 시 갱신                                     │
│   • 삭제: XGROUP DELCONSUMER 또는 수동 정리                           │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

```java
// Consumer 구현 예시 (Spring Data Redis)
@Component
@RequiredArgsConstructor
@Slf4j
public class EventConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final OrderService orderService;

    private static final String STREAM_KEY = "orders";
    private static final String GROUP_NAME = "order-processors";

    // Consumer 이름: 인스턴스별로 고유해야 함
    private final String consumerName = generateConsumerName();

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        String messageId = message.getId().getValue();

        try {
            log.info("Received: id={}, consumer={}", messageId, consumerName);

            Map<String, String> data = message.getValue();
            String eventType = data.get("eventType");
            String payload = data.get("payload");

            // 비즈니스 로직 처리
            processEvent(eventType, payload);

            // ACK: 처리 완료 확인
            redisTemplate.opsForStream()
                    .acknowledge(STREAM_KEY, GROUP_NAME, message.getId());

            log.debug("ACKed: id={}", messageId);

        } catch (Exception e) {
            log.error("Failed to process: id={}", messageId, e);
            // ACK 안함 → PEL에 남음 → 나중에 재처리
        }
    }

    private String generateConsumerName() {
        String hostname = System.getenv().getOrDefault("HOSTNAME", "localhost");
        String pid = String.valueOf(ProcessHandle.current().pid());
        return hostname + "-" + pid + "-" + Thread.currentThread().getId();
    }

    private void processEvent(String eventType, String payload) throws Exception {
        switch (eventType) {
            case "OrderCreatedEvent" -> {
                OrderCreatedEvent event = objectMapper.readValue(payload, OrderCreatedEvent.class);
                orderService.processOrderCreated(event);
            }
            case "OrderCancelledEvent" -> {
                OrderCancelledEvent event = objectMapper.readValue(payload, OrderCancelledEvent.class);
                orderService.processOrderCancelled(event);
            }
            default -> log.warn("Unknown event type: {}", eventType);
        }
    }
}
```

### 7.5 XREAD vs XREADGROUP 비교

```
┌─────────────────────────────────────────────────────────────────────┐
│                    XREAD vs XREADGROUP 비교                          │
│                                                                      │
│   ┌────────────────┬─────────────────────┬───────────────────────┐  │
│   │      구분      │       XREAD          │     XREADGROUP        │  │
│   ├────────────────┼─────────────────────┼───────────────────────┤  │
│   │ Consumer Group │ 사용 안함            │ 필수                  │  │
│   │ 메시지 분산    │ 모든 Consumer에 전달 │ 그룹 내 1개에만 전달  │  │
│   │ ACK           │ 개념 없음            │ 필수 (또는 선택)      │  │
│   │ PEL           │ 없음                 │ 있음                  │  │
│   │ 재처리        │ 불가                 │ 가능 (XCLAIM)         │  │
│   │ 시작 위치     │ ID 또는 $            │ > 또는 0 또는 ID     │  │
│   │ At-least-once │ 보장 안됨            │ 보장                  │  │
│   └────────────────┴─────────────────────┴───────────────────────┘  │
│                                                                      │
│   사용 케이스:                                                        │
│   • XREAD: 단순 모니터링, 브로드캐스트, 실시간 대시보드              │
│   • XREADGROUP: 작업 분산, 신뢰성 있는 처리, 마이크로서비스          │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 8. ACK와 XDEL: 메시지 생명주기

### 8.1 ACK (XACK)와 XDEL의 차이

```
┌─────────────────────────────────────────────────────────────────────┐
│                      ACK vs XDEL 비교                                │
│                                                                      │
│   ┌────────────────────┬──────────────────┬─────────────────────┐   │
│   │       구분         │      XACK         │       XDEL          │   │
│   ├────────────────────┼──────────────────┼─────────────────────┤   │
│   │ 목적              │ 처리 완료 표시     │ 메시지 물리 삭제     │   │
│   │ 대상              │ PEL (Consumer별)  │ Stream 자체          │   │
│   │ 다른 그룹 영향     │ 없음 (그룹 독립)  │ 있음 (전체 삭제)     │   │
│   │ 메시지 유지       │ Stream에 남아있음  │ Stream에서 제거      │   │
│   │ 복구 가능         │ XRANGE로 재조회    │ 불가                 │   │
│   │ 호출 시점         │ 처리 완료 직후     │ 모든 그룹 처리 후    │   │
│   └────────────────────┴──────────────────┴─────────────────────┘   │
│                                                                      │
│   핵심: XACK ≠ 삭제, XACK = "이 Consumer가 처리 완료"                │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 8.2 메시지 생명주기

```
┌─────────────────────────────────────────────────────────────────────┐
│                      메시지 생명주기 상세                             │
│                                                                      │
│   [단계 1: 발행 (XADD)]                                               │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │  Producer ─── XADD orders * orderId 001 ───▶ Stream         │   │
│   │                                                              │   │
│   │  Stream 상태:                                                 │   │
│   │  • 메시지 추가됨                                              │   │
│   │  • 모든 Consumer Group에서 조회 가능                          │   │
│   │  • 아직 어떤 그룹에도 전달되지 않음                            │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                           │                                          │
│                           ▼                                          │
│   [단계 2: 전달 (XREADGROUP)]                                         │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │  Consumer ─── XREADGROUP GROUP g1 consumer-1 ... ──▶ Redis  │   │
│   │                                                              │   │
│   │  상태 변화:                                                   │   │
│   │  • 메시지가 Consumer에게 전달됨                               │   │
│   │  • PEL에 항목 추가 (message-id, consumer-1, time, count=1)   │   │
│   │  • Consumer Group의 last-delivered-id 갱신                   │   │
│   │  • Stream의 메시지는 그대로 유지                              │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                           │                                          │
│                           ▼                                          │
│   [단계 3: 처리 완료 (XACK)]                                          │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │  Consumer ─── XACK orders g1 <message-id> ──────────▶ Redis │   │
│   │                                                              │   │
│   │  상태 변화:                                                   │   │
│   │  • PEL에서 해당 항목 제거                                     │   │
│   │  • Stream의 메시지는 여전히 존재! (삭제 아님)                 │   │
│   │  • 다른 Consumer Group의 PEL에는 영향 없음                    │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                           │                                          │
│                           ▼                                          │
│   [단계 4: 삭제 (XDEL 또는 XTRIM)]                                    │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │  Admin ─── XDEL orders <message-id> ────────────────▶ Redis │   │
│   │  또는                                                        │   │
│   │  Auto ─── XTRIM orders MAXLEN ~ 1000 ───────────────▶ Redis │   │
│   │                                                              │   │
│   │  상태 변화:                                                   │   │
│   │  • Stream에서 메시지 물리적 삭제                              │   │
│   │  • 모든 Consumer Group에서 더 이상 조회 불가                  │   │
│   │  • 주의: 다른 그룹이 아직 처리 안했을 수 있음!                │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 8.3 ACK Best Practices

```java
/**
 * ACK Best Practices 구현 예시
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AckBestPracticesConsumer {

    private final StringRedisTemplate redisTemplate;

    /**
     * Best Practice 1: 처리 완료 후 즉시 ACK
     *
     * - 처리 성공 시에만 ACK
     * - 예외 발생 시 ACK 안함 → 재처리 가능
     */
    public void processWithImmediateAck(MapRecord<String, String, String> message) {
        try {
            // 1. 비즈니스 로직 처리
            processBusinessLogic(message);

            // 2. 처리 성공 시에만 ACK
            redisTemplate.opsForStream()
                    .acknowledge("stream", "group", message.getId());

        } catch (Exception e) {
            // ACK 안함 → PEL에 남음 → XCLAIM으로 재처리 가능
            log.error("Processing failed, not acknowledging: {}", message.getId());
        }
    }

    /**
     * Best Practice 2: 멱등성 + ACK
     *
     * - 중복 처리 방지와 ACK 결합
     * - XCLAIM으로 재처리될 때 안전
     */
    public void processWithIdempotency(MapRecord<String, String, String> message) {
        String messageId = message.getId().getValue();
        String processedKey = "processed:" + messageId;

        // 1. 이미 처리된 메시지인지 확인
        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(processedKey, "processing", Duration.ofHours(24));

        if (Boolean.FALSE.equals(isNew)) {
            // 이미 처리 중이거나 완료 → ACK만 하고 종료
            log.info("Already processed: {}", messageId);
            redisTemplate.opsForStream()
                    .acknowledge("stream", "group", message.getId());
            return;
        }

        try {
            // 2. 비즈니스 로직 처리
            processBusinessLogic(message);

            // 3. 처리 완료 마킹
            redisTemplate.opsForValue()
                    .set(processedKey, "completed", Duration.ofHours(24));

            // 4. ACK
            redisTemplate.opsForStream()
                    .acknowledge("stream", "group", message.getId());

        } catch (Exception e) {
            // 실패 시 processed 키 삭제 (재처리 허용)
            redisTemplate.delete(processedKey);
            throw e;
        }
    }

    /**
     * Best Practice 3: 배치 ACK
     *
     * - 여러 메시지를 묶어서 ACK
     * - 네트워크 왕복 감소
     */
    public void batchAck(String streamKey, String groupName, List<RecordId> processedIds) {
        if (!processedIds.isEmpty()) {
            RecordId[] ids = processedIds.toArray(new RecordId[0]);
            Long acked = redisTemplate.opsForStream()
                    .acknowledge(streamKey, groupName, ids);
            log.info("Batch ACKed: requested={}, acked={}", ids.length, acked);
        }
    }

    private void processBusinessLogic(MapRecord<String, String, String> message) {
        // 실제 비즈니스 로직
    }
}
```

### 8.4 XDEL 사용 시 주의사항

```
┌─────────────────────────────────────────────────────────────────────┐
│                      XDEL 사용 주의사항                               │
│                                                                      │
│   [위험 상황]                                                         │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                                                              │   │
│   │   Stream: orders                                             │   │
│   │   ├── Group A: order-processors (msg1 처리 완료, ACK됨)     │   │
│   │   └── Group B: inventory-sync (msg1 아직 처리 안됨)          │   │
│   │                                                              │   │
│   │   XDEL orders msg1  ← Group A가 삭제하면?                    │   │
│   │   → Group B는 msg1을 영영 처리할 수 없음! 💥                 │   │
│   │                                                              │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│   [안전한 삭제 전략]                                                  │
│                                                                      │
│   전략 1: MAXLEN + XTRIM (자동 관리)                                 │
│   • XADD orders MAXLEN ~ 10000 * ...                               │
│   • 오래된 메시지 자동 정리                                          │
│   • 충분히 긴 보관 기간 설정                                         │
│                                                                      │
│   전략 2: MINID + XTRIM                                              │
│   • XTRIM orders MINID ~ <oldest-safe-id>                          │
│   • 특정 ID 이전 메시지만 삭제                                       │
│   • 모든 그룹의 last-delivered-id 확인 후 삭제                       │
│                                                                      │
│   전략 3: 별도 정리 배치 (권장)                                       │
│   • 주기적으로 모든 그룹 상태 확인                                    │
│   • 모든 그룹이 처리 완료한 메시지만 삭제                             │
│   • 충분한 보관 기간 후 삭제                                         │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

```java
/**
 * 안전한 스트림 정리 서비스
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StreamCleanupService {

    private final StringRedisTemplate redisTemplate;

    /**
     * 모든 Consumer Group이 처리 완료한 메시지만 삭제
     */
    @Scheduled(cron = "0 0 2 * * *")  // 매일 새벽 2시
    public void cleanupProcessedMessages() {
        String streamKey = "orders";

        // 1. 모든 Consumer Group 정보 조회
        StreamInfo.XInfoGroups groups = redisTemplate.opsForStream()
                .groups(streamKey);

        // 2. 가장 뒤처진 그룹의 last-delivered-id 찾기
        String minDeliveredId = groups.stream()
                .map(group -> group.lastDeliveredId())
                .filter(Objects::nonNull)
                .min(Comparator.comparing(this::parseEntryId))
                .orElse(null);

        if (minDeliveredId == null) {
            log.info("No groups found, skipping cleanup");
            return;
        }

        // 3. 안전 마진 적용 (예: 1시간 이전 메시지만 삭제)
        String safeDeleteId = calculateSafeDeleteId(minDeliveredId);

        // 4. XTRIM으로 안전하게 삭제
        Long deleted = redisTemplate.opsForStream()
                .trim(streamKey, StreamTrimOptions.minId(safeDeleteId).approximate());

        log.info("Cleaned up stream: streamKey={}, minId={}, deleted={}",
                streamKey, safeDeleteId, deleted);
    }

    private long parseEntryId(String entryId) {
        return Long.parseLong(entryId.split("-")[0]);
    }

    private String calculateSafeDeleteId(String lastDeliveredId) {
        long timestamp = parseEntryId(lastDeliveredId);
        long safeTimestamp = timestamp - Duration.ofHours(1).toMillis();
        return safeTimestamp + "-0";
    }
}
```

---

## 9. 메시지 순서 보장 전략

### 9.1 Redis Stream의 순서 보장 특성

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Redis Stream 순서 보장 특성                        │
│                                                                      │
│   [기본 특성]                                                         │
│   • Entry ID가 시간순 정렬 보장                                       │
│   • 단일 Consumer: 완벽한 순서 보장                                   │
│   • 다중 Consumer (Consumer Group): 순서 보장 안됨 ⚠️               │
│                                                                      │
│   [Consumer Group 순서 문제 시나리오]                                  │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │  Stream: [msg1] [msg2] [msg3] [msg4] [msg5]                  │   │
│   │                                                              │   │
│   │  T0: Consumer-1 receives msg1, msg3, msg5                    │   │
│   │      Consumer-2 receives msg2, msg4                          │   │
│   │                                                              │   │
│   │  T1: Consumer-2 finishes msg2                                │   │
│   │  T2: Consumer-2 finishes msg4                                │   │
│   │  T3: Consumer-1 finishes msg1  ← msg1이 msg2보다 늦게 완료!  │   │
│   │                                                              │   │
│   │  처리 완료 순서: msg2 → msg4 → msg1 → msg3 → msg5            │   │
│   │  원래 순서:      msg1 → msg2 → msg3 → msg4 → msg5            │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 9.2 Kafka와 비교

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Redis Stream vs Kafka 순서 보장                    │
│                                                                      │
│   ┌─────────────────────┬───────────────────┬─────────────────────┐ │
│   │        특성          │   Redis Stream    │       Kafka         │ │
│   ├─────────────────────┼───────────────────┼─────────────────────┤ │
│   │ 기본 순서 보장       │ Entry ID 순       │ Offset 순           │ │
│   │ 파티션 개념          │ 없음 (단일 Stream)│ 있음 (Partition)    │ │
│   │ Consumer Group 내   │ 순서 보장 안됨    │ 파티션 내 순서 보장 │ │
│   │ 순서 보장            │                   │                     │ │
│   │ Key 기반 파티셔닝    │ 없음              │ 있음                │ │
│   │ 수평 확장 시 순서    │ 보장 안됨        │ 파티션 내 보장      │ │
│   └─────────────────────┴───────────────────┴─────────────────────┘ │
│                                                                      │
│   핵심 차이:                                                         │
│   • Kafka: 같은 Key → 같은 Partition → 같은 Consumer → 순서 보장    │
│   • Redis: Consumer Group 내 Round-Robin 분배 → 순서 보장 안됨      │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 9.3 전략 1: 단일 Consumer (완전한 순서 보장)

```
┌─────────────────────────────────────────────────────────────────────┐
│                      전략 1: 단일 Consumer                            │
│                                                                      │
│   개념:                                                              │
│   • 하나의 Consumer만 메시지 처리                                    │
│   • 완벽한 순서 보장                                                 │
│   • 처리량 제한 (수평 확장 불가)                                      │
│                                                                      │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │  Stream: [msg1] [msg2] [msg3] [msg4] [msg5]                  │   │
│   │              │                                               │   │
│   │              ▼                                               │   │
│   │       ┌─────────────┐                                        │   │
│   │       │  Consumer   │  ← 단일 Consumer가 순차 처리           │   │
│   │       │  (single)   │                                        │   │
│   │       └─────────────┘                                        │   │
│   │              │                                               │   │
│   │       처리 순서: msg1 → msg2 → msg3 → msg4 → msg5 ✓          │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│   적합한 케이스:                                                     │
│   • 처리량이 낮은 경우 (초당 수백 건 이하)                           │
│   • 순서가 절대적으로 중요한 경우                                    │
│   • 단순한 시스템 구조가 필요한 경우                                  │
│                                                                      │
│   부적합한 케이스:                                                   │
│   • 고처리량 요구 (초당 수천 건 이상)                                │
│   • 고가용성 필요 (단일 장애점)                                      │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

```java
/**
 * 전략 1: 단일 Consumer 패턴
 */
@Configuration
public class SingleConsumerConfig {

    @Bean
    public Subscription singleConsumerSubscription(
            StreamMessageListenerContainer<String, MapRecord<String, String, String>> container,
            OrderEventConsumer consumer,
            StringRedisTemplate redisTemplate) {

        String streamKey = "orders";
        String groupName = "order-processors";
        // 모든 인스턴스가 같은 Consumer 이름 사용 → 실제로는 하나만 활성
        String consumerName = "single-consumer";

        createConsumerGroupIfNotExists(redisTemplate, streamKey, groupName);

        // COUNT 1: 한 번에 하나씩만 처리
        Subscription subscription = container.receive(
                Consumer.from(groupName, consumerName),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()),
                consumer
        );

        container.start();
        return subscription;
    }
}
```

### 9.4 전략 2: Key 기반 파티셔닝 (Kafka 스타일)

```
┌─────────────────────────────────────────────────────────────────────┐
│                  전략 2: Key 기반 파티셔닝                            │
│                                                                      │
│   개념:                                                              │
│   • 같은 Key의 이벤트는 같은 Stream으로 라우팅                        │
│   • 각 Stream에 전용 Consumer 할당                                   │
│   • Key 단위로 순서 보장                                             │
│                                                                      │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                                                              │   │
│   │   Producer                                                   │   │
│   │      │                                                       │   │
│   │      │ hash(orderId) % 3                                     │   │
│   │      ▼                                                       │   │
│   │   ┌─────────────────────────────────────────────────────┐   │   │
│   │   │                    Router                            │   │   │
│   │   └─────────────────────────────────────────────────────┘   │   │
│   │         │              │              │                     │   │
│   │         ▼              ▼              ▼                     │   │
│   │   ┌──────────┐   ┌──────────┐   ┌──────────┐               │   │
│   │   │ Stream-0 │   │ Stream-1 │   │ Stream-2 │               │   │
│   │   │ order-1  │   │ order-2  │   │ order-3  │               │   │
│   │   │ order-4  │   │ order-5  │   │ order-6  │               │   │
│   │   │ order-7  │   │ order-8  │   │ order-9  │               │   │
│   │   └──────────┘   └──────────┘   └──────────┘               │   │
│   │         │              │              │                     │   │
│   │         ▼              ▼              ▼                     │   │
│   │   ┌──────────┐   ┌──────────┐   ┌──────────┐               │   │
│   │   │Consumer-0│   │Consumer-1│   │Consumer-2│               │   │
│   │   └──────────┘   └──────────┘   └──────────┘               │   │
│   │                                                              │   │
│   │   order-1, order-4, order-7은 항상 Consumer-0이 처리         │   │
│   │   → 해당 주문의 이벤트 순서 보장 ✓                           │   │
│   │                                                              │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

```java
/**
 * 전략 2: Key 기반 파티셔닝 구현
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PartitionedEventProducer {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final int PARTITION_COUNT = 3;
    private static final String STREAM_PREFIX = "orders:partition:";

    /**
     * Key(orderId) 기반으로 파티션 선택하여 발행
     * 같은 orderId의 이벤트는 항상 같은 파티션으로
     */
    public String publish(String orderId, Object event) {
        // 1. 파티션 결정 (consistent hashing)
        int partition = Math.abs(orderId.hashCode()) % PARTITION_COUNT;
        String streamKey = STREAM_PREFIX + partition;

        // 2. 메시지 발행
        Map<String, String> message = new HashMap<>();
        message.put("orderId", orderId);
        message.put("eventType", event.getClass().getSimpleName());
        message.put("payload", serialize(event));
        message.put("timestamp", Instant.now().toString());

        RecordId recordId = redisTemplate.opsForStream()
                .add(StreamRecords.string(message).withStreamKey(streamKey));

        log.debug("Published to partition {}: orderId={}, id={}",
                partition, orderId, recordId.getValue());

        return recordId.getValue();
    }

    /**
     * 모든 파티션의 Consumer 설정
     */
    @Bean
    public List<Subscription> partitionedSubscriptions(
            StreamMessageListenerContainer<String, MapRecord<String, String, String>> container,
            PartitionedEventConsumer consumer) {

        List<Subscription> subscriptions = new ArrayList<>();

        for (int i = 0; i < PARTITION_COUNT; i++) {
            String streamKey = STREAM_PREFIX + i;
            String groupName = "order-processors";
            String consumerName = "partition-consumer-" + i;

            createConsumerGroupIfNotExists(streamKey, groupName);

            Subscription subscription = container.receive(
                    Consumer.from(groupName, consumerName),
                    StreamOffset.create(streamKey, ReadOffset.lastConsumed()),
                    consumer
            );

            subscriptions.add(subscription);
        }

        container.start();
        return subscriptions;
    }

    private String serialize(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
```

### 9.5 전략 3: Lua Script 시퀀스 검증

```
┌─────────────────────────────────────────────────────────────────────┐
│                   전략 3: Lua Script 시퀀스 검증                      │
│                                                                      │
│   개념:                                                              │
│   • 이벤트에 시퀀스 번호 포함                                        │
│   • 처리 시 이전 시퀀스와 비교                                       │
│   • 순서가 맞지 않으면 처리 거부 (재시도 대기)                        │
│                                                                      │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                                                              │   │
│   │   Event: {orderId: "001", seq: 3, type: "PAID"}              │   │
│   │                                                              │   │
│   │   처리 전 검증:                                               │   │
│   │   ┌────────────────────────────────────────────────────┐    │   │
│   │   │  current_seq = GET order:001:last_seq              │    │   │
│   │   │  if current_seq != 2:  ← seq 3을 처리하려면 2여야 함│    │   │
│   │   │      return REJECT  (나중에 재처리)                 │    │   │
│   │   │  else:                                              │    │   │
│   │   │      process()                                      │    │   │
│   │   │      SET order:001:last_seq 3                       │    │   │
│   │   │      return SUCCESS                                 │    │   │
│   │   └────────────────────────────────────────────────────┘    │   │
│   │                                                              │   │
│   │   Lua Script로 원자적 수행 (Race Condition 방지)             │   │
│   │                                                              │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

```java
/**
 * 전략 3: Lua Script 시퀀스 검증
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SequenceValidatingConsumer {

    private final StringRedisTemplate redisTemplate;

    // Lua Script: 시퀀스 검증 + 업데이트를 원자적으로 수행
    private static final String SEQUENCE_CHECK_SCRIPT = """
        local key = KEYS[1]
        local expected_seq = tonumber(ARGV[1]) - 1
        local new_seq = ARGV[1]

        local current_seq = redis.call('GET', key)
        if current_seq == false then
            current_seq = 0
        else
            current_seq = tonumber(current_seq)
        end

        if current_seq == expected_seq then
            redis.call('SET', key, new_seq)
            return 1  -- SUCCESS
        else
            return 0  -- REJECT (out of order)
        end
        """;

    private final RedisScript<Long> sequenceCheckScript = new DefaultRedisScript<>(
            SEQUENCE_CHECK_SCRIPT, Long.class);

    /**
     * 시퀀스 검증 후 처리
     */
    public ProcessResult processWithSequenceValidation(OrderEvent event) {
        String seqKey = "order:" + event.getOrderId() + ":last_seq";
        long eventSeq = event.getSequence();

        // 1. Lua Script로 시퀀스 검증 + 업데이트 (원자적)
        Long result = redisTemplate.execute(
                sequenceCheckScript,
                List.of(seqKey),
                String.valueOf(eventSeq)
        );

        if (result != null && result == 1) {
            // 2. 순서 맞음 → 처리 진행
            log.info("Processing in-order event: orderId={}, seq={}",
                    event.getOrderId(), eventSeq);
            processEvent(event);
            return ProcessResult.SUCCESS;
        } else {
            // 3. 순서 안 맞음 → 나중에 재처리
            log.warn("Out-of-order event, will retry: orderId={}, seq={}",
                    event.getOrderId(), eventSeq);
            return ProcessResult.RETRY_LATER;
        }
    }

    /**
     * Consumer에서 사용
     */
    public void onMessage(MapRecord<String, String, String> message) {
        OrderEvent event = parseEvent(message);

        ProcessResult result = processWithSequenceValidation(event);

        if (result == ProcessResult.SUCCESS) {
            // ACK
            redisTemplate.opsForStream()
                    .acknowledge("stream", "group", message.getId());
        }
        // RETRY_LATER인 경우 ACK 안함 → PEL에 남음 → 나중에 XCLAIM
    }

    private void processEvent(OrderEvent event) {
        // 비즈니스 로직
    }

    private OrderEvent parseEvent(MapRecord<String, String, String> message) {
        // 파싱 로직
        return null;
    }

    enum ProcessResult {
        SUCCESS, RETRY_LATER
    }
}
```

### 9.6 전략 4: 버퍼링 및 정렬

```
┌─────────────────────────────────────────────────────────────────────┐
│                    전략 4: 버퍼링 및 정렬                             │
│                                                                      │
│   개념:                                                              │
│   • 일정 시간/개수만큼 이벤트 수집                                   │
│   • 버퍼 내에서 정렬 후 순차 처리                                    │
│   • 마이크로 배치 방식                                               │
│                                                                      │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                                                              │   │
│   │   Stream: [msg5] [msg2] [msg4] [msg1] [msg3]                 │   │
│   │              │                                               │   │
│   │              ▼                                               │   │
│   │   ┌──────────────────────────────────────────────────┐      │   │
│   │   │              Buffer (5초 또는 100개)               │      │   │
│   │   │                                                   │      │   │
│   │   │   [msg5, msg2, msg4, msg1, msg3]                  │      │   │
│   │   │                                                   │      │   │
│   │   │   정렬 후: [msg1, msg2, msg3, msg4, msg5]         │      │   │
│   │   └──────────────────────────────────────────────────┘      │   │
│   │              │                                               │   │
│   │              ▼                                               │   │
│   │   순차 처리: msg1 → msg2 → msg3 → msg4 → msg5 ✓              │   │
│   │                                                              │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│   장점:                                                              │
│   • 구현이 상대적으로 단순                                           │
│   • 버퍼 내 완벽한 순서 보장                                         │
│                                                                      │
│   단점:                                                              │
│   • 처리 지연 발생 (버퍼 대기 시간)                                  │
│   • 버퍼 경계 문제 (버퍼 간 순서는 보장 안됨)                        │
│   • 메모리 사용량 증가                                               │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

```java
/**
 * 전략 4: 버퍼링 및 정렬 Consumer
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BufferingConsumer {

    private final StringRedisTemplate redisTemplate;
    private final OrderService orderService;

    private static final int BUFFER_SIZE = 100;
    private static final Duration BUFFER_TIMEOUT = Duration.ofSeconds(5);

    // Key별 버퍼
    private final ConcurrentMap<String, List<BufferedEvent>> buffers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Instant> bufferStartTimes = new ConcurrentHashMap<>();

    /**
     * 이벤트 수신 시 버퍼에 추가
     */
    public void onMessage(MapRecord<String, String, String> message) {
        String orderId = message.getValue().get("orderId");
        long sequence = Long.parseLong(message.getValue().get("sequence"));

        BufferedEvent event = new BufferedEvent(message, sequence);

        // 1. 버퍼에 추가
        buffers.computeIfAbsent(orderId, k -> new ArrayList<>()).add(event);
        bufferStartTimes.putIfAbsent(orderId, Instant.now());

        // 2. 플러시 조건 확인
        List<BufferedEvent> buffer = buffers.get(orderId);
        Instant startTime = bufferStartTimes.get(orderId);

        boolean sizeReached = buffer.size() >= BUFFER_SIZE;
        boolean timeoutReached = Duration.between(startTime, Instant.now())
                .compareTo(BUFFER_TIMEOUT) >= 0;

        if (sizeReached || timeoutReached) {
            flushBuffer(orderId);
        }
    }

    /**
     * 버퍼 플러시: 정렬 후 순차 처리
     */
    private synchronized void flushBuffer(String orderId) {
        List<BufferedEvent> buffer = buffers.remove(orderId);
        bufferStartTimes.remove(orderId);

        if (buffer == null || buffer.isEmpty()) {
            return;
        }

        // 1. 시퀀스 번호로 정렬
        buffer.sort(Comparator.comparingLong(BufferedEvent::sequence));

        log.info("Flushing buffer: orderId={}, count={}", orderId, buffer.size());

        // 2. 순차 처리
        for (BufferedEvent event : buffer) {
            try {
                processEvent(event);

                // 3. ACK
                redisTemplate.opsForStream().acknowledge(
                        "orders", "order-processors", event.message().getId());

            } catch (Exception e) {
                log.error("Failed to process: orderId={}, seq={}",
                        orderId, event.sequence(), e);
                // 실패한 것은 ACK 안함
            }
        }
    }

    /**
     * 주기적으로 타임아웃된 버퍼 플러시
     */
    @Scheduled(fixedRate = 1000)
    public void flushTimeoutBuffers() {
        Instant now = Instant.now();

        bufferStartTimes.forEach((orderId, startTime) -> {
            if (Duration.between(startTime, now).compareTo(BUFFER_TIMEOUT) >= 0) {
                flushBuffer(orderId);
            }
        });
    }

    private void processEvent(BufferedEvent event) {
        // 비즈니스 로직
    }

    record BufferedEvent(
            MapRecord<String, String, String> message,
            long sequence
    ) {}
}
```

### 9.7 순서 보장 전략 선택 가이드

```
┌─────────────────────────────────────────────────────────────────────┐
│                    순서 보장 전략 선택 가이드                          │
│                                                                      │
│   [결정 트리]                                                         │
│                                                                      │
│   처리량 요구사항은?                                                  │
│        │                                                             │
│        ├─ 낮음 (< 1000 TPS)                                         │
│        │       │                                                     │
│        │       └─ 순서가 절대적으로 중요?                            │
│        │               │                                             │
│        │               ├─ Yes → 전략 1: 단일 Consumer                │
│        │               └─ No  → Consumer Group 기본 사용             │
│        │                                                             │
│        └─ 높음 (> 1000 TPS)                                         │
│                │                                                     │
│                └─ 순서 보장 단위는?                                   │
│                        │                                             │
│                        ├─ 전체 순서 필요 → 전략 4: 버퍼링             │
│                        │   (지연 허용 시)                            │
│                        │                                             │
│                        └─ Key별 순서 필요 → 전략 2: 파티셔닝          │
│                            │                                         │
│                            └─ 추가 검증 필요? → 전략 3: 시퀀스 검증   │
│                                                                      │
│   [전략별 특성 비교]                                                  │
│   ┌─────────────┬────────────┬────────────┬────────────┬──────────┐ │
│   │    전략     │   처리량   │  순서 보장  │   복잡도   │   지연   │ │
│   ├─────────────┼────────────┼────────────┼────────────┼──────────┤ │
│   │ 단일Consumer│    낮음    │    완벽    │    낮음    │   낮음   │ │
│   │ 파티셔닝    │    높음    │  Key별 보장 │    중간    │   낮음   │ │
│   │ 시퀀스검증  │    중간    │  Key별 보장 │    높음    │   중간   │ │
│   │ 버퍼링      │    중간    │  버퍼 내   │    중간    │   높음   │ │
│   └─────────────┴────────────┴────────────┴────────────┴──────────┘ │
│                                                                      │
│   [권장 조합]                                                         │
│   • 대부분의 경우: 전략 2 (파티셔닝) + 전략 3 (시퀀스 검증)          │
│   • 단순한 시스템: 전략 1 (단일 Consumer)                            │
│   • 배치 처리: 전략 4 (버퍼링)                                       │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 10. 실전 패턴: 주문 이벤트 처리

### 10.1 전체 아키텍처

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

### 10.2 주문 서비스 구현

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

### 10.3 재고 Consumer 구현

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

## 11. 모니터링 및 운영

### 11.1 스트림 상태 확인 API

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

### 11.2 메트릭 수집

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

## 12. 실습 과제

### 12.1 과제 1: 기본 Stream 구현
1. Redis Stream 생성 및 메시지 발행
2. Consumer로 메시지 읽기
3. Consumer Group 설정 및 분산 처리

### 12.2 과제 2: 주문 이벤트 시스템
1. 주문 생성 시 이벤트 발행
2. 재고 서비스에서 이벤트 구독
3. 결제 서비스에서 이벤트 구독

### 12.3 과제 3: 에러 처리
1. Pending 메시지 모니터링 구현
2. Dead Letter Queue 구현
3. 재시도 로직 구현

### 12.4 체크리스트
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
