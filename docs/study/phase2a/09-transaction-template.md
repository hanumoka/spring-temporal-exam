# TransactionTemplate - 프로그래밍 방식 트랜잭션

## 이 문서에서 배우는 것

- @Transactional vs TransactionTemplate 비교
- 스크립트 패턴으로 트랜잭션 관리
- 트랜잭션 모니터링 구현
- 실무 적용 패턴

---

## 1. 왜 TransactionTemplate인가?

### @Transactional의 한계

```java
// @Transactional 방식
@Transactional
public void processOrder(OrderRequest request) {
    // 트랜잭션 어디서 시작?
    orderRepository.save(order);
    inventoryService.reserve(order);
    // 트랜잭션 어디서 끝?
}
```

**문제점**:
- 트랜잭션 경계가 암묵적 (메서드 시작/끝)
- 모니터링 포인트 삽입이 어려움
- 부분 롤백이나 세밀한 제어 어려움
- 프록시 기반이라 self-invocation 문제 발생

### TransactionTemplate의 장점

```java
// TransactionTemplate 방식
public void processOrder(OrderRequest request) {
    // 트랜잭션 시작 전 작업 가능
    log.info("트랜잭션 시작");
    Instant start = Instant.now();

    Order result = transactionTemplate.execute(status -> {
        // 명확한 트랜잭션 경계
        Order order = orderRepository.save(new Order(request));
        inventoryService.reserve(order);
        return order;
    });

    // 트랜잭션 종료 후 작업 가능
    log.info("트랜잭션 완료: {}ms", Duration.between(start, Instant.now()).toMillis());
}
```

**장점**:
- 트랜잭션 경계가 명시적
- 모니터링/로깅 삽입 용이
- 세밀한 제어 가능
- self-invocation 문제 없음

---

## 2. 기본 사용법

### 설정

```java
@Configuration
public class TransactionConfig {

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
        TransactionTemplate template = new TransactionTemplate(tm);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(30);  // 30초
        return template;
    }

    // 읽기 전용 트랜잭션
    @Bean
    public TransactionTemplate readOnlyTransactionTemplate(PlatformTransactionManager tm) {
        TransactionTemplate template = new TransactionTemplate(tm);
        template.setReadOnly(true);
        return template;
    }
}
```

### 기본 패턴

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final TransactionTemplate transactionTemplate;
    private final OrderRepository orderRepository;

    // 반환값이 있는 경우
    public Order createOrder(OrderRequest request) {
        return transactionTemplate.execute(status -> {
            Order order = Order.create(request);
            return orderRepository.save(order);
        });
    }

    // 반환값이 없는 경우
    public void updateOrder(Long orderId, OrderUpdateRequest request) {
        transactionTemplate.executeWithoutResult(status -> {
            Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
            order.update(request);
        });
    }

    // 수동 롤백
    public void processWithManualRollback(OrderRequest request) {
        transactionTemplate.executeWithoutResult(status -> {
            try {
                // 비즈니스 로직
                orderRepository.save(new Order(request));
            } catch (BusinessException e) {
                status.setRollbackOnly();  // 명시적 롤백
                throw e;
            }
        });
    }
}
```

---

## 3. 스크립트 패턴

### 트랜잭션 스크립트 구조

```
┌─────────────────────────────────────────────────────────────────────┐
│                    트랜잭션 스크립트 패턴                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   [1. 사전 준비]                                                     │
│   ├── 트랜잭션 ID 생성                                               │
│   ├── MDC 설정                                                      │
│   ├── 시작 시간 기록                                                 │
│   └── 로깅: 트랜잭션 시작                                            │
│                                                                      │
│   [2. 트랜잭션 실행]                                                 │
│   └── transactionTemplate.execute(status -> {                       │
│           // 비즈니스 로직                                           │
│       });                                                           │
│                                                                      │
│   [3. 사후 처리]                                                     │
│   ├── 성공 시: 성공 메트릭, 로깅                                     │
│   ├── 실패 시: 실패 메트릭, 에러 로깅                                │
│   └── 항상: 소요 시간 메트릭, MDC 정리                               │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 구현 예시

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderTransactionService {

    private final TransactionTemplate transactionTemplate;
    private final OrderRepository orderRepository;
    private final InventoryService inventoryService;
    private final MeterRegistry meterRegistry;

    private static final String TX_TYPE = "order";

    public Order createOrder(OrderRequest request) {
        String txId = generateTxId();
        Instant start = Instant.now();

        // 1. 사전 준비
        MDC.put("txId", txId);
        MDC.put("txType", TX_TYPE);
        log.info("트랜잭션 시작: customerId={}, productId={}",
                request.customerId(), request.productId());

        try {
            // 2. 트랜잭션 실행
            Order order = transactionTemplate.execute(status -> {
                Order newOrder = Order.create(request);
                Order savedOrder = orderRepository.save(newOrder);

                inventoryService.reserve(
                    savedOrder.getId(),
                    request.productId(),
                    request.quantity()
                );

                return savedOrder;
            });

            // 3. 성공 처리
            recordSuccess(start);
            log.info("트랜잭션 성공: orderId={}", order.getId());
            return order;

        } catch (Exception e) {
            // 3. 실패 처리
            recordFailure(start, e);
            log.error("트랜잭션 실패: {}", e.getMessage(), e);
            throw e;

        } finally {
            // 3. 정리
            recordDuration(start);
            MDC.remove("txId");
            MDC.remove("txType");
        }
    }

    private String generateTxId() {
        return "tx-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void recordSuccess(Instant start) {
        meterRegistry.counter("transaction.success", "type", TX_TYPE).increment();
    }

    private void recordFailure(Instant start, Exception e) {
        meterRegistry.counter("transaction.failure",
                "type", TX_TYPE,
                "exception", e.getClass().getSimpleName()
        ).increment();
    }

    private void recordDuration(Instant start) {
        Duration duration = Duration.between(start, Instant.now());
        meterRegistry.timer("transaction.duration", "type", TX_TYPE)
                .record(duration);
    }
}
```

---

## 4. 트랜잭션 모니터링

### 4.1 수집할 메트릭

| 메트릭 | 타입 | 설명 |
|--------|------|------|
| `transaction.success` | Counter | 성공한 트랜잭션 수 |
| `transaction.failure` | Counter | 실패한 트랜잭션 수 |
| `transaction.duration` | Timer | 트랜잭션 소요 시간 |
| `transaction.active` | Gauge | 현재 진행 중인 트랜잭션 수 |
| `transaction.rollback` | Counter | 롤백된 트랜잭션 수 |

### 4.2 로그 포맷

```
// 시작 로그
2024-01-15 10:30:00.123 [req-abc123] [tx-def456] INFO  트랜잭션 시작: type=order, customerId=100

// 성공 로그
2024-01-15 10:30:00.456 [req-abc123] [tx-def456] INFO  트랜잭션 성공: type=order, orderId=789, duration=333ms

// 실패 로그
2024-01-15 10:30:00.456 [req-abc123] [tx-def456] ERROR 트랜잭션 실패: type=order, error=InsufficientStockException, duration=200ms
```

### 4.3 TransactionMonitor 유틸리티

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class TransactionMonitor {

    private final MeterRegistry meterRegistry;
    private final AtomicInteger activeTransactions = new AtomicInteger(0);

    @PostConstruct
    public void init() {
        // 현재 활성 트랜잭션 수 게이지 등록
        Gauge.builder("transaction.active", activeTransactions, AtomicInteger::get)
                .description("Currently active transactions")
                .register(meterRegistry);
    }

    /**
     * 모니터링이 포함된 트랜잭션 실행
     */
    public <T> T executeWithMonitoring(
            TransactionTemplate template,
            String txType,
            TransactionCallback<T> action) {

        String txId = "tx-" + UUID.randomUUID().toString().substring(0, 8);
        Instant start = Instant.now();

        MDC.put("txId", txId);
        MDC.put("txType", txType);
        activeTransactions.incrementAndGet();

        log.info("트랜잭션 시작: type={}", txType);

        try {
            T result = template.execute(action);

            meterRegistry.counter("transaction.success", "type", txType).increment();
            log.info("트랜잭션 성공: type={}", txType);

            return result;

        } catch (Exception e) {
            meterRegistry.counter("transaction.failure",
                    "type", txType,
                    "exception", e.getClass().getSimpleName()
            ).increment();

            if (isRollback(e)) {
                meterRegistry.counter("transaction.rollback", "type", txType).increment();
            }

            log.error("트랜잭션 실패: type={}, error={}", txType, e.getMessage());
            throw e;

        } finally {
            Duration duration = Duration.between(start, Instant.now());
            meterRegistry.timer("transaction.duration", "type", txType).record(duration);

            activeTransactions.decrementAndGet();
            MDC.remove("txId");
            MDC.remove("txType");

            log.debug("트랜잭션 종료: type={}, duration={}ms", txType, duration.toMillis());
        }
    }

    /**
     * 반환값 없는 트랜잭션 실행
     */
    public void executeWithMonitoring(
            TransactionTemplate template,
            String txType,
            TransactionCallbackWithoutResult action) {

        executeWithMonitoring(template, txType, status -> {
            action.doInTransaction(status);
            return null;
        });
    }

    private boolean isRollback(Exception e) {
        return e instanceof RuntimeException || e instanceof Error;
    }
}
```

### 4.4 사용 예시

```java
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final TransactionTemplate transactionTemplate;
    private final TransactionMonitor txMonitor;
    private final PaymentRepository paymentRepository;

    public Payment processPayment(PaymentRequest request) {
        return txMonitor.executeWithMonitoring(
            transactionTemplate,
            "payment",
            status -> {
                Payment payment = Payment.create(request);
                return paymentRepository.save(payment);
            }
        );
    }
}
```

---

## 5. 중첩 트랜잭션 처리

### REQUIRES_NEW 시뮬레이션

```java
@Service
@RequiredArgsConstructor
public class AuditService {

    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate requiresNewTemplate;
    private final AuditLogRepository auditLogRepository;

    @PostConstruct
    public void init() {
        // REQUIRES_NEW 동작을 위한 별도 설정
        requiresNewTemplate.setPropagationBehavior(
            TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
    }

    /**
     * 메인 트랜잭션이 롤백되어도 감사 로그는 남김
     */
    public void logAudit(String action, String details) {
        requiresNewTemplate.executeWithoutResult(status -> {
            AuditLog log = new AuditLog(action, details, LocalDateTime.now());
            auditLogRepository.save(log);
        });
    }
}
```

### 트랜잭션 분리 예시

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderProcessingService {

    private final TransactionTemplate transactionTemplate;
    private final AuditService auditService;
    private final OrderRepository orderRepository;

    public Order processOrder(OrderRequest request) {
        try {
            Order order = transactionTemplate.execute(status -> {
                // 메인 비즈니스 로직
                Order newOrder = orderRepository.save(Order.create(request));

                if (someConditionFails()) {
                    throw new BusinessException("처리 실패");
                }

                return newOrder;
            });

            // 성공 감사 로그 (별도 트랜잭션)
            auditService.logAudit("ORDER_CREATED", "orderId=" + order.getId());
            return order;

        } catch (Exception e) {
            // 실패 감사 로그 (메인 트랜잭션 롤백되어도 기록됨)
            auditService.logAudit("ORDER_FAILED", "error=" + e.getMessage());
            throw e;
        }
    }
}
```

---

## 6. 테스트

### 단위 테스트

```java
@ExtendWith(MockitoExtension.class)
class OrderTransactionServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private Counter counter;

    @Mock
    private Timer timer;

    private TransactionTemplate transactionTemplate;
    private OrderTransactionService service;

    @BeforeEach
    void setUp() {
        // 테스트용 트랜잭션 템플릿 (실제 트랜잭션 없이 실행)
        transactionTemplate = new TransactionTemplate();
        transactionTemplate.setTransactionManager(
            new PseudoTransactionManager()  // 가짜 트랜잭션 매니저
        );

        when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(counter);
        when(meterRegistry.timer(anyString(), any(String[].class))).thenReturn(timer);

        service = new OrderTransactionService(
            transactionTemplate, orderRepository, inventoryService, meterRegistry
        );
    }

    @Test
    void 주문_생성_성공시_성공_메트릭_기록() {
        // given
        OrderRequest request = new OrderRequest(1L, 100L, 5);
        when(orderRepository.save(any())).thenReturn(Order.create(request));

        // when
        service.createOrder(request);

        // then
        verify(meterRegistry).counter("transaction.success", "type", "order");
        verify(counter).increment();
    }

    @Test
    void 주문_생성_실패시_실패_메트릭_기록() {
        // given
        OrderRequest request = new OrderRequest(1L, 100L, 5);
        when(orderRepository.save(any()))
            .thenThrow(new RuntimeException("DB Error"));

        // when & then
        assertThrows(RuntimeException.class, () -> service.createOrder(request));
        verify(meterRegistry).counter(eq("transaction.failure"), anyString(), anyString(), anyString(), anyString());
    }
}
```

### 통합 테스트

```java
@SpringBootTest
@Transactional
class OrderTransactionServiceIntegrationTest {

    @Autowired
    private OrderTransactionService service;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void 트랜잭션_메트릭이_정상_기록된다() {
        // given
        OrderRequest request = new OrderRequest(1L, 100L, 5);
        double beforeCount = getCount("transaction.success", "order");

        // when
        service.createOrder(request);

        // then
        double afterCount = getCount("transaction.success", "order");
        assertThat(afterCount).isEqualTo(beforeCount + 1);
    }

    private double getCount(String name, String type) {
        Counter counter = meterRegistry.find(name).tag("type", type).counter();
        return counter != null ? counter.count() : 0;
    }
}
```

---

## 7. Grafana 대시보드

### 주요 패널

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Transaction Dashboard                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [Success Rate]          [Active Transactions]    [Avg Duration]    │
│  ┌────────────┐          ┌────────────┐          ┌────────────┐    │
│  │    98.5%   │          │      3     │          │   45ms     │    │
│  │   ▲ 0.2%   │          │            │          │   ▼ 5ms    │    │
│  └────────────┘          └────────────┘          └────────────┘    │
│                                                                      │
│  [Transaction Rate Over Time]                                       │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  success ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━              │   │
│  │  failure ─ ─ ─ ─ ─ ─ ─                                      │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  [Duration Percentiles (p50, p95, p99)]                            │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  p50: 35ms  |  p95: 120ms  |  p99: 250ms                   │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Prometheus 쿼리

```promql
# 성공률
sum(rate(transaction_success_total[5m])) /
(sum(rate(transaction_success_total[5m])) + sum(rate(transaction_failure_total[5m]))) * 100

# 초당 트랜잭션 수 (TPS)
sum(rate(transaction_success_total[1m]))

# 평균 소요 시간
rate(transaction_duration_seconds_sum[5m]) / rate(transaction_duration_seconds_count[5m])

# p95 소요 시간
histogram_quantile(0.95, sum(rate(transaction_duration_seconds_bucket[5m])) by (le, type))
```

---

## 8. 실습 과제

1. TransactionTemplate 설정 및 Bean 등록
2. TransactionMonitor 유틸리티 구현
3. 기존 @Transactional 코드를 TransactionTemplate으로 전환
4. 메트릭 수집 및 Grafana 대시보드 구성
5. 트랜잭션 로그로 문제 추적 테스트

---

## 참고 자료

- [Spring Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction.html)
- [TransactionTemplate API](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/TransactionTemplate.html)
- [Micrometer Metrics](https://micrometer.io/docs)

---

## 다음 단계

Phase 2-A 학습 완료! [Phase 2-B: Redis 기초](../phase2b/01-redis-basics.md)로 이동
