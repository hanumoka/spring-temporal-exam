# CDC (Change Data Capture) with Debezium

## 이 문서에서 배우는 것

- CDC(Change Data Capture)의 개념과 필요성
- Polling 방식의 한계와 CDC의 장점
- Debezium Server + Redis Stream Sink 구성
- Outbox Event Router SMT 적용
- Polling에서 CDC로 전환하는 방법

---

## 선행 학습

> **중요**: 이 문서는 [04-outbox-pattern.md](./04-outbox-pattern.md)을 먼저 학습한 후 진행해야 합니다.
> Outbox 패턴의 Polling 방식을 먼저 구현하고, 그 한계를 체험한 후 CDC로 전환하는 것을 권장합니다.

---

## 1. Polling 방식의 한계

### 1.1 현재 구조 (Polling)

```
┌─────────────────────────────────────────────────────────────────────┐
│                       Polling 방식의 한계                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   [Service]                                                          │
│       │                                                              │
│       │ 트랜잭션                                                     │
│       ▼                                                              │
│   ┌─────────────────────────────────────────────────┐               │
│   │  MySQL                                           │               │
│   │  └── outbox_event 테이블                         │               │
│   └──────────────────────┬──────────────────────────┘               │
│                          │                                           │
│                          │ ⚠️ Polling (SELECT ... WHERE status='PENDING')
│                          │    └── 1초마다 실행                       │
│                          │    └── DB 부하 발생                       │
│                          │    └── 폴링 주기만큼 지연                 │
│                          ▼                                           │
│   ┌─────────────────────────────────────────────────┐               │
│   │  Message Relay (Spring Scheduler)               │               │
│   └──────────────────────┬──────────────────────────┘               │
│                          │                                           │
│                          ▼                                           │
│   ┌─────────────────────────────────────────────────┐               │
│   │  Redis Stream                                    │               │
│   └─────────────────────────────────────────────────┘               │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 1.2 Polling 방식의 문제점

| 문제 | 설명 |
|------|------|
| **지연** | 폴링 주기(예: 1초)만큼 이벤트 발행 지연 |
| **DB 부하** | 주기적인 SELECT 쿼리로 DB 리소스 소모 |
| **확장성** | 이벤트 양 증가 시 폴링 빈도 증가 → 부하 증가 |
| **순서 보장** | 추가적인 처리 필요 (ORDER BY, 락 등) |

---

## 2. CDC (Change Data Capture)란?

### 2.1 개념

**CDC**는 데이터베이스의 변경 사항을 실시간으로 캡처하여 다른 시스템에 전달하는 패턴입니다.

```
┌─────────────────────────────────────────────────────────────────────┐
│                       CDC 방식 (Debezium)                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   [Service]                                                          │
│       │                                                              │
│       │ 트랜잭션                                                     │
│       ▼                                                              │
│   ┌─────────────────────────────────────────────────┐               │
│   │  MySQL (binlog 활성화)                           │               │
│   │  └── outbox_event 테이블                         │               │
│   └──────────────────────┬──────────────────────────┘               │
│                          │                                           │
│                          │ ✅ Binlog 캡처 (실시간)                   │
│                          │    └── DB 폴링 없음                       │
│                          │    └── ~100ms 이내 전달                   │
│                          │    └── 순서 자동 보장                     │
│                          ▼                                           │
│   ┌─────────────────────────────────────────────────┐               │
│   │  Debezium Server                                 │               │
│   │  └── Outbox Event Router SMT                    │               │
│   └──────────────────────┬──────────────────────────┘               │
│                          │                                           │
│                          ▼                                           │
│   ┌─────────────────────────────────────────────────┐               │
│   │  Redis Stream                                    │               │
│   └─────────────────────────────────────────────────┘               │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 Polling vs CDC 비교

| 항목 | Polling | CDC (Debezium) |
|------|---------|----------------|
| **캡처 방식** | 주기적 SELECT | binlog 실시간 읽기 |
| **지연** | 폴링 주기 (1초~) | ~100ms |
| **DB 부하** | 있음 (쿼리 실행) | 없음 (로그 읽기) |
| **순서 보장** | 추가 처리 필요 | 자동 (binlog 순서) |
| **인프라** | 없음 | Debezium Server |
| **복잡도** | 낮음 | 중간 |

---

## 3. Debezium 소개

### 3.1 Debezium이란?

**Debezium**은 Red Hat이 개발한 오픈소스 CDC 플랫폼입니다.

- 다양한 DB 지원: MySQL, PostgreSQL, MongoDB, Oracle, SQL Server 등
- 다양한 Sink 지원: Kafka, Redis, Pulsar, Kinesis 등
- Outbox Event Router SMT 내장

### 3.2 Debezium Server

Kafka 없이도 CDC를 사용할 수 있는 독립 실행형 서버입니다.

```
┌───────────────────────────────────────────────────────────────┐
│                      Debezium Server                           │
│                                                                │
│   ┌─────────────┐    ┌─────────────┐    ┌─────────────┐       │
│   │   Source    │    │    SMT      │    │    Sink     │       │
│   │  Connector  │───▶│ (Transform) │───▶│  Connector  │       │
│   │  (MySQL)    │    │             │    │   (Redis)   │       │
│   └─────────────┘    └─────────────┘    └─────────────┘       │
│                                                                │
└───────────────────────────────────────────────────────────────┘
```

---

## 4. 환경 구성

### 4.1 MySQL binlog 활성화

```yaml
# docker-compose.yml
services:
  mysql:
    image: mysql:8.0
    command: >
      --server-id=1
      --log-bin=mysql-bin
      --binlog-format=ROW
      --binlog-row-image=FULL
      --gtid-mode=ON
      --enforce-gtid-consistency=ON
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: order_db
    ports:
      - "3306:3306"
```

### 4.2 Debezium 전용 사용자 생성

```sql
-- Debezium CDC 사용자 생성
CREATE USER 'debezium'@'%' IDENTIFIED BY 'dbz';

-- 필요한 권한 부여
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'debezium'@'%';

-- 특정 DB에 대한 권한 (outbox 테이블 읽기)
GRANT SELECT ON order_db.* TO 'debezium'@'%';

FLUSH PRIVILEGES;
```

### 4.3 Debezium Server 설정

```yaml
# docker-compose.yml에 추가
services:
  debezium:
    image: debezium/server:3.0
    ports:
      - "8083:8080"
    volumes:
      - ./debezium/application.properties:/debezium/conf/application.properties
    depends_on:
      - mysql
      - redis
```

```properties
# debezium/application.properties

# Sink 설정 (Redis Stream)
debezium.sink.type=redis
debezium.sink.redis.address=redis:6379
debezium.sink.redis.memory.limit.mb=100
debezium.sink.redis.skip.heartbeat.messages=true

# Source 설정 (MySQL)
debezium.source.connector.class=io.debezium.connector.mysql.MySqlConnector
debezium.source.offset.storage=org.apache.kafka.connect.storage.FileOffsetBackingStore
debezium.source.offset.storage.file.filename=/debezium/data/offsets.dat
debezium.source.offset.flush.interval.ms=0

# MySQL 연결 정보
debezium.source.database.hostname=mysql
debezium.source.database.port=3306
debezium.source.database.user=debezium
debezium.source.database.password=dbz
debezium.source.database.server.id=1
debezium.source.database.server.name=mysql
debezium.source.database.include.list=order_db

# Outbox 테이블만 캡처
debezium.source.table.include.list=order_db.outbox_event

# Outbox Event Router SMT
debezium.transforms=outbox
debezium.transforms.outbox.type=io.debezium.transforms.outbox.EventRouter
debezium.transforms.outbox.table.field.event.key=aggregate_id
debezium.transforms.outbox.table.field.event.type=event_type
debezium.transforms.outbox.table.field.event.payload=payload
debezium.transforms.outbox.route.by.field=aggregate_type
```

---

## 5. Outbox Event Router SMT

### 5.1 SMT (Single Message Transform)란?

메시지가 Sink로 전달되기 전에 변환하는 기능입니다.

### 5.2 Outbox Event Router 동작

```
[Outbox 테이블 INSERT]
    │
    ▼
┌─────────────────────────────────────────┐
│  Before SMT (Raw Change Event)          │
│  {                                       │
│    "op": "c",                           │
│    "after": {                           │
│      "id": 1,                           │
│      "aggregate_type": "Order",         │
│      "aggregate_id": "order-123",       │
│      "event_type": "OrderCreated",      │
│      "payload": "{...}"                 │
│    }                                     │
│  }                                       │
└────────────────┬────────────────────────┘
                 │ Outbox Event Router SMT
                 ▼
┌─────────────────────────────────────────┐
│  After SMT (Clean Event)                │
│  {                                       │
│    "key": "order-123",                  │
│    "value": {                           │
│      "eventType": "OrderCreated",       │
│      "payload": {...}                   │
│    }                                     │
│  }                                       │
│                                          │
│  Topic/Stream: "outbox.event.Order"     │
└─────────────────────────────────────────┘
```

---

## 6. Polling 코드 제거

### 6.1 제거할 코드

CDC 전환 후 더 이상 필요 없는 코드:

```java
// ❌ 제거: Message Relay (Polling)
@Component
@RequiredArgsConstructor
public class OutboxMessageRelay {

    // 더 이상 필요 없음 - Debezium이 대체
    @Scheduled(fixedDelay = 1000)
    public void pollAndPublish() {
        // ...
    }
}
```

### 6.2 유지할 코드

Outbox 테이블에 INSERT하는 코드는 그대로 유지:

```java
// ✅ 유지: Outbox 테이블 INSERT
@Transactional
public void createOrder(Order order) {
    // 비즈니스 로직
    orderRepository.save(order);

    // Outbox 테이블에 이벤트 저장 (Debezium이 캡처)
    outboxRepository.save(OutboxEvent.builder()
        .aggregateType("Order")
        .aggregateId(order.getId().toString())
        .eventType("OrderCreated")
        .payload(objectMapper.writeValueAsString(order))
        .build());
}
```

---

## 7. Redis Stream Consumer

### 7.1 Consumer 구현

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventConsumer {

    private final RedisTemplate<String, String> redisTemplate;
    private final NotificationService notificationService;

    @PostConstruct
    public void startConsuming() {
        // Consumer Group 생성
        try {
            redisTemplate.opsForStream().createGroup("outbox.event.Order", "notification-group");
        } catch (Exception e) {
            log.debug("Consumer group already exists");
        }
    }

    @Scheduled(fixedDelay = 100)
    public void consume() {
        List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream()
            .read(Consumer.from("notification-group", "consumer-1"),
                  StreamReadOptions.empty().count(10),
                  StreamOffset.create("outbox.event.Order", ReadOffset.lastConsumed()));

        for (MapRecord<String, Object, Object> message : messages) {
            try {
                processEvent(message);
                // ACK
                redisTemplate.opsForStream().acknowledge("outbox.event.Order", "notification-group", message.getId());
            } catch (Exception e) {
                log.error("Failed to process event: {}", message.getId(), e);
            }
        }
    }

    private void processEvent(MapRecord<String, Object, Object> message) {
        // 이벤트 처리 로직
        String eventType = (String) message.getValue().get("eventType");
        String payload = (String) message.getValue().get("payload");

        log.info("Received event: type={}, payload={}", eventType, payload);

        // 알림 발송 등
        notificationService.sendNotification(eventType, payload);
    }
}
```

---

## 8. 모니터링

### 8.1 Debezium 메트릭

```yaml
# Prometheus 스크래핑 설정
- job_name: 'debezium'
  static_configs:
    - targets: ['debezium:8080']
```

### 8.2 주요 모니터링 포인트

| 메트릭 | 설명 |
|--------|------|
| `debezium_mysql_connector_streaming_milliseconds_behind_source` | binlog 지연 |
| `debezium_mysql_connector_total_changes_counter` | 캡처된 변경 수 |
| `redis_stream_length` | Redis Stream 길이 |

---

## 9. 트러블슈팅

### 9.1 binlog 위치 초기화

```bash
# Debezium offset 초기화
docker-compose exec debezium rm /debezium/data/offsets.dat
docker-compose restart debezium
```

### 9.2 MySQL binlog 확인

```sql
-- binlog 활성화 확인
SHOW VARIABLES LIKE 'log_bin';

-- binlog 포맷 확인
SHOW VARIABLES LIKE 'binlog_format';

-- binlog 파일 목록
SHOW BINARY LOGS;
```

---

## 10. 실습 과제

### Step 1: Polling 방식 구현 (필수)

1. Outbox 테이블 생성
2. Spring Scheduler로 Polling 구현
3. Redis Stream으로 이벤트 발행
4. **한계 체험**: 1초 지연, DB 쿼리 로그 확인

### Step 2: CDC 전환 (선택)

5. MySQL binlog 활성화
6. Debezium 전용 사용자 생성
7. Debezium Server 설정 및 실행
8. Polling 코드 제거
9. **비교**: 지연 시간, DB 부하 비교

---

## 참고 자료

- [Debezium 공식 문서](https://debezium.io/documentation/)
- [Debezium Redis Sink](https://debezium.io/documentation/reference/stable/operations/debezium-server.html#_redis_stream)
- [Outbox Event Router](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html)
- [Debezium + Spring Boot 예제](https://github.com/YunusEmreNalbant/transactional-outbox-pattern-with-debezium)
- [DZone - Debezium Server with Redis Stream](https://dzone.com/articles/debezium-server-with-postgresql-and-redis-stream)

---

## 다음 단계

[05-opentelemetry-tempo.md](./05-opentelemetry-tempo.md) - 분산 추적으로 이동

> CDC로 발행된 이벤트가 어떻게 처리되는지 추적하려면 분산 추적이 필요합니다.
