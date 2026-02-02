# Contract Testing (계약 테스트)

## 개요

### What (무엇인가)
Contract Testing은 서비스 간 API 계약을 검증하는 테스트 방식입니다. Consumer(호출자)와 Provider(제공자) 간의 통신 규약을 정의하고, 양측이 이를 준수하는지 독립적으로 검증합니다.

### Why (왜 필요한가)

```
┌─────────────────────────────────────────────────────────────────────┐
│                    MSA에서 테스트의 어려움                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [Integration Test 문제점]                                          │
│  ├── 모든 서비스를 동시에 실행해야 함                                │
│  ├── 환경 구성 복잡                                                  │
│  ├── 테스트 속도 느림                                                │
│  └── 하나의 서비스 장애 → 전체 테스트 실패                           │
│                                                                      │
│  [E2E Test 문제점]                                                  │
│  ├── 더 느리고 불안정                                                │
│  ├── 실패 원인 파악 어려움                                           │
│  └── 유지보수 비용 높음                                              │
│                                                                      │
│  [Contract Test 해결]                                               │
│  ├── 각 서비스 독립적으로 테스트                                     │
│  ├── 빠른 피드백                                                     │
│  ├── 계약만 지키면 독립 배포 가능                                    │
│  └── 서비스 간 통신 문제 조기 발견                                   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 테스트 다이아몬드 (2025 트렌드)

```
          /\
         /E2E\          ← 소수, 핵심만
        /──────\
       /Integration\    ← 가장 많이
      /──────────────\
     /  Contract Tests \  ← 서비스 간 계약
    /────────────────────\
   /      Unit Tests      \  ← 기반
  ──────────────────────────

Netflix, Spotify 등 MSA 선도 기업이 채택한 테스트 전략
```

---

## 1. Consumer-Driven Contract (CDC)

### 1.1 기본 개념

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Consumer-Driven Contract 흐름                     │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  1. Consumer가 계약 정의                                             │
│     Orchestrator: "Order Service는 POST /orders에                   │
│                    이 형식으로 응답해야 함"                          │
│                           │                                          │
│                           ▼                                          │
│  2. 계약 파일 생성 (Pact 파일)                                       │
│     {                                                               │
│       "consumer": "orchestrator-pure",                              │
│       "provider": "service-order",                                  │
│       "interactions": [...]                                         │
│     }                                                               │
│                           │                                          │
│                           ▼                                          │
│  3. Provider가 계약 검증                                             │
│     Order Service 빌드 시 자동으로 계약 충족 여부 검증               │
│                           │                                          │
│                           ▼                                          │
│  4. 독립 배포 가능                                                   │
│     계약만 지키면 서로 영향 없이 배포                                │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 1.2 이 프로젝트의 계약 관계

```
┌─────────────────────────────────────────────────────────────────────┐
│                    서비스 간 계약 관계                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [orchestrator-pure] (Consumer)                                      │
│         │                                                            │
│         ├──→ [service-order]     (Provider)                         │
│         │     └── POST /api/orders                                  │
│         │     └── PUT /api/orders/{id}/confirm                      │
│         │     └── POST /api/orders/{id}/cancel                      │
│         │                                                            │
│         ├──→ [service-inventory] (Provider)                         │
│         │     └── POST /api/inventory/reserve                       │
│         │     └── POST /api/inventory/confirm                       │
│         │     └── POST /api/inventory/cancel                        │
│         │                                                            │
│         └──→ [service-payment]   (Provider)                         │
│               └── POST /api/payments                                │
│               └── POST /api/payments/{id}/approve                   │
│               └── POST /api/payments/{id}/refund                    │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 2. Pact 기반 Contract Testing

### 2.1 의존성 추가

```gradle
// build.gradle (Consumer - orchestrator-pure)
dependencies {
    testImplementation 'au.com.dius.pact.consumer:junit5:4.6.5'
}

// build.gradle (Provider - service-order, service-inventory, service-payment)
dependencies {
    testImplementation 'au.com.dius.pact.provider:junit5:4.6.5'
    testImplementation 'au.com.dius.pact.provider:spring:4.6.5'
}
```

### 2.2 Consumer 테스트 (Orchestrator)

```java
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "service-order", port = "8081")
class OrderServiceContractTest {

    @Pact(consumer = "orchestrator-pure", provider = "service-order")
    public V4Pact createOrderPact(PactDslWithProvider builder) {
        return builder
            .given("주문 생성 가능 상태")
            .uponReceiving("주문 생성 요청")
                .path("/api/orders")
                .method("POST")
                .headers("Content-Type", "application/json")
                .body(new PactDslJsonBody()
                    .stringType("customerId", "CUST-001")
                    .numberType("totalAmount", 10000)
                )
            .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body(new PactDslJsonBody()
                    .booleanType("success", true)
                    .object("data")
                        .numberType("id", 1L)
                        .stringType("orderNumber", "ORD-20260202-001")
                        .stringType("status", "PENDING")
                    .closeObject()
                )
            .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "createOrderPact")
    void testCreateOrder(MockServer mockServer) {
        // Given
        OrderServiceClient client = new OrderServiceClient(
            RestClient.builder()
                .baseUrl(mockServer.getUrl())
                .build()
        );

        // When
        ApiResponse<OrderResponse> response = client.createOrder(
            new CreateOrderRequest("CUST-001", 10000)
        );

        // Then
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().getStatus()).isEqualTo("PENDING");
    }
}
```

### 2.3 Provider 테스트 (Order Service)

```java
@Provider("service-order")
@Consumer("orchestrator-pure")
@PactFolder("pacts")  // Consumer 테스트에서 생성된 pact 파일 위치
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderServiceProviderTest {

    @LocalServerPort
    private int port;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void setUp(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", port));
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verifyPact(PactVerificationContext context) {
        context.verifyInteraction();
    }

    @State("주문 생성 가능 상태")
    void setupOrderCreation() {
        // Provider 상태 설정 (필요한 테스트 데이터 준비)
        orderRepository.deleteAll();
    }

    @State("주문이 존재함")
    Map<String, Object> setupExistingOrder() {
        Order order = orderRepository.save(
            Order.create("CUST-001", BigDecimal.valueOf(10000))
        );
        return Map.of("orderId", order.getId());
    }
}
```

---

## 3. Spring Cloud Contract (대안)

### 3.1 개요

Spring 생태계에서 더 자연스러운 통합을 원할 경우 사용합니다.

### 3.2 Groovy DSL로 계약 정의

```groovy
// service-order/src/test/resources/contracts/createOrder.groovy
Contract.make {
    description "주문 생성"

    request {
        method POST()
        url "/api/orders"
        headers {
            contentType applicationJson()
        }
        body([
            customerId: "CUST-001",
            totalAmount: 10000
        ])
    }

    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body([
            success: true,
            data: [
                id: $(producer(regex('[0-9]+')), consumer(1)),
                orderNumber: $(producer(regex('ORD-[0-9]{8}-[0-9]{3}')), consumer("ORD-20260202-001")),
                status: "PENDING"
            ]
        ])
    }
}
```

### 3.3 자동 생성 테스트

```java
// 자동 생성됨: service-order/target/generated-test-sources
public class ContractVerifierTest extends OrderContractTestBase {

    @Test
    public void validate_createOrder() throws Exception {
        // Given
        MockMvcRequestSpecification request = given()
            .header("Content-Type", "application/json")
            .body("{\"customerId\":\"CUST-001\",\"totalAmount\":10000}");

        // When
        ResponseOptions response = given().spec(request)
            .post("/api/orders");

        // Then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header("Content-Type")).matches("application/json.*");
        DocumentContext parsedJson = JsonPath.parse(response.getBody().asString());
        assertThat(parsedJson.read("$.success", Boolean.class)).isEqualTo(true);
    }
}
```

---

## 4. Pact vs Spring Cloud Contract

| 항목 | Pact | Spring Cloud Contract |
|------|------|----------------------|
| **생태계** | 언어 무관 (Polyglot) | Spring 전용 |
| **계약 형식** | JSON | Groovy/YAML/Java |
| **Broker** | Pact Broker (중앙 저장소) | Git 또는 Artifactory |
| **학습 곡선** | 중간 | Spring 익숙하면 낮음 |
| **적합 상황** | 다양한 언어 혼용 | Spring 전용 환경 |

### 이 프로젝트 선택: Pact

```
이유:
├── 더 범용적인 도구 학습
├── 언어 무관한 계약 검증
└── Pact Broker를 통한 계약 관리 학습
```

---

## 5. CI/CD 통합

### 5.1 테스트 순서

```
┌─────────────────────────────────────────────────────────────────────┐
│                    CI/CD에서 Contract Test 위치                      │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  1. Unit Tests (빠름)                                                │
│     └── 각 서비스 독립 실행                                          │
│                    │                                                 │
│                    ▼                                                 │
│  2. Contract Tests (Consumer)                                        │
│     └── Pact 파일 생성                                               │
│     └── Pact Broker에 업로드                                         │
│                    │                                                 │
│                    ▼                                                 │
│  3. Contract Tests (Provider)                                        │
│     └── Pact Broker에서 계약 다운로드                                │
│     └── 계약 충족 여부 검증                                          │
│                    │                                                 │
│                    ▼                                                 │
│  4. Integration Tests                                                │
│     └── Testcontainers로 DB, Redis 연동                              │
│                    │                                                 │
│                    ▼                                                 │
│  5. 배포                                                             │
│     └── 계약 검증 통과 시에만 배포 허용                               │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 5.2 GitHub Actions 예시

```yaml
# .github/workflows/contract-test.yml
name: Contract Tests

on: [push, pull_request]

jobs:
  consumer-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Run Consumer Contract Tests
        run: ./gradlew :orchestrator-pure:contractTest

      - name: Publish Pacts to Broker
        run: ./gradlew pactPublish
        env:
          PACT_BROKER_URL: ${{ secrets.PACT_BROKER_URL }}
          PACT_BROKER_TOKEN: ${{ secrets.PACT_BROKER_TOKEN }}

  provider-tests:
    needs: consumer-tests
    strategy:
      matrix:
        service: [service-order, service-inventory, service-payment]
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Verify Provider Contract
        run: ./gradlew :${{ matrix.service }}:pactVerify
        env:
          PACT_BROKER_URL: ${{ secrets.PACT_BROKER_URL }}
```

---

## 6. 핵심 학습 포인트

### 6.1 Contract Testing의 가치

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Contract Testing 핵심 가치                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  1. 독립 배포 가능                                                   │
│     └── 계약만 지키면 다른 서비스 영향 없이 배포                     │
│                                                                      │
│  2. 빠른 피드백                                                      │
│     └── 전체 시스템 없이도 통신 문제 조기 발견                       │
│                                                                      │
│  3. 팀 자율성                                                        │
│     └── 각 팀이 독립적으로 개발/테스트/배포                          │
│                                                                      │
│  4. 문서화                                                           │
│     └── 계약 자체가 API 문서 역할                                    │
│                                                                      │
│  5. 신뢰도                                                           │
│     └── "이 API는 계약을 통해 검증됨" 확신                           │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 6.2 주의사항

| 주의점 | 설명 |
|--------|------|
| **과도한 계약** | 모든 필드가 아닌 중요한 필드만 계약 |
| **계약 관리** | Pact Broker 등 중앙 저장소 필요 |
| **버전 관리** | 계약 버전과 서비스 버전 동기화 |
| **팀 협업** | Consumer와 Provider 팀 간 소통 필요 |

---

## 7. 실습 가이드

### Step 1: Pact 의존성 추가
각 모듈의 `build.gradle`에 Pact 의존성 추가

### Step 2: Consumer 테스트 작성
`orchestrator-pure`에 각 Provider에 대한 계약 테스트 작성

### Step 3: Provider 테스트 작성
각 서비스에 계약 검증 테스트 작성

### Step 4: CI 통합
GitHub Actions에 계약 테스트 추가

---

## 관련 문서

- [D019 테스트 전략 확장](../../architecture/DECISIONS.md#d019-테스트-전략-확장)
- [Pact 공식 문서](https://docs.pact.io/)
- [Spring Cloud Contract](https://spring.io/projects/spring-cloud-contract)
