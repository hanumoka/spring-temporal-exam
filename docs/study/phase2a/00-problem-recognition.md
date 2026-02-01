# MSA/EDA 문제 인식 종합

> **이 문서의 목적**: MSA 분산 트랜잭션과 EDA 환경에서 발생하는 문제들을 체계적으로 이해하고, 이러한 어려움을 직접 체험한 후 Temporal의 가치를 체감하기 위한 기반 지식을 제공합니다.

---

## 1. 프로젝트 핵심 목표

```
"MSA/EDA 환경의 어려움 체험 후 Temporal 도입 효과 학습"
```

### 이 프로젝트는 "해결책 모색"이 아니다

| 오해 | 실제 |
|------|------|
| MSA/EDA 문제의 해결책을 찾는 프로젝트 | 해결책(Temporal)은 **이미 정해져 있음** |
| 여러 솔루션 비교/검토 | Temporal 하나에 집중 |
| 문제 해결이 목표 | **"왜 Temporal이 필요한가"를 체감**하는 것이 목표 |

### 학습 구조

```
┌─────────────────────────────────────────────────────────────────┐
│                     학습 여정                                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   Phase 2-A: REST 기반 Saga 직접 구현                            │
│        ↓                                                         │
│   "이거 생각보다 엄청 복잡하네..."                                │
│   "보상 트랜잭션 관리가 힘드네..."                                │
│   "장애 복구는 어떻게 하지..."                                    │
│        ↓                                                         │
│   Phase 2-B: MQ + Redis 추가                                     │
│        ↓                                                         │
│   "더 복잡해졌네..."                                              │
│   "메시지 순서, 중복, 유실 처리해야 하네..."                       │
│        ↓                                                         │
│   Phase 3: Temporal 도입                                         │
│        ↓                                                         │
│   "아, 이래서 Temporal을 쓰는구나!"  ← 이 순간이 목표             │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. 모놀리식 vs MSA: 근본적 차이

### 모놀리식 (단일 애플리케이션)

```java
@Transactional  // 이 한 줄이 모든 것을 해결
public void createOrder(OrderRequest request) {
    Order order = orderRepository.save(new Order(...));
    inventoryRepository.decreaseStock(productId, quantity);
    paymentRepository.process(order.getId(), amount);
    // 어디서든 실패하면? → 자동 롤백. 끝.
}
```

- 하나의 DB, 하나의 트랜잭션
- `@Transactional` 하나로 ACID 보장
- 실패 시 자동 롤백

### MSA (분산 서비스)

```
[Client] → [Order Service] → [Inventory Service] → [Payment Service]
              (order_db)        (inventory_db)        (payment_db)
```

- 서비스마다 별도 DB
- 네트워크로 연결된 독립적인 프로세스
- `@Transactional`이 **서비스 경계를 넘지 못함**

---

## 3. MSA 분산 트랜잭션의 8가지 문제

### 문제 1: 부분 실패 (Partial Failure)

```
[주문 생성] ✓ 커밋 완료
     ↓
[재고 차감] ✓ 커밋 완료
     ↓
[결제 처리] ✗ 실패
```

**문제점:**
- 각 서비스는 독립된 DB, 독립된 트랜잭션
- 결제 실패 시 이미 커밋된 재고/주문을 되돌릴 수 없음
- 수동으로 **보상 트랜잭션(Compensation)** 필요

---

### 문제 2: 네트워크 불확실성 (Network Uncertainty)

```
[Orchestrator] ──요청──▶ [Payment Service]
                              ↓
                         결제 성공!
                              ↓
               ◀──응답── [타임아웃/유실]
                   ✗
```

**상황:** 결제는 성공했는데, 응답이 안 옴

| 선택 | 결과 |
|------|------|
| 재시도 | 중복 결제 위험 |
| 포기 | 실제론 성공했는데 실패 처리 |

이것이 **"분산 시스템의 2장군 문제"** - 요청이 실패인지, 응답만 유실된 것인지 구분 불가

---

### 문제 3: 중복 요청 (Duplicate Request)

```
[Client] ──주문 요청──▶ [Server]
           ↓ 타임아웃
[Client] ──같은 요청──▶ [Server]  // 재시도
           ↓
결과: 주문이 2개 생성됨
```

**해결 필요:** 멱등성(Idempotency) 보장 - Idempotency Key로 중복 감지

---

### 문제 4: 동시성 경쟁 (Race Condition)

```
재고: 1개

[요청 A] 조회 → 1개 → 차감 시도
[요청 B] 조회 → 1개 → 차감 시도

결과: 둘 다 성공? → 오버셀링 발생
```

**해결 필요:** 동시성 제어

| 방식 | 설명 | 적용 |
|------|------|------|
| Atomic UPDATE | `WHERE (qty - reserved) >= N` | 단순 동시성 |
| 낙관적 락 | `WHERE version = ?` | 충돌 적은 경우 |
| 비관적 락 | `SELECT FOR UPDATE` | 충돌 많은 경우 |
| 분산 락 | Redis RLock | 서비스 간 동시성 |

---

### 문제 5: 장애 전파 (Cascading Failure)

```
[Order] → [Inventory] → [Payment] → [External PG]
                                          ↓
                                    PG 장애 (응답 없음)
                                          ↓
                              전체 시스템 마비
```

**해결 필요:** 타임아웃, 서킷 브레이커, 벌크헤드

---

### 문제 6: 데이터 정합성 불일치 (Data Inconsistency)

```
같은 주문 #123 조회 시:

[Order Service]     → 상태: CONFIRMED
[Inventory Service] → 상태: RESERVED (아직 미반영)
[Payment Service]   → 상태: COMPLETED
```

**본질:** 강한 일관성(Strong Consistency) 포기, 최종 일관성(Eventual Consistency) 수용

---

### 문제 7: 장애 복구 (Failure Recovery)

```
Saga 실행 중:
1. 주문 생성 ✓
2. 재고 예약 ✓
3. 결제 처리 중... ← 서버 크래시!

[서버 재시작 후]
- 어디까지 진행됐지?
- 결제가 됐는지 안 됐는지 모름
```

**해결 필요:** Saga 상태 영속화, 복구 로직 구현

---

### 문제 8: 순서 역전 (Out-of-Order Processing)

```
[발행 순서]
1. OrderCreated
2. PaymentCompleted
3. ShippingStarted

[수신 순서] (네트워크 지연)
1. ShippingStarted   ← 주문이 없는데?
2. OrderCreated
3. PaymentCompleted
```

**해결 필요:** 시퀀스 번호, 파티션 키, 재정렬 버퍼

---

## 4. EDA(이벤트 기반 아키텍처)의 8가지 문제

### 문제 1: 메시지 유실 (Message Loss)

```
[Producer] ──이벤트──▶ [MQ] ──전달──▶ [Consumer]
                         ↓
                    브로커 장애
                         ↓
                    이벤트 유실
```

**해결 필요:** Producer ACK, MQ 영속화, Consumer ACK 후 처리

---

### 문제 2: 메시지 중복 (Duplicate Message)

```
[Consumer] 메시지 처리 완료
     ↓
[Consumer] ACK 전송 중... 크래시!
     ↓
[MQ] ACK 못 받음 → 재전송
```

**At-Least-Once의 부작용:** Consumer 멱등성 필수

---

### 문제 3: 순서 보장 어려움 (Ordering)

```
Partition 0: [Event A] [Event B] [Event C]
                ↓         ↓         ↓
Consumer 1: [A 처리중...]
Consumer 2:           [B 완료]
Consumer 3:                     [C 완료]

결과: B, C가 A보다 먼저 완료
```

**해결 필요:** 파티션 키, 단일 Consumer, 순서 무관 설계

---

### 문제 4: 이벤트 스키마 진화 (Schema Evolution)

```
v1: { orderId, amount }
v2: { orderId, amount, currency }  ← 필드 추가

[Producer v2] ──▶ [Consumer v1] → currency 필드 처리 불가
```

**해결 필요:** 스키마 버전 관리, 하위 호환성 유지

---

### 문제 5: 디버깅/추적 어려움 (Observability)

```
"주문 #123이 왜 실패했지?"

[Order Service] → 로그: 주문 생성 OK
        ↓ (이벤트)
[Inventory Service] → 로그: ???
```

**해결 필요:** Correlation ID 전파, 분산 추적

---

### 문제 6: 복잡한 에러 처리 (Error Handling)

```
[Consumer] 이벤트 처리 실패
     ↓
재시도? 몇 번? 간격은?
     ↓
계속 실패하면? → DLQ
```

**해결 필요:** 재시도 정책, DLQ + 모니터링

---

### 문제 7: 최종 일관성 관리 (Eventual Consistency)

```
[주문 생성] ──이벤트──▶ [재고 차감]
     ↓                      ↓
   즉시 반영              언젠가 반영
     ↓
[사용자 조회]
"주문은 됐는데 재고가 아직 그대로네?"
```

**해결 필요:** UI에서 "처리 중" 상태 표시, 보상 로직

---

### 문제 8: 테스트 어려움 (Testing Complexity)

```
[통합 테스트]
- MQ 인프라 필요
- 비동기 완료 대기 방법?
- 타이밍 이슈로 불안정
```

**해결 필요:** Testcontainers, Awaitility

---

## 5. MSA와 EDA 문제의 관계

### 공통 문제 (같은 뿌리)

```
┌─────────────────────────────────────────────────────────────────┐
│                    근본 원인: 분산 시스템                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│                    ┌─────────────────┐                          │
│                    │   분산 시스템    │                          │
│                    │   근본 한계      │                          │
│                    │  (CAP, 네트워크) │                          │
│                    └────────┬────────┘                          │
│                             │                                    │
│              ┌──────────────┴──────────────┐                    │
│              │                             │                     │
│              ▼                             ▼                     │
│     ┌─────────────────┐          ┌─────────────────┐            │
│     │ 분산 트랜잭션   │          │      EDA        │            │
│     │ (동기 호출)     │          │  (비동기 이벤트) │            │
│     └─────────────────┘          └─────────────────┘            │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 문제 매핑

| 분산 트랜잭션 | EDA | 공통 본질 |
|--------------|-----|----------|
| 중복 요청 | 메시지 중복 | **멱등성 필요** |
| 순서 역전 | 순서 보장 어려움 | **순서 문제** |
| 장애 복구 | 에러 처리/DLQ | **실패 복구** |
| 데이터 정합성 | 최종 일관성 | **일관성 관리** |
| 네트워크 불확실성 | 메시지 유실 | **전달 보장** |

---

## 6. 재고 관리 패턴 비교

### 즉시 차감 패턴

```sql
-- 차감
UPDATE inventory SET quantity = quantity - 5;

-- 복구
UPDATE inventory SET quantity = quantity + 5;
```

### 예약 패턴

```sql
-- 예약
UPDATE inventory SET reserved = reserved + 5;

-- 확정
UPDATE inventory SET quantity = quantity - 5, reserved = reserved - 5;

-- 취소
UPDATE inventory SET reserved = reserved - 5;
```

### 비교 (Atomic UPDATE 사용 기준)

| 항목 | 즉시 차감 + Atomic | 예약 패턴 + Atomic |
|------|-------------------|-------------------|
| 오버셀링 방지 | ✓ 해결 | ✓ 해결 |
| 보상 실패 시 | ✗ 재고 영구 유실 | ✓ quantity 불변, 좀비 예약만 |
| 크래시 복구 | ✗ 상태 판단 어려움 | ✓ TTL 자동 정리 |
| 복구 추적 | ✗ 별도 이력 필요 | ✓ 예약 정보 포함 |

**핵심 원칙:** 예약 패턴은 `quantity`를 확정 전까지 변경하지 않음으로써 안전한 복구 보장

---

## 7. 강한 일관성 vs 최종 일관성

### 강한 일관성 (Strong Consistency)

```
쓰기 완료 후, 모든 읽기는 항상 최신 값을 반환
```

- 쓰기 느림 (모든 노드 동기화 대기)
- 가용성 낮음 (노드 장애 시 쓰기 불가)
- 사용: 금융, 결제, 재고

### 최종 일관성 (Eventual Consistency)

```
쓰기 완료 후, "언젠가는" 모든 읽기가 최신 값을 반환
```

- 쓰기 빠름 (즉시 응답)
- 가용성 높음
- 사용: SNS, 캐시, 로그

### MSA에서의 현실

```
[서비스 내부] → 강한 일관성 (단일 DB, @Transactional)
[서비스 간]   → 최종 일관성 (Saga, 이벤트)
```

---

## 8. 16가지 문제 요약

### MSA 분산 트랜잭션 (8가지)

| # | 문제 | 핵심 |
|---|------|------|
| 1 | 부분 실패 | 일부만 성공, 수동 보상 필요 |
| 2 | 네트워크 불확실성 | 성공/실패 구분 불가 |
| 3 | 중복 요청 | 재시도로 인한 중복 처리 |
| 4 | 동시성 경쟁 | 같은 자원 동시 접근 |
| 5 | 장애 전파 | 하나의 장애가 전체로 확산 |
| 6 | 데이터 정합성 | 서비스 간 상태 불일치 |
| 7 | 장애 복구 | 크래시 후 상태 복원 |
| 8 | 순서 역전 | 이벤트 도착 순서 뒤바뀜 |

### EDA 이벤트 기반 (8가지)

| # | 문제 | 핵심 |
|---|------|------|
| 1 | 메시지 유실 | 이벤트가 사라짐 |
| 2 | 메시지 중복 | 같은 이벤트 여러 번 수신 |
| 3 | 순서 보장 | 병렬 처리 시 순서 깨짐 |
| 4 | 스키마 진화 | 버전 호환성 관리 |
| 5 | 디버깅 추적 | 흐름 파악 어려움 |
| 6 | 에러 처리 | 재시도/DLQ 전략 복잡 |
| 7 | 최종 일관성 | 일시적 불일치 허용 |
| 8 | 테스트 | 비동기 검증 어려움 |

---

## 9. 학습 연결고리

이 문서에서 인식한 문제들은 다음 학습 문서에서 해결 방법을 다룹니다:

| 문제 | 학습 문서 |
|------|----------|
| 부분 실패 + 보상 | [01-saga-pattern.md](./01-saga-pattern.md) |
| 중복 요청 | [02-idempotency.md](./02-idempotency.md) |
| 장애 전파 | [03-resilience4j.md](./03-resilience4j.md) |
| 동시성 경쟁 | [04-distributed-lock.md](./04-distributed-lock.md), [05-optimistic-lock.md](./05-optimistic-lock.md) |
| 메시지 유실/중복 | [Phase 2-B: 04-outbox-pattern.md](../phase2b/04-outbox-pattern.md) |
| 디버깅 추적 | [Phase 2-B: 05-opentelemetry-zipkin.md](../phase2b/05-opentelemetry-zipkin.md) |
| 전체 문제 통합 해결 | [Phase 3: 01-temporal-concepts.md](../phase3/01-temporal-concepts.md) |
| Temporal 한계와 보완 | [Phase 3: 03-temporal-limitations.md](../phase3/03-temporal-limitations.md) |

---

## 10. Temporal 미리보기

위의 모든 문제를 순수하게 구현하려면 수백 줄의 보일러플레이트 코드가 필요합니다.
Temporal은 이러한 인프라 복잡성을 흡수하여 개발자가 비즈니스 로직에 집중하게 해줍니다.

```java
// 500줄+ 순수 구현 → ~50줄 Temporal 구현
@WorkflowMethod
public void processOrder(OrderRequest request) {
    Saga saga = new Saga(options);

    try {
        saga.addCompensation(() -> activities.cancelOrder(orderId));
        activities.createOrder(request);

        saga.addCompensation(() -> activities.cancelReservation(productId));
        activities.reserveInventory(productId, quantity);

        saga.addCompensation(() -> activities.refundPayment(orderId));
        activities.processPayment(orderId, amount);

    } catch (Exception e) {
        saga.compensate();  // 자동 역순 보상
        throw e;
    }
}
```

**하지만 Temporal도 모든 것을 해결하지 않습니다.** 동시성 제어, 외부 서비스 멱등성 등은 여전히 개발자 책임입니다.
이에 대한 상세 내용은 [03-temporal-limitations.md](../phase3/03-temporal-limitations.md)를 참조하세요.

---

## 실습 가이드

### Step 1: 문제 인식 확인

각 문제에 대해 다음 질문에 답해보세요:

1. 이 문제가 왜 발생하는가?
2. 모놀리식에서는 왜 문제가 안 되었는가?
3. 이 문제를 해결하지 않으면 어떤 일이 발생하는가?

### Step 2: 순수 구현 체험

Phase 2-A, 2-B에서 각 문제에 대한 해결책을 직접 구현하며 복잡성을 체험합니다.

### Step 3: Temporal 전환

Phase 3에서 동일한 기능을 Temporal로 전환하며 얼마나 코드가 단순해지는지 체감합니다.

---

> **핵심 메시지**: 문제를 해결하기 위해서가 아니라, 문제를 직접 겪어보고 "왜 어려운지" 체감하기 위해 이 학습 과정을 진행합니다.
