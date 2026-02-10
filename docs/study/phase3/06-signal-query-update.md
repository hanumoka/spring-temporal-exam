# Signal, Query, Update: 외부와의 소통

> **핵심 질문**: 실행 중인 Workflow에 외부에서 어떻게 개입하는가?

---

## 1. 왜 필요한가?

Workflow는 한번 시작되면 완료될 때까지 독립적으로 실행된다.
하지만 현실에서는 **실행 중인 Workflow와 소통**해야 하는 상황이 반드시 발생한다.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                  Workflow와 외부 세계: 소통이 필요한 상황들                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  [상황 1] 고객이 "주문 취소"를 요청함 → Signal 사용                          │
│  [상황 2] 관리자가 "이 주문 어디까지 진행됐어?" → Query 사용                  │
│  [상황 3] 결제 대기 중, 고객이 카드 정보 변경 + 결과 확인 → Update 사용       │
│  [상황 4] 고객센터에서 "처리 완료된 단계 알려줘" → Query 사용                 │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

Temporal은 이 소통을 위해 **세 가지 메시지 타입**을 제공한다:
- **Signal**: 편지 보내기 (보내고 끝, 응답 안 기다림)
- **Query**: 전화 걸기 (즉시 응답 기다림)
- **Update**: 택배 보내기 (전달 + 처리 결과 확인)

---

## 2. Signal vs Query vs Update 비교표

| 구분 | Signal | Query | Update |
|------|--------|-------|--------|
| **목적** | 상태 변경 요청 | 상태 조회 | 상태 변경 + 결과 확인 |
| **방향** | 외부 --> Workflow | 외부 --> Workflow --> 외부 | 외부 <--> Workflow |
| **동기/비동기** | 비동기 (Fire & Forget) | 동기 (응답 대기) | 동기 (응답 대기) |
| **응답** | 없음 (void) | 있음 | 있음 |
| **상태 변경** | O (가능) | X (금지) | O (가능) |
| **History 저장** | O (기록됨) | X (기록 안 됨) | O (기록됨) |
| **검증(Validator)** | 없음 | 없음 | O (선택적) |
| **Activity 호출** | 불가 (핸들러 내) | 불가 | 가능 (핸들러 내) |
| **비유** | 편지 보내기 | 전화로 물어보기 | 택배 (전달+확인) |

```
┌─────────────────────────────────────────────────────────────────────────┐
│              Signal vs Query vs Update 메시지 흐름                       │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Signal:  Client ── "취소해!" ──▶ Workflow  (응답 없음)                │
│  Query:   Client ── "상태?" ────▶ Workflow ── "PROCESSING" ──▶ Client │
│  Update:  Client ── "주소변경" ─▶ Workflow ── "변경완료" ────▶ Client │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Signal 상세

### 3.1 개념

Signal은 **실행 중인 Workflow에 비동기 메시지를 보내는 방법**이다.

- **Fire & Forget**: 보내고 끝. 응답을 기다리지 않는다
- **상태 변경 가능**: Workflow 내부 변수를 변경할 수 있다
- **History에 기록**: Event History에 저장된다
- **반환값 없음**: `void`만 가능

### 3.2 Java 구현

```java
// 1. Workflow 인터페이스에 Signal 정의
@WorkflowInterface
public interface OrderWorkflow {
    @WorkflowMethod
    OrderResult processOrder(OrderRequest request);

    @SignalMethod
    void cancelOrder(String reason);        // 여러 Signal 정의 가능

    @SignalMethod
    void updatePaymentMethod(String newCardToken);
}

// 2. Workflow 구현에서 Signal 처리
public class OrderWorkflowImpl implements OrderWorkflow {

    private boolean cancelRequested = false;
    private String cancelReason = null;
    private String cardToken = null;

    @Override
    public OrderResult processOrder(OrderRequest request) {
        Long orderId = activities.createOrder(request);

        // Signal 체크 포인트 1: 주문 생성 직후
        if (cancelRequested) {
            activities.cancelOrder(orderId, cancelReason);
            return new OrderResult(orderId, "CANCELLED", cancelReason);
        }

        activities.reserveStock(request.productId());

        // Signal 체크 포인트 2: 재고 예약 직후 (보상 트랜잭션 필요)
        if (cancelRequested) {
            activities.cancelReservation(request.productId());
            activities.cancelOrder(orderId, cancelReason);
            return new OrderResult(orderId, "CANCELLED", cancelReason);
        }

        // cardToken이 Signal로 변경됐을 수 있음
        String tokenToUse = cardToken != null ? cardToken : request.getCardToken();
        activities.processPayment(orderId, tokenToUse);
        return new OrderResult(orderId, "COMPLETED", null);
    }

    // Signal 핸들러: 상태만 변경, 로직은 메인 Workflow에서 처리
    @Override
    public void cancelOrder(String reason) {
        this.cancelRequested = true;
        this.cancelReason = reason;
    }

    @Override
    public void updatePaymentMethod(String newCardToken) {
        this.cardToken = newCardToken;
    }
}

// 3. 외부에서 Signal 보내기
OrderWorkflow workflow = workflowClient.newWorkflowStub(
    OrderWorkflow.class, workflowId
);
workflow.cancelOrder("고객 요청");  // Fire & Forget, 즉시 반환
```

### 3.3 Signal과 Workflow.await 조합

Signal은 체크 포인트 방식 외에, `Workflow.await()`와 조합하면 더 우아하게 사용할 수 있다.

```java
public class ApprovalWorkflowImpl implements ApprovalWorkflow {
    private boolean approved = false;

    @Override
    public ApprovalResult waitForApproval(ApprovalRequest request) {
        activities.sendApprovalRequest(request);

        // Signal이 올 때까지 대기 (최대 1시간)
        boolean received = Workflow.await(
            Duration.ofHours(1),
            () -> this.approved  // 조건: approved가 true가 될 때까지
        );

        if (!received) {
            return ApprovalResult.timeout("1시간 내 승인 없음");
        }
        return ApprovalResult.approved();
    }

    @Override
    public void approve() {
        this.approved = true;  // Signal로 상태 변경 -> await 조건 충족
    }
}
```

### 3.4 Signal 사용 시점

- **"주문 취소해줘"** -- 응답 불필요, 비동기 처리
- **"결제 완료됐어"** -- 외부 시스템에서 알림 전달
- **"새로운 아이템 추가"** -- 배치 처리 중 항목 추가
- **"카드 정보 변경"** -- 실행 중인 Workflow에 정보 전달

---

## 4. Query 상세

### 4.1 개념

Query는 **실행 중인 Workflow의 상태를 읽기 전용으로 조회하는 방법**이다.

- **동기 응답**: 호출하면 즉시 결과를 반환한다
- **상태 변경 불가**: 읽기만 가능하다 (순수 함수처럼 동작)
- **History에 기록 안 됨**: Event History에 저장되지 않아 빈번한 조회에 적합
- **반환값 있음**: 다양한 타입 반환 가능

### 4.2 Java 구현

```java
// 1. Workflow 인터페이스에 Query 정의
@WorkflowInterface
public interface OrderWorkflow {
    @WorkflowMethod
    OrderResult processOrder(OrderRequest request);

    @QueryMethod
    String getStatus();                    // 여러 Query 정의 가능

    @QueryMethod
    List<String> getCompletedSteps();
}

// 2. Workflow 구현에서 Query 응답
public class OrderWorkflowImpl implements OrderWorkflow {

    private String currentStatus = "INITIALIZED";
    private List<String> completedSteps = new ArrayList<>();

    @Override
    public OrderResult processOrder(OrderRequest request) {
        currentStatus = "CREATING_ORDER";
        Long orderId = activities.createOrder(request);
        completedSteps.add("ORDER_CREATED");

        currentStatus = "RESERVING_STOCK";
        activities.reserveStock(request.productId());
        completedSteps.add("STOCK_RESERVED");

        currentStatus = "PROCESSING_PAYMENT";
        activities.processPayment(orderId);
        completedSteps.add("PAYMENT_COMPLETED");

        currentStatus = "COMPLETED";
        return OrderResult.success(orderId);
    }

    // Query 핸들러: 읽기만!
    @Override
    public String getStatus() {
        return this.currentStatus;
    }

    @Override
    public List<String> getCompletedSteps() {
        return new ArrayList<>(this.completedSteps);  // 방어적 복사
    }
}

// 3. 외부에서 Query 호출
OrderWorkflow workflow = workflowClient.newWorkflowStub(
    OrderWorkflow.class, workflowId
);
String status = workflow.getStatus();  // 동기 응답: "RESERVING_STOCK"
```

### 4.3 Query 주의사항

Query 핸들러 안에서 **절대로 하면 안 되는 것들**이 있다:

```java
// (X) 잘못된 예 1: 상태 변경
@QueryMethod
public String getStatus() {
    this.accessCount++;  // (X) 상태 변경하면 안 됨!
    return this.currentStatus;
}

// (X) 잘못된 예 2: Activity 호출
@QueryMethod
public OrderDetail getDetail() {
    return activities.fetchOrderDetail(orderId);  // (X) Activity 호출 금지!
}

// (X) 잘못된 예 3: 외부 API 호출
@QueryMethod
public ExternalData getExternalData() {
    return restTemplate.getForObject("http://...", ExternalData.class);  // (X)
}

// (O) 올바른 예: 메모리 상태만 반환
@QueryMethod
public String getStatus() {
    return this.currentStatus;  // 단순 읽기만!
}
```

**왜 이런 제약이 있는가?**

Query는 Workflow의 **현재 메모리 상태를 스냅샷**으로 읽는 것이다.
History에 기록되지 않으므로, 부수 효과(Side Effect)가 있으면 Replay 시 일관성이 깨진다.
Activity나 외부 API 호출은 비결정적 동작이므로 Query 내에서 호출할 수 없다.

---

## 5. Update 상세 (Temporal 1.21+)

### 5.1 개념

Update는 Signal과 Query의 **장점을 결합**한 메시지 타입이다.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     Update = Signal + Query                             │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Signal의 장점: 상태 변경 가능       ──┐                                │
│                                        ├──▶ Update                     │
│  Query의 장점: 결과 반환 가능        ──┘                                │
│                                                                         │
│  추가 기능:                                                             │
│  - Validator로 사전 검증 가능                                           │
│  - Handler 내에서 Activity 호출 가능                                    │
│  - History에 기록됨 (내구성 보장)                                       │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 5.2 Java 구현

```java
// 1. Workflow 인터페이스에 Update 정의
@WorkflowInterface
public interface OrderWorkflow {
    @WorkflowMethod
    OrderResult processOrder(OrderRequest request);

    @UpdateMethod
    UpdateAddressResult updateShippingAddress(String newAddress);

    // Validator (선택적) - updateName으로 Handler와 연결
    @UpdateValidatorMethod(updateName = "updateShippingAddress")
    void validateAddressUpdate(String newAddress);
}

// 2. Workflow 구현
public class OrderWorkflowImpl implements OrderWorkflow {
    private String shippingAddress;
    private String currentStep;

    // Validator: 상태 변경 없이 검증만 수행
    @Override
    public void validateAddressUpdate(String newAddress) {
        if (currentStep.equals("SHIPPED")) {
            throw new IllegalStateException("이미 배송됨, 변경 불가");
        }
        if (newAddress == null || newAddress.isBlank()) {
            throw new IllegalArgumentException("주소가 비어있음");
        }
    }

    // Handler: 검증 통과 후 실행 (상태 변경 + Activity 호출 가능)
    @Override
    public UpdateAddressResult updateShippingAddress(String newAddress) {
        String oldAddress = this.shippingAddress;
        this.shippingAddress = newAddress;
        activities.notifyAddressChange(oldAddress, newAddress);  // Activity 가능!
        return new UpdateAddressResult(true, oldAddress, newAddress);
    }
}

// 3. 외부에서 Update 호출
OrderWorkflow workflow = workflowClient.newWorkflowStub(
    OrderWorkflow.class, workflowId
);
// 동기 호출: 검증 -> 실행 -> 결과 반환
UpdateAddressResult result = workflow.updateShippingAddress("서울시 강남구...");
```

### 5.3 Update 처리 흐름

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      Update 처리 단계                                    │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Client                        Temporal Server              Worker     │
│    │                                │                          │        │
│    │── UpdateWorkflowExecution ───▶│── Workflow Task ───────▶│        │
│    │                                │                          │        │
│    │                                │         [1] Validator 실행        │
│    │                                │         검증 실패 → 즉시 에러    │
│    │                                │                          │        │
│    │                                │         [2] Handler 실행         │
│    │                                │         상태 변경 + 로직         │
│    │                                │                          │        │
│    │◀── Update 결과 ──────────────│◀── 결과 반환 ───────────│        │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 5.4 Update 사용 시점

- **"배송지 변경"** -- 검증 + 변경 + 결과 확인이 모두 필요
- **"할인 코드 적용"** -- 유효성 검증 후 적용 여부 반환
- **"결제 금액 수정"** -- 변경 전 조건 확인 + 변경 결과 확인

**Signal 대신 Update를 써야 하는 경우:**
- 클라이언트가 **변경 결과를 즉시 알아야** 할 때
- 변경 전 **유효성 검증**이 필요할 때
- 검증 실패 시 클라이언트가 **즉시 에러를 받아야** 할 때

---

## 6. 실전 패턴: Signal + Query 조합

Update 이전에는 **Signal로 변경 요청 + Query로 결과 확인**하는 패턴이 주류였다.
Update를 사용할 수 없는 환경이거나, 비동기 처리가 더 적합한 경우 여전히 유용하다.

### 6.1 기본 패턴: 취소 요청 후 결과 폴링

```java
@PostMapping("/orders/{workflowId}/cancel")
public ResponseEntity<?> cancelAndWait(@PathVariable String workflowId) {
    OrderWorkflow workflow = workflowClient.newWorkflowStub(
        OrderWorkflow.class, workflowId
    );

    // 1. Signal 전송 (비동기)
    workflow.cancelOrder("고객 변심");

    // 2. Query로 상태 확인 (폴링)
    for (int i = 0; i < 10; i++) {
        String status = workflow.getStatus();
        if ("CANCELLED".equals(status)) {
            return ResponseEntity.ok("취소 완료!");
        }
        if ("COMPLETED".equals(status)) {
            return ResponseEntity.badRequest().body("이미 완료된 주문입니다");
        }
        Thread.sleep(500);
    }
    return ResponseEntity.accepted().body("취소 처리 중...");
}
```

### 6.2 패턴 흐름도

```
┌─────────────────────────────────────────────────────────────────────────┐
│                   Signal + Query 조합 패턴 흐름                          │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Client                              Workflow                          │
│    │                                    │                               │
│    │── Signal("취소해!") ──────────────▶│  cancelRequested = true       │
│    │                                    │                               │
│    │── Query("상태?") ─────────────────▶│                               │
│    │◀── "PROCESSING" ──────────────────│  (아직 처리 중)                │
│    │                                    │                               │
│    │   ... 0.5초 대기 ...               │  ... 보상 트랜잭션 실행 ...   │
│    │                                    │                               │
│    │── Query("상태?") ─────────────────▶│                               │
│    │◀── "CANCELLED" ───────────────────│  (취소 완료!)                  │
│    │                                    │                               │
└─────────────────────────────────────────────────────────────────────────┘
```

### 6.3 Signal + Query vs Update 비교

| 항목 | Signal + Query 조합 | Update |
|------|---------------------|--------|
| **코드 복잡도** | 높음 (폴링 로직 필요) | 낮음 (한 번 호출) |
| **응답 시점** | 폴링 주기에 의존 | 즉시 |
| **네트워크 호출** | 여러 번 (Signal 1회 + Query N회) | 1회 |
| **검증** | 별도 구현 필요 | Validator 내장 |
| **비동기 처리** | 자연스러움 | 동기 대기 |
| **호환성** | 모든 Temporal 버전 | 1.21+ 필요 |

---

## 7. 언제 무엇을 사용할까? 의사결정 가이드

### 7.1 의사결정 플로우차트

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      메시지 타입 선택 가이드                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Q1. 상태를 변경해야 하는가?                                            │
│    │                                                                    │
│    ├── 아니오 → Query                                                   │
│    │           "현재 몇 단계야?", "처리된 아이템 개수?"                 │
│    │           (History 미기록 → 빈번한 조회에 적합)                    │
│    │                                                                    │
│    └── 예 → Q2. 변경 결과를 즉시 알아야 하는가?                        │
│              │                                                          │
│              ├── 아니오 → Signal                                        │
│              │            "주문 취소해줘", "결제 완료됐어"              │
│              │                                                          │
│              └── 예 → Q3. 변경 전 검증이 필요한가?                     │
│                        │                                                │
│                        ├── 예 → Update (권장)                          │
│                        │        "배송지 변경", "할인 코드 적용"        │
│                        │                                                │
│                        └── 아니오 → Update 또는 Signal+Query           │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 7.2 자주 하는 실수

| 실수 | 문제 | 해결 |
|------|------|------|
| Query에서 상태 변경 | Replay 시 일관성 깨짐 | Query는 읽기만, 변경은 Signal/Update |
| Query에서 Activity 호출 | 비결정적 동작 발생 | Activity는 Workflow 메인 메서드에서만 |
| Signal 결과를 기대 | Signal은 void만 반환 | 결과 필요하면 Update 또는 Signal+Query |
| Update Validator에서 상태 변경 | Validator는 검증 전용 | 상태 변경은 Handler에서만 |
| 빈번한 Signal 전송 | Event History 비대화 | 빈번한 데이터는 Query 조회 활용 |

---

## 핵심 요약

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         핵심 요약                                        │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  1. Signal = 편지 (비동기, 상태 변경, History 기록)                     │
│  2. Query  = 전화 (동기, 읽기 전용, History 미기록)                     │
│  3. Update = 택배 (동기, 상태 변경 + 결과 반환, Validator 내장)         │
│                                                                         │
│  선택 기준:                                                             │
│  - 읽기만? → Query                                                      │
│  - 쓰기 + 응답 불필요? → Signal                                        │
│  - 쓰기 + 응답 필요? → Update                                          │
│  - Update 불가 환경? → Signal + Query 조합                              │
│                                                                         │
│  주의사항:                                                              │
│  - Query 안에서 절대 상태 변경/Activity 호출/외부 API 호출 금지         │
│  - Signal 핸들러는 상태만 변경, 로직은 메인 Workflow에서 처리           │
│  - Update Validator는 검증만, 상태 변경은 Handler에서                   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

> **다음 학습**: [07-advanced-topics.md](./07-advanced-topics.md) - Temporal 고급 주제
