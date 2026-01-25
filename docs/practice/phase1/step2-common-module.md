# Step 2: 공통 모듈 (common) 구성

> **선행 조건**: [step1-multimodule-setup.md](./step1-multimodule-setup.md) 완료

## 목표

모든 서비스에서 공유하는 DTO, 예외, 이벤트 클래스를 common 모듈에 구성합니다.

---

## 2-1. common/build.gradle 작성

### 해야 할 일

common 모듈의 build.gradle을 작성하세요.

### 고려 사항

- common은 **라이브러리 모듈**입니다 (실행 불가)
- 다른 모듈에서 `implementation project(':common')`으로 참조합니다
- `java-library` 플러그인 사용을 고려하세요

### 필요한 의존성

| 의존성 | 용도 |
|--------|------|
| spring-boot-starter-validation | Bean Validation |
| lombok | 보일러플레이트 감소 |

### 힌트

```groovy
plugins {
    id 'java-library'  // java 대신 java-library
}

dependencies {
    // api vs implementation 차이를 고려하세요
    // api: 의존성이 전이됨 (다른 모듈에서도 접근 가능)
    // implementation: 의존성이 전이되지 않음
}
```

### 질문: api vs implementation?

common 모듈의 의존성을 다른 모듈에서도 사용해야 할까요?
- `@NotNull`, `@Valid` 등의 어노테이션을 서비스 모듈에서 사용하려면?

### 검증 방법

```bash
./gradlew :common:build
./gradlew :common:dependencies
```

### 체크리스트

```
[ ] java-library 플러그인 적용
[ ] validation 의존성 추가
[ ] lombok 의존성 추가
[ ] api vs implementation 결정
```

---

## 2-2. 공통 DTO 정의

### 해야 할 일

서비스 간 통신에 사용할 공통 DTO를 정의하세요.

### 패키지 구조

```
common/src/main/java/com/hanumoka/common/
└── dto/
    ├── order/
    │   ├── CreateOrderRequest.java
    │   ├── OrderResponse.java
    │   └── OrderStatus.java (enum)
    ├── inventory/
    │   ├── ReserveStockRequest.java
    │   └── StockResponse.java
    └── payment/
        ├── ProcessPaymentRequest.java
        └── PaymentResponse.java
```

### 설계 가이드

#### CreateOrderRequest

주문 생성 요청에 필요한 필드를 생각해보세요:
- 고객 정보
- 상품 정보
- 수량
- 멱등성 키 (Idempotency Key)

#### OrderStatus (enum)

주문 상태 흐름을 생각해보세요:
```
PENDING → CONFIRMED → COMPLETED
    ↓
CANCELLED
```

### 힌트

```java
// record를 사용하면 간결합니다 (Java 16+)
public record CreateOrderRequest(
    // 필드 정의
) {}

// 또는 전통적인 클래스 + Lombok
@Getter
@Builder
public class CreateOrderRequest {
    // 필드 정의
}
```

### Bean Validation 적용

각 필드에 적절한 검증 어노테이션을 추가하세요:
- `@NotNull`, `@NotBlank`
- `@Positive`, `@Min`, `@Max`
- `@Size`

### 검증 방법

```bash
./gradlew :common:compileJava
```

### 체크리스트

```
[ ] dto 패키지 구조 생성
[ ] CreateOrderRequest 정의
[ ] OrderResponse 정의
[ ] OrderStatus enum 정의
[ ] ReserveStockRequest 정의
[ ] ProcessPaymentRequest 정의
[ ] Bean Validation 어노테이션 적용
```

---

## 2-3. 공통 예외 클래스 정의

### 해야 할 일

비즈니스 예외를 정의하세요.

### 패키지 구조

```
common/src/main/java/com/hanumoka/common/
└── exception/
    ├── BusinessException.java (기본 예외)
    ├── OrderNotFoundException.java
    ├── InsufficientStockException.java
    ├── PaymentFailedException.java
    └── ErrorCode.java (enum)
```

### 설계 가이드

#### BusinessException (기본 예외)

모든 비즈니스 예외의 부모 클래스입니다:
- ErrorCode를 포함
- HTTP 상태 코드 매핑 가능

#### ErrorCode (enum)

에러 코드를 중앙 관리합니다:
```java
public enum ErrorCode {
    // 주문 관련
    ORDER_NOT_FOUND("ORD001", "주문을 찾을 수 없습니다", HttpStatus.NOT_FOUND),

    // 재고 관련
    INSUFFICIENT_STOCK("INV001", "재고가 부족합니다", HttpStatus.CONFLICT),

    // 결제 관련
    PAYMENT_FAILED("PAY001", "결제에 실패했습니다", HttpStatus.BAD_REQUEST);

    // 필드, 생성자, getter
}
```

### 힌트

```java
@Getter
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    // 생성자
}
```

### 질문: Checked vs Unchecked?

비즈니스 예외는 왜 `RuntimeException`을 상속할까요?
- 트랜잭션 롤백과의 관계를 생각해보세요

### 체크리스트

```
[ ] ErrorCode enum 정의
[ ] BusinessException 기본 클래스 정의
[ ] OrderNotFoundException 정의
[ ] InsufficientStockException 정의
[ ] PaymentFailedException 정의
```

---

## 2-4. 공통 이벤트 클래스 정의

### 해야 할 일

도메인 이벤트를 정의하세요. (Phase 2-B에서 사용)

### 패키지 구조

```
common/src/main/java/com/hanumoka/common/
└── event/
    ├── DomainEvent.java (인터페이스 또는 추상 클래스)
    ├── OrderCreatedEvent.java
    ├── OrderConfirmedEvent.java
    ├── PaymentCompletedEvent.java
    └── StockReservedEvent.java
```

### 설계 가이드

#### DomainEvent (기본 이벤트)

모든 이벤트가 공통으로 가져야 할 필드:
- 이벤트 ID
- 발생 시간
- 집계 ID (Aggregate ID)

### 힌트

```java
public interface DomainEvent {
    String getEventId();
    Instant getOccurredAt();
    String getAggregateId();
}
```

### 체크리스트

```
[ ] DomainEvent 인터페이스/추상클래스 정의
[ ] OrderCreatedEvent 정의
[ ] OrderConfirmedEvent 정의
[ ] PaymentCompletedEvent 정의
[ ] StockReservedEvent 정의
```

---

## 최종 검증

### 전체 빌드

```bash
./gradlew :common:clean :common:build
```

### 다른 모듈에서 참조 테스트

service-order의 build.gradle에 추가:
```groovy
dependencies {
    implementation project(':common')
}
```

```bash
./gradlew :service-order:compileJava
```

### 성공 기준

- [ ] common 모듈 빌드 성공
- [ ] 다른 모듈에서 common 참조 가능
- [ ] DTO, 예외, 이벤트 클래스 정의 완료

---

## 참고: 폴더 구조 최종 형태

```
common/
├── build.gradle
└── src/
    └── main/
        └── java/
            └── com/
                └── hanumoka/
                    └── common/
                        ├── dto/
                        │   ├── order/
                        │   ├── inventory/
                        │   └── payment/
                        ├── exception/
                        │   ├── BusinessException.java
                        │   ├── ErrorCode.java
                        │   └── ...
                        └── event/
                            ├── DomainEvent.java
                            └── ...
```

---

## 트러블슈팅

### 문제: validation 어노테이션을 찾을 수 없음

**원인**: validation 의존성 누락 또는 api/implementation 문제
**해결**:
1. common에 validation 의존성 확인
2. `api` 키워드로 선언했는지 확인

### 문제: 다른 모듈에서 클래스 접근 불가

**원인**: 패키지 가시성 또는 의존성 문제
**해결**:
1. 클래스가 `public`인지 확인
2. `implementation project(':common')` 선언 확인

---

## 다음 단계

Step 2 완료 후 → [step3-docker-infra.md](./step3-docker-infra.md) (작성 예정)

---

## 자가 점검 질문

1. `api`와 `implementation`의 차이는 무엇인가요?
2. 왜 비즈니스 예외는 RuntimeException을 상속하나요?
3. record와 class 중 DTO에 어떤 것을 선택했나요? 그 이유는?
4. ErrorCode를 enum으로 관리하면 어떤 장점이 있나요?
