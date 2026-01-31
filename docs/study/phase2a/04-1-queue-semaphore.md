# 대기열 + 세마포어 조합 (Queue + Semaphore)

## 이 문서에서 배우는 것

- 세마포어만 사용할 때의 한계점 이해
- 대기열 + 세마포어 조합 패턴 학습
- Redis List/Stream을 활용한 버퍼링 구현
- Temporal이 이 패턴을 어떻게 자동화하는지 이해

---

## 1. 왜 대기열 + 세마포어 조합이 필요한가?

### 세마포어만 사용할 때의 문제

```
┌─────────────────────────────────────────────────────────────────────┐
│                    RSemaphore만 사용할 때                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [요청 폭주 상황]                                                    │
│                                                                      │
│  요청 1  ──▶ RSemaphore (permits=10) ──▶ PG API ✓                   │
│  요청 2  ──▶ RSemaphore              ──▶ PG API ✓                   │
│  ...                                                                 │
│  요청 10 ──▶ RSemaphore              ──▶ PG API ✓                   │
│  요청 11 ──▶ RSemaphore (5초 대기)   ──▶ 실패! 503 반환 ❌           │
│  요청 12 ──▶ RSemaphore (5초 대기)   ──▶ 실패! 503 반환 ❌           │
│  ...                                                                 │
│  요청 100 ──▶ RSemaphore (5초 대기)  ──▶ 실패! 503 반환 ❌           │
│                                                                      │
│  [문제]                                                              │
│  - waitTime 내에 permit 획득 실패 시 요청 거절                       │
│  - 사용자 경험 저하 (결제 실패 메시지)                               │
│  - 트래픽 폭주 시 대량의 요청 거절                                   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 대기열 + 세마포어 조합의 해결책

```
┌─────────────────────────────────────────────────────────────────────┐
│                    대기열 + 세마포어 조합                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [요청 폭주 상황]                                                    │
│                                                                      │
│  요청 1   ──▶ Queue ──▶ Consumer ──▶ Semaphore ──▶ PG API ✓        │
│  요청 2   ──▶ Queue ──▶ Consumer ──▶ Semaphore ──▶ PG API ✓        │
│  ...                                                                 │
│  요청 10  ──▶ Queue ──▶ Consumer ──▶ Semaphore ──▶ PG API ✓        │
│  요청 11  ──▶ Queue ──▶ (대기 중...)                                 │
│  요청 12  ──▶ Queue ──▶ (대기 중...)                                 │
│  ...                                                                 │
│  요청 100 ──▶ Queue ──▶ (대기 중...)                                 │
│                                                                      │
│  [이후 순차 처리]                                                    │
│  요청 11 ──▶ Semaphore 획득 ──▶ PG API ✓                            │
│  요청 12 ──▶ Semaphore 획득 ──▶ PG API ✓                            │
│  ...                                                                 │
│                                                                      │
│  [효과]                                                              │
│  - 요청 거절 없음 (버퍼링)                                           │
│  - 순차 처리로 외부 API Rate Limit 준수                              │
│  - 사용자에게 "처리 중" 상태 제공 가능                               │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 2. 패턴 비교

### 2.1 동기 방식 vs 비동기 방식

| 방식 | 세마포어만 | 대기열 + 세마포어 |
|------|----------|------------------|
| 처리 방식 | 동기 (즉시 응답) | 비동기 (나중에 처리) |
| 실패 시 | 503 반환 | 대기열에 적재 |
| 응답 시간 | 즉시 (성공/실패) | 즉시 (접수 완료) + 나중에 결과 |
| 적합한 경우 | 짧은 대기 허용 | 트래픽 폭주 대응 |
| 구현 복잡도 | 낮음 | 높음 |

### 2.2 언제 어떤 패턴을 사용하나?

```
┌─────────────────────────────────────────────────────────────────────┐
│                       패턴 선택 가이드                                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [세마포어만 사용]                                                   │
│  └── 조건:                                                          │
│      ├── 트래픽이 예측 가능                                          │
│      ├── 짧은 대기(5초 이내)로 처리 가능                             │
│      └── 실패 시 클라이언트가 재시도 가능                            │
│  └── 예: 일반적인 API 호출, 내부 서비스 통신                         │
│                                                                      │
│  [대기열 + 세마포어]                                                 │
│  └── 조건:                                                          │
│      ├── 트래픽 폭주 가능성 있음                                     │
│      ├── 요청 거절이 비즈니스에 큰 영향                              │
│      └── 비동기 처리가 허용됨                                        │
│  └── 예: 결제 처리, 주문 처리, 배치 작업                             │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 3. 구현 방식

### 3.1 아키텍처 개요

```
┌─────────────────────────────────────────────────────────────────────┐
│                    대기열 + 세마포어 아키텍처                          │
│                                                                      │
│  ┌──────────────┐                                                   │
│  │   Client     │                                                   │
│  │  (요청 발생)  │                                                   │
│  └──────┬───────┘                                                   │
│         │ POST /payments                                            │
│         ▼                                                           │
│  ┌──────────────┐                                                   │
│  │  API Server  │                                                   │
│  │  (Producer)  │                                                   │
│  └──────┬───────┘                                                   │
│         │ LPUSH / XADD                                              │
│         ▼                                                           │
│  ┌──────────────────────────────────────────┐                       │
│  │              Redis                        │                       │
│  │  ┌────────────────────────────────────┐  │                       │
│  │  │  Queue (List 또는 Stream)           │  │                       │
│  │  │  [req-1] [req-2] [req-3] ...       │  │                       │
│  │  └────────────────────────────────────┘  │                       │
│  │                                          │                       │
│  │  ┌────────────────────────────────────┐  │                       │
│  │  │  RSemaphore (permits=10)           │  │                       │
│  │  └────────────────────────────────────┘  │                       │
│  └──────────────────────────────────────────┘                       │
│         ▲                                                           │
│         │ BRPOP / XREADGROUP + tryAcquire                           │
│         │                                                           │
│  ┌──────┴───────┐                                                   │
│  │  Consumer    │                                                   │
│  │  (Worker)    │──────────────▶ 외부 PG API                        │
│  └──────────────┘                                                   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2 Redis List 기반 구현

```java
/**
 * Producer: 요청을 대기열에 적재
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentQueueProducer {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String QUEUE_KEY = "queue:payment:pending";

    /**
     * 결제 요청을 대기열에 추가
     *
     * @return 요청 ID (추후 결과 조회용)
     */
    public String enqueue(PaymentRequest request) {
        String requestId = UUID.randomUUID().toString();

        QueuedPaymentRequest queued = new QueuedPaymentRequest(
            requestId,
            request,
            Instant.now()
        );

        try {
            String json = objectMapper.writeValueAsString(queued);
            redisTemplate.opsForList().leftPush(QUEUE_KEY, json);

            log.info("결제 요청 대기열 적재: requestId={}", requestId);
            return requestId;

        } catch (JsonProcessingException e) {
            throw new PaymentException("요청 직렬화 실패", e);
        }
    }

    /**
     * 대기열 크기 조회 (모니터링용)
     */
    public long getQueueSize() {
        Long size = redisTemplate.opsForList().size(QUEUE_KEY);
        return size != null ? size : 0;
    }
}

/**
 * Consumer: 대기열에서 요청을 가져와 처리
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentQueueConsumer {

    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final PaymentGateway paymentGateway;
    private final PaymentResultRepository resultRepository;
    private final ObjectMapper objectMapper;

    private static final String QUEUE_KEY = "queue:payment:pending";
    private static final String SEMAPHORE_KEY = "semaphore:pg:api";
    private static final int MAX_CONCURRENT = 10;

    @PostConstruct
    public void initSemaphore() {
        RSemaphore semaphore = redissonClient.getSemaphore(SEMAPHORE_KEY);
        semaphore.trySetPermits(MAX_CONCURRENT);
        log.info("PG 세마포어 초기화: permits={}", MAX_CONCURRENT);
    }

    /**
     * 대기열 폴링 및 처리 (스케줄러로 실행)
     */
    @Scheduled(fixedDelay = 100)  // 100ms 간격으로 폴링
    public void processQueue() {
        RSemaphore semaphore = redissonClient.getSemaphore(SEMAPHORE_KEY);

        // 세마포어 획득 가능할 때만 대기열에서 가져옴
        if (!semaphore.tryAcquire()) {
            return;  // 동시 처리 한도 도달, 다음 폴링까지 대기
        }

        try {
            // 대기열에서 요청 가져오기 (블로킹)
            String json = redisTemplate.opsForList().rightPop(QUEUE_KEY);

            if (json == null) {
                return;  // 대기열 비어있음
            }

            QueuedPaymentRequest queued = objectMapper.readValue(
                json, QueuedPaymentRequest.class
            );

            log.info("결제 요청 처리 시작: requestId={}, 대기시간={}ms",
                queued.getRequestId(),
                Duration.between(queued.getEnqueuedAt(), Instant.now()).toMillis());

            // 실제 결제 처리
            processPayment(queued);

        } catch (Exception e) {
            log.error("대기열 처리 오류", e);
        } finally {
            semaphore.release();
        }
    }

    private void processPayment(QueuedPaymentRequest queued) {
        try {
            PaymentResult result = paymentGateway.process(queued.getRequest());

            // 결과 저장 (클라이언트가 조회할 수 있도록)
            resultRepository.save(new PaymentResultEntity(
                queued.getRequestId(),
                PaymentStatus.SUCCESS,
                result.getTransactionId(),
                Instant.now()
            ));

            log.info("결제 완료: requestId={}, txId={}",
                queued.getRequestId(), result.getTransactionId());

        } catch (Exception e) {
            log.error("결제 실패: requestId={}", queued.getRequestId(), e);

            resultRepository.save(new PaymentResultEntity(
                queued.getRequestId(),
                PaymentStatus.FAILED,
                null,
                Instant.now(),
                e.getMessage()
            ));
        }
    }
}

/**
 * 결과 조회 API
 */
@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentQueueProducer producer;
    private final PaymentResultRepository resultRepository;

    /**
     * 결제 요청 (비동기)
     */
    @PostMapping("/payments")
    public ResponseEntity<PaymentAcceptedResponse> requestPayment(
            @RequestBody PaymentRequest request) {

        String requestId = producer.enqueue(request);

        return ResponseEntity
            .accepted()  // 202 Accepted
            .body(new PaymentAcceptedResponse(
                requestId,
                "결제 요청이 접수되었습니다.",
                "/payments/" + requestId + "/status"
            ));
    }

    /**
     * 결제 결과 조회 (폴링)
     */
    @GetMapping("/payments/{requestId}/status")
    public ResponseEntity<PaymentStatusResponse> getStatus(
            @PathVariable String requestId) {

        return resultRepository.findByRequestId(requestId)
            .map(result -> ResponseEntity.ok(PaymentStatusResponse.from(result)))
            .orElse(ResponseEntity.ok(PaymentStatusResponse.pending(requestId)));
    }
}
```

### 3.3 Redis Stream 기반 구현 (권장)

Redis Stream은 List보다 더 강력한 기능을 제공합니다:
- Consumer Group 지원 (다중 Consumer)
- 메시지 ACK
- 자동 재처리 (Pending List)

```java
/**
 * Producer: Redis Stream에 메시지 발행
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentStreamProducer {

    private final StringRedisTemplate redisTemplate;

    private static final String STREAM_KEY = "stream:payment";

    public String publish(PaymentRequest request) {
        String requestId = UUID.randomUUID().toString();

        Map<String, String> message = Map.of(
            "requestId", requestId,
            "orderId", request.getOrderId(),
            "amount", String.valueOf(request.getAmount()),
            "timestamp", Instant.now().toString()
        );

        RecordId recordId = redisTemplate.opsForStream()
            .add(StreamRecords.newRecord()
                .in(STREAM_KEY)
                .ofMap(message));

        log.info("결제 요청 발행: requestId={}, recordId={}", requestId, recordId);
        return requestId;
    }
}

/**
 * Consumer: Redis Stream 구독 및 처리
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentStreamConsumer {

    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final PaymentGateway paymentGateway;

    private static final String STREAM_KEY = "stream:payment";
    private static final String GROUP_NAME = "payment-processors";
    private static final String CONSUMER_NAME = "consumer-1";
    private static final String SEMAPHORE_KEY = "semaphore:pg:api";

    @PostConstruct
    public void initConsumerGroup() {
        try {
            redisTemplate.opsForStream().createGroup(STREAM_KEY, GROUP_NAME);
            log.info("Consumer Group 생성: {}", GROUP_NAME);
        } catch (Exception e) {
            // 이미 존재하는 경우 무시
            log.debug("Consumer Group 이미 존재: {}", GROUP_NAME);
        }

        // 세마포어 초기화
        RSemaphore semaphore = redissonClient.getSemaphore(SEMAPHORE_KEY);
        semaphore.trySetPermits(10);
    }

    @Scheduled(fixedDelay = 100)
    public void consume() {
        RSemaphore semaphore = redissonClient.getSemaphore(SEMAPHORE_KEY);

        // 세마포어 획득 시도
        if (!semaphore.tryAcquire()) {
            return;
        }

        try {
            // Stream에서 메시지 읽기
            List<MapRecord<String, Object, Object>> records =
                redisTemplate.opsForStream().read(
                    Consumer.from(GROUP_NAME, CONSUMER_NAME),
                    StreamReadOptions.empty().count(1).block(Duration.ofMillis(100)),
                    StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                );

            if (records == null || records.isEmpty()) {
                return;
            }

            MapRecord<String, Object, Object> record = records.get(0);
            processMessage(record);

            // ACK 전송
            redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, record.getId());

        } catch (Exception e) {
            log.error("Stream 처리 오류", e);
        } finally {
            semaphore.release();
        }
    }

    private void processMessage(MapRecord<String, Object, Object> record) {
        Map<Object, Object> data = record.getValue();
        String requestId = (String) data.get("requestId");

        log.info("결제 처리: requestId={}", requestId);

        // 실제 결제 로직...
        PaymentRequest request = PaymentRequest.builder()
            .orderId((String) data.get("orderId"))
            .amount(new BigDecimal((String) data.get("amount")))
            .build();

        paymentGateway.process(request);
    }
}
```

---

## 4. 다중 Consumer 확장

### 4.1 수평 확장 아키텍처

```
┌─────────────────────────────────────────────────────────────────────┐
│                    다중 Consumer 아키텍처                             │
│                                                                      │
│                      Redis Stream                                    │
│                 ┌─────────────────────┐                             │
│                 │ stream:payment       │                             │
│                 │ [msg-1][msg-2][msg-3]│                             │
│                 └──────────┬──────────┘                             │
│                            │                                         │
│              Consumer Group: payment-processors                      │
│                            │                                         │
│           ┌────────────────┼────────────────┐                       │
│           │                │                │                        │
│           ▼                ▼                ▼                        │
│    ┌────────────┐   ┌────────────┐   ┌────────────┐                 │
│    │ Consumer-1 │   │ Consumer-2 │   │ Consumer-3 │                 │
│    │ (Pod A)    │   │ (Pod B)    │   │ (Pod C)    │                 │
│    └─────┬──────┘   └─────┬──────┘   └─────┬──────┘                 │
│          │                │                │                         │
│          ▼                ▼                ▼                         │
│    ┌─────────────────────────────────────────────┐                  │
│    │           RSemaphore (permits=10)           │                  │
│    │         전체 Consumer 합계 10개 제한         │                  │
│    └─────────────────────────────────────────────┘                  │
│          │                │                │                         │
│          ▼                ▼                ▼                         │
│    ┌─────────────────────────────────────────────┐                  │
│    │               외부 PG API                    │                  │
│    │            Rate Limit: 10 TPS               │                  │
│    └─────────────────────────────────────────────┘                  │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.2 장점

| 특성 | 설명 |
|------|------|
| **수평 확장** | Consumer 인스턴스를 자유롭게 추가 가능 |
| **부하 분산** | Consumer Group이 메시지를 자동 분배 |
| **전역 Rate Limit** | RSemaphore가 전체 인스턴스의 동시 호출 제한 |
| **장애 복구** | 하나의 Consumer가 죽어도 다른 Consumer가 처리 |

---

## 5. 모니터링

### 5.1 주요 메트릭

```java
@Component
@RequiredArgsConstructor
public class QueueMetrics {

    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final MeterRegistry meterRegistry;

    private static final String QUEUE_KEY = "queue:payment:pending";
    private static final String SEMAPHORE_KEY = "semaphore:pg:api";

    @Scheduled(fixedRate = 5000)  // 5초마다 메트릭 수집
    public void collectMetrics() {
        // 대기열 크기
        Long queueSize = redisTemplate.opsForList().size(QUEUE_KEY);
        meterRegistry.gauge("payment.queue.size", queueSize != null ? queueSize : 0);

        // 사용 가능한 세마포어 permits
        RSemaphore semaphore = redissonClient.getSemaphore(SEMAPHORE_KEY);
        int availablePermits = semaphore.availablePermits();
        meterRegistry.gauge("payment.semaphore.available", availablePermits);

        // 사용 중인 permits
        meterRegistry.gauge("payment.semaphore.used", 10 - availablePermits);
    }
}
```

### 5.2 알림 조건

| 메트릭 | 알림 조건 | 대응 |
|--------|----------|------|
| `queue.size` | > 1000 | Consumer 스케일 아웃 고려 |
| `queue.size` | > 5000 (5분 이상) | 즉시 조치 필요 |
| `semaphore.available` | = 0 (5분 이상) | PG API 병목 확인 |
| 처리 지연 시간 | > 30초 | Consumer 성능 점검 |

---

## 6. Temporal과의 비교

### 6.1 직접 구현 vs Temporal

```
┌─────────────────────────────────────────────────────────────────────┐
│                    직접 구현 vs Temporal                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [직접 구현 - Phase 2-A]                                            │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  // Producer                                                  │   │
│  │  redisTemplate.opsForList().leftPush(QUEUE_KEY, json);       │   │
│  │                                                               │   │
│  │  // Consumer (스케줄러 필요)                                   │   │
│  │  @Scheduled(fixedDelay = 100)                                │   │
│  │  public void consume() {                                     │   │
│  │      if (!semaphore.tryAcquire()) return;                    │   │
│  │      String msg = redisTemplate.rightPop(QUEUE_KEY);         │   │
│  │      process(msg);                                           │   │
│  │      semaphore.release();                                    │   │
│  │  }                                                           │   │
│  │                                                               │   │
│  │  // 결과 저장, 재시도, 실패 처리 모두 직접 구현                  │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  [Temporal - Phase 3]                                               │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  // Worker 설정만으로 동시성 제어                              │   │
│  │  WorkerOptions options = WorkerOptions.newBuilder()          │   │
│  │      .setMaxConcurrentActivityExecutionSize(10)              │   │
│  │      .setMaxTaskQueueActivitiesPerSecond(5.0)                │   │
│  │      .build();                                               │   │
│  │                                                               │   │
│  │  // Activity 정의 (비즈니스 로직만)                            │   │
│  │  public String processPayment(PaymentRequest request) {      │   │
│  │      return paymentGateway.process(request);                 │   │
│  │  }                                                           │   │
│  │                                                               │   │
│  │  // 대기열, 재시도, 상태 관리 모두 Temporal이 처리!             │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 6.2 비교 요약

| 항목 | 직접 구현 | Temporal |
|------|----------|----------|
| 대기열 | Redis List/Stream 직접 관리 | Task Queue (자동) |
| 동시성 제어 | RSemaphore 직접 관리 | WorkerOptions 설정 |
| 재시도 | 직접 구현 필요 | RetryOptions 선언 |
| 상태 관리 | DB 저장 직접 구현 | Event History (자동) |
| 결과 조회 | 별도 저장소 필요 | WorkflowClient 쿼리 |
| 모니터링 | 직접 구현 필요 | Temporal Web UI |
| 장애 복구 | Pending List 처리 필요 | 자동 복구 |

### 6.3 학습 의의

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Phase 2-A → Phase 3 학습 흐름                     │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Phase 2-A: 직접 구현                                               │
│  ├── 대기열 + 세마포어 조합 직접 구현                                │
│  ├── Consumer 스케줄러 관리                                         │
│  ├── 실패 처리, 재시도, 상태 관리                                   │
│  └── "이거 관리하기 정말 힘들다..." 체감                            │
│                                                                      │
│  Phase 3: Temporal 도입                                             │
│  ├── WorkerOptions 몇 줄로 동시성 제어                              │
│  ├── Task Queue가 대기열 역할 자동 수행                             │
│  ├── Activity 재시도, 상태 관리 자동화                               │
│  └── "이래서 Temporal을 쓰는구나!" 이해                             │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 7. 우리 프로젝트 적용

### 7.1 적용 대상

| 서비스 | 적용 여부 | 이유 |
|--------|----------|------|
| **Payment Service** | ✅ 적용 | PG API Rate Limit + 트래픽 폭주 대응 |
| Inventory Service | ❌ | 분산 락으로 충분, 즉시 응답 필요 |
| Order Service | ❌ | 동기 처리 필요 |
| Notification Service | ⚠️ 고려 | Phase 2-B에서 Redis Stream 사용 예정 |

### 7.2 Payment Service 적용 시나리오

```
┌─────────────────────────────────────────────────────────────────────┐
│                    결제 서비스 대기열 적용                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [사용자 요청]                                                       │
│  POST /payments                                                     │
│       │                                                              │
│       ▼                                                              │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  1. 요청 검증 (Bean Validation)                              │    │
│  │  2. 멱등성 체크 (Idempotency Key)                            │    │
│  │  3. 대기열 적재 (Redis Stream)                               │    │
│  │  4. 202 Accepted 응답 반환                                   │    │
│  └────────────────────────────────────────────────────────────┘    │
│       │                                                              │
│       │  { "requestId": "abc-123",                                  │
│       │    "status": "PENDING",                                     │
│       │    "statusUrl": "/payments/abc-123/status" }                │
│       ▼                                                              │
│  [Consumer 처리] (비동기)                                            │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  1. 세마포어 획득                                            │    │
│  │  2. Fake PG API 호출                                        │    │
│  │  3. 결과 저장 (DB)                                          │    │
│  │  4. 세마포어 반환                                            │    │
│  └────────────────────────────────────────────────────────────┘    │
│       │                                                              │
│       ▼                                                              │
│  [사용자 결과 조회]                                                  │
│  GET /payments/abc-123/status                                       │
│       │                                                              │
│       │  { "requestId": "abc-123",                                  │
│       │    "status": "SUCCESS",                                     │
│       │    "transactionId": "TXN-xyz" }                            │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 8. 실습 과제

### 과제 1: Redis List 기반 구현

1. `PaymentQueueProducer` 구현
2. `PaymentQueueConsumer` 구현 (RSemaphore 포함)
3. 결과 저장 및 조회 API 구현

### 과제 2: 부하 테스트

1. 100개 동시 요청 발생
2. 세마포어 10개로 제한
3. 모든 요청이 순차 처리되는지 확인

### 과제 3: 메트릭 수집

1. 대기열 크기 메트릭 수집
2. 세마포어 사용량 메트릭 수집
3. 평균 대기 시간 계산

### 과제 4: Redis Stream 전환 (선택)

1. List → Stream으로 전환
2. Consumer Group 설정
3. 메시지 ACK 처리

---

## 참고 자료

- [Redis List Commands](https://redis.io/commands/?group=list)
- [Redis Stream Commands](https://redis.io/commands/?group=stream)
- [Redisson RSemaphore](https://github.com/redisson/redisson/wiki/8.-distributed-locks-and-synchronizers#86-semaphore)
- [Temporal Worker Performance](https://docs.temporal.io/develop/worker-performance)
- [Temporal Task Queue Rate Limiting](https://docs.temporal.io/task-queue)

---

## 다음 단계

[05-optimistic-lock.md](./05-optimistic-lock.md) - 낙관적 락으로 이동
