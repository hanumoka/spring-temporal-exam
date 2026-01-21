# 글로벌 예외 처리

## 이 문서에서 배우는 것

- 예외 처리 전략과 설계 원칙
- Spring의 @RestControllerAdvice 활용
- 비즈니스 예외와 시스템 예외 분리
- 일관된 에러 응답 구조

---

## 1. 예외 처리가 중요한 이유

### 예외 처리 없이 발생하는 문제

```json
// 스프링 기본 에러 응답 (사용자 친화적이지 않음)
{
  "timestamp": "2024-01-15T10:30:00.000+00:00",
  "status": 500,
  "error": "Internal Server Error",
  "path": "/orders"
}
```

**문제점**:
- 클라이언트가 에러 원인을 알 수 없음
- 일관되지 않은 에러 형식
- 민감한 정보 노출 가능성

### 좋은 예외 처리

```json
// 일관된 에러 응답
{
  "code": "INSUFFICIENT_STOCK",
  "message": "재고가 부족합니다",
  "details": {
    "productId": 123,
    "requestedQuantity": 10,
    "availableQuantity": 5
  },
  "timestamp": "2024-01-15T10:30:00"
}
```

---

## 2. 예외 계층 설계

### 비즈니스 예외 vs 시스템 예외

```
┌─────────────────────────────────────────────────────────────┐
│                      예외 계층                               │
│                                                              │
│  RuntimeException                                            │
│       │                                                      │
│       ├── BusinessException (비즈니스 예외)                  │
│       │       ├── OrderNotFoundException                    │
│       │       ├── InsufficientStockException                │
│       │       ├── PaymentFailedException                    │
│       │       └── InvalidOrderStatusException               │
│       │                                                      │
│       └── SystemException (시스템 예외)                      │
│               ├── ExternalServiceException                  │
│               ├── DatabaseException                         │
│               └── CacheException                            │
└─────────────────────────────────────────────────────────────┘
```

### 기본 예외 클래스

```java
// 비즈니스 예외 베이스
@Getter
public abstract class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    protected BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    protected BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}

// 시스템 예외 베이스
@Getter
public abstract class SystemException extends RuntimeException {

    private final ErrorCode errorCode;

    protected SystemException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
```

### 에러 코드 정의

```java
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통
    INVALID_INPUT("C001", "잘못된 입력입니다", HttpStatus.BAD_REQUEST),
    INTERNAL_ERROR("C002", "내부 서버 오류가 발생했습니다", HttpStatus.INTERNAL_SERVER_ERROR),

    // 주문
    ORDER_NOT_FOUND("O001", "주문을 찾을 수 없습니다", HttpStatus.NOT_FOUND),
    INVALID_ORDER_STATUS("O002", "주문 상태가 올바르지 않습니다", HttpStatus.BAD_REQUEST),
    ORDER_ALREADY_CANCELLED("O003", "이미 취소된 주문입니다", HttpStatus.CONFLICT),

    // 재고
    PRODUCT_NOT_FOUND("I001", "상품을 찾을 수 없습니다", HttpStatus.NOT_FOUND),
    INSUFFICIENT_STOCK("I002", "재고가 부족합니다", HttpStatus.CONFLICT),

    // 결제
    PAYMENT_FAILED("P001", "결제에 실패했습니다", HttpStatus.BAD_REQUEST),
    PAYMENT_ALREADY_COMPLETED("P002", "이미 완료된 결제입니다", HttpStatus.CONFLICT),
    REFUND_FAILED("P003", "환불에 실패했습니다", HttpStatus.INTERNAL_SERVER_ERROR),

    // 외부 서비스
    EXTERNAL_SERVICE_ERROR("E001", "외부 서비스 오류가 발생했습니다", HttpStatus.SERVICE_UNAVAILABLE),
    SERVICE_TIMEOUT("E002", "서비스 응답 시간이 초과되었습니다", HttpStatus.GATEWAY_TIMEOUT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
```

---

## 3. 구체적인 예외 클래스

### 주문 관련 예외

```java
// 주문을 찾을 수 없음
public class OrderNotFoundException extends BusinessException {

    private final Long orderId;

    public OrderNotFoundException(Long orderId) {
        super(ErrorCode.ORDER_NOT_FOUND, "주문을 찾을 수 없습니다: " + orderId);
        this.orderId = orderId;
    }

    public Long getOrderId() {
        return orderId;
    }
}

// 잘못된 주문 상태
public class InvalidOrderStatusException extends BusinessException {

    private final String currentStatus;
    private final String expectedStatus;

    public InvalidOrderStatusException(String current, String expected) {
        super(ErrorCode.INVALID_ORDER_STATUS,
            String.format("주문 상태가 올바르지 않습니다. 현재: %s, 필요: %s", current, expected));
        this.currentStatus = current;
        this.expectedStatus = expected;
    }
}
```

### 재고 관련 예외

```java
// 재고 부족
@Getter
public class InsufficientStockException extends BusinessException {

    private final Long productId;
    private final int requestedQuantity;
    private final int availableQuantity;

    public InsufficientStockException(Long productId, int requested, int available) {
        super(ErrorCode.INSUFFICIENT_STOCK,
            String.format("재고 부족: 상품 %d, 요청 %d개, 가용 %d개",
                productId, requested, available));
        this.productId = productId;
        this.requestedQuantity = requested;
        this.availableQuantity = available;
    }
}
```

### 결제 관련 예외

```java
// 결제 실패
@Getter
public class PaymentFailedException extends BusinessException {

    private final String reason;

    public PaymentFailedException(String reason) {
        super(ErrorCode.PAYMENT_FAILED, "결제 실패: " + reason);
        this.reason = reason;
    }
}
```

---

## 4. 에러 응답 DTO

```java
@Getter
@Builder
public class ErrorResponse {

    private final String code;
    private final String message;
    private final Object details;
    private final LocalDateTime timestamp;

    public static ErrorResponse of(ErrorCode errorCode) {
        return ErrorResponse.builder()
            .code(errorCode.getCode())
            .message(errorCode.getMessage())
            .timestamp(LocalDateTime.now())
            .build();
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return ErrorResponse.builder()
            .code(errorCode.getCode())
            .message(message)
            .timestamp(LocalDateTime.now())
            .build();
    }

    public static ErrorResponse of(ErrorCode errorCode, String message, Object details) {
        return ErrorResponse.builder()
            .code(errorCode.getCode())
            .message(message)
            .details(details)
            .timestamp(LocalDateTime.now())
            .build();
    }
}
```

---

## 5. 글로벌 예외 핸들러

```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // =====================
    // 비즈니스 예외 처리
    // =====================

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
        log.warn("비즈니스 예외 발생: {}", e.getMessage());

        ErrorCode errorCode = e.getErrorCode();
        ErrorResponse response = ErrorResponse.of(errorCode, e.getMessage());

        return ResponseEntity
            .status(errorCode.getHttpStatus())
            .body(response);
    }

    // 재고 부족 예외 (상세 정보 포함)
    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientStock(InsufficientStockException e) {
        log.warn("재고 부족: productId={}, requested={}, available={}",
            e.getProductId(), e.getRequestedQuantity(), e.getAvailableQuantity());

        Map<String, Object> details = Map.of(
            "productId", e.getProductId(),
            "requestedQuantity", e.getRequestedQuantity(),
            "availableQuantity", e.getAvailableQuantity()
        );

        ErrorResponse response = ErrorResponse.of(
            e.getErrorCode(),
            e.getMessage(),
            details
        );

        return ResponseEntity
            .status(e.getErrorCode().getHttpStatus())
            .body(response);
    }

    // =====================
    // 검증 예외 처리
    // =====================

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        log.warn("입력값 검증 실패");

        List<Map<String, Object>> errors = e.getBindingResult().getFieldErrors().stream()
            .map(error -> Map.<String, Object>of(
                "field", error.getField(),
                "message", error.getDefaultMessage(),
                "rejectedValue", String.valueOf(error.getRejectedValue())
            ))
            .toList();

        ErrorResponse response = ErrorResponse.of(
            ErrorCode.INVALID_INPUT,
            "입력값이 올바르지 않습니다",
            errors
        );

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(response);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
        log.warn("제약 조건 위반");

        List<Map<String, Object>> errors = e.getConstraintViolations().stream()
            .map(v -> Map.<String, Object>of(
                "field", v.getPropertyPath().toString(),
                "message", v.getMessage()
            ))
            .toList();

        ErrorResponse response = ErrorResponse.of(
            ErrorCode.INVALID_INPUT,
            "입력값이 올바르지 않습니다",
            errors
        );

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(response);
    }

    // =====================
    // 시스템 예외 처리
    // =====================

    @ExceptionHandler(SystemException.class)
    public ResponseEntity<ErrorResponse> handleSystemException(SystemException e) {
        log.error("시스템 예외 발생", e);

        ErrorResponse response = ErrorResponse.of(e.getErrorCode());

        return ResponseEntity
            .status(e.getErrorCode().getHttpStatus())
            .body(response);
    }

    // =====================
    // 기타 예외 처리
    // =====================

    // 낙관적 락 충돌
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockingFailureException e) {
        log.warn("낙관적 락 충돌: {}", e.getMessage());

        ErrorResponse response = ErrorResponse.builder()
            .code("CONFLICT")
            .message("다른 사용자가 먼저 수정했습니다. 다시 시도해주세요.")
            .timestamp(LocalDateTime.now())
            .build();

        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(response);
    }

    // 알 수 없는 예외 (최후의 방어선)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknownException(Exception e) {
        log.error("알 수 없는 예외 발생", e);

        // 운영 환경에서는 상세 메시지 숨김
        ErrorResponse response = ErrorResponse.of(ErrorCode.INTERNAL_ERROR);

        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(response);
    }
}
```

---

## 6. 예외 발생 예시

### 서비스에서 예외 발생

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final InventoryRepository inventoryRepository;

    public Order createOrder(CreateOrderRequest request) {
        // 상품 존재 확인
        Product product = inventoryRepository.findById(request.productId())
            .orElseThrow(() -> new ProductNotFoundException(request.productId()));

        // 재고 확인
        if (product.getStock() < request.quantity()) {
            throw new InsufficientStockException(
                request.productId(),
                request.quantity(),
                product.getStock()
            );
        }

        // 주문 생성...
        return order;
    }

    public void confirmOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new InvalidOrderStatusException(
                order.getStatus().name(),
                OrderStatus.PENDING.name()
            );
        }

        order.confirm();
        orderRepository.save(order);
    }
}
```

---

## 7. 클라이언트 에러 응답 예시

### 재고 부족

```json
HTTP/1.1 409 Conflict
{
  "code": "I002",
  "message": "재고 부족: 상품 123, 요청 10개, 가용 5개",
  "details": {
    "productId": 123,
    "requestedQuantity": 10,
    "availableQuantity": 5
  },
  "timestamp": "2024-01-15T10:30:00"
}
```

### 입력값 오류

```json
HTTP/1.1 400 Bad Request
{
  "code": "C001",
  "message": "입력값이 올바르지 않습니다",
  "details": [
    {
      "field": "quantity",
      "message": "수량은 1 이상이어야 합니다",
      "rejectedValue": "-5"
    },
    {
      "field": "customerId",
      "message": "고객 ID는 필수입니다",
      "rejectedValue": "null"
    }
  ],
  "timestamp": "2024-01-15T10:30:00"
}
```

### 리소스 없음

```json
HTTP/1.1 404 Not Found
{
  "code": "O001",
  "message": "주문을 찾을 수 없습니다: 999",
  "timestamp": "2024-01-15T10:30:00"
}
```

---

## 8. 테스트

```java
@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @Test
    void 존재하지_않는_주문_조회시_404_응답() throws Exception {
        // given
        Long orderId = 999L;
        given(orderService.findById(orderId))
            .willThrow(new OrderNotFoundException(orderId));

        // when & then
        mockMvc.perform(get("/orders/{id}", orderId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("O001"))
            .andExpect(jsonPath("$.message").value(containsString("999")));
    }

    @Test
    void 재고_부족시_409_응답() throws Exception {
        // given
        given(orderService.create(any()))
            .willThrow(new InsufficientStockException(123L, 10, 5));

        // when & then
        mockMvc.perform(post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{...}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("I002"))
            .andExpect(jsonPath("$.details.availableQuantity").value(5));
    }
}
```

---

## 9. 실습 과제

1. ErrorCode enum 정의
2. BusinessException 베이스 클래스 생성
3. 도메인별 구체 예외 클래스 생성
4. GlobalExceptionHandler 구현
5. 예외 발생 및 응답 테스트

---

## 참고 자료

- [Spring Error Handling](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-exceptionhandler.html)
- [RFC 7807 - Problem Details for HTTP APIs](https://datatracker.ietf.org/doc/html/rfc7807)
- [Baeldung - Exception Handling in Spring](https://www.baeldung.com/exception-handling-for-rest-with-spring)

---

## 다음 단계

[08-mdc-logging.md](./08-mdc-logging.md) - MDC 로깅으로 이동
