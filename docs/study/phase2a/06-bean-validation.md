# Bean Validation - 입력 검증

## 이 문서에서 배우는 것

- Bean Validation의 개념과 필요성
- 기본 제약 조건 어노테이션
- 커스텀 검증 구현
- Spring Boot에서의 활용

---

## 1. 왜 입력 검증이 필요한가?

### 검증 없이 발생하는 문제

```java
// 검증 없는 코드
@PostMapping("/orders")
public Order createOrder(@RequestBody OrderRequest request) {
    // request.quantity가 음수면? → 재고가 오히려 증가!
    // request.customerId가 null이면? → NullPointerException!
    // request.email이 이상하면? → 알림 발송 실패!
    return orderService.create(request);
}
```

### 잘못된 검증 패턴

```java
// ❌ 서비스 로직에 검증 로직이 섞임
public Order createOrder(OrderRequest request) {
    if (request.getQuantity() == null) {
        throw new IllegalArgumentException("수량은 필수입니다");
    }
    if (request.getQuantity() < 1) {
        throw new IllegalArgumentException("수량은 1 이상이어야 합니다");
    }
    if (request.getProductId() == null) {
        throw new IllegalArgumentException("상품 ID는 필수입니다");
    }
    // ... 비즈니스 로직이 묻힘
}
```

### Bean Validation 사용

```java
// ✓ 선언적 검증
public record OrderRequest(
    @NotNull(message = "상품 ID는 필수입니다")
    Long productId,

    @NotNull(message = "수량은 필수입니다")
    @Min(value = 1, message = "수량은 1 이상이어야 합니다")
    Integer quantity,

    @NotNull @Positive
    Long customerId
) {}
```

---

## 2. Bean Validation 기본

### 의존성

Spring Boot Starter에 포함되어 있습니다:

```groovy
// build.gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-validation'
}
```

### 기본 제약 조건

| 어노테이션 | 설명 | 예시 |
|-----------|------|------|
| `@NotNull` | null 불가 | `@NotNull Long id` |
| `@NotEmpty` | null, 빈 문자열/컬렉션 불가 | `@NotEmpty String name` |
| `@NotBlank` | null, 빈 문자열, 공백만 불가 | `@NotBlank String name` |
| `@Size` | 크기 제한 | `@Size(min=2, max=100)` |
| `@Min` / `@Max` | 최소/최대 값 | `@Min(1) @Max(100)` |
| `@Positive` | 양수만 | `@Positive BigDecimal price` |
| `@PositiveOrZero` | 0 이상 | `@PositiveOrZero Integer stock` |
| `@Negative` | 음수만 | `@Negative Integer discount` |
| `@Email` | 이메일 형식 | `@Email String email` |
| `@Pattern` | 정규식 매칭 | `@Pattern(regexp="...")` |
| `@Past` / `@Future` | 과거/미래 날짜 | `@Past LocalDate birthDate` |
| `@AssertTrue` / `@AssertFalse` | 참/거짓 검증 | `@AssertTrue boolean agreed` |

### NotNull vs NotEmpty vs NotBlank

```java
String value = null;    // @NotNull ❌ @NotEmpty ❌ @NotBlank ❌
String value = "";      // @NotNull ✓  @NotEmpty ❌ @NotBlank ❌
String value = "   ";   // @NotNull ✓  @NotEmpty ✓  @NotBlank ❌
String value = "hello"; // @NotNull ✓  @NotEmpty ✓  @NotBlank ✓
```

---

## 3. 컨트롤러에서 사용

### 3.1 요청 본문 검증 (@Valid)

```java
@RestController
@RequestMapping("/orders")
public class OrderController {

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @Valid @RequestBody OrderRequest request) {  // @Valid 필수!

        Order order = orderService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(order));
    }
}
```

### 3.2 경로 변수 / 요청 파라미터 검증

```java
@RestController
@RequestMapping("/orders")
@Validated  // 클래스에 추가 필요!
public class OrderController {

    @GetMapping("/{orderId}")
    public OrderResponse getOrder(
            @PathVariable @Positive(message = "주문 ID는 양수여야 합니다") Long orderId) {
        return orderService.findById(orderId);
    }

    @GetMapping
    public List<OrderResponse> searchOrders(
            @RequestParam @Min(0) int page,
            @RequestParam @Min(1) @Max(100) int size) {
        return orderService.search(page, size);
    }
}
```

### 3.3 DTO 정의 예시

```java
// 주문 생성 요청
public record CreateOrderRequest(
    @NotNull(message = "상품 ID는 필수입니다")
    Long productId,

    @NotNull(message = "수량은 필수입니다")
    @Min(value = 1, message = "수량은 1개 이상이어야 합니다")
    @Max(value = 100, message = "수량은 100개 이하여야 합니다")
    Integer quantity,

    @NotNull(message = "고객 ID는 필수입니다")
    @Positive(message = "고객 ID는 양수여야 합니다")
    Long customerId,

    @Size(max = 500, message = "메모는 500자 이하여야 합니다")
    String memo
) {}
```

```java
// 회원 가입 요청
public record SignUpRequest(
    @NotBlank(message = "이메일은 필수입니다")
    @Email(message = "유효한 이메일 형식이 아닙니다")
    String email,

    @NotBlank(message = "비밀번호는 필수입니다")
    @Size(min = 8, max = 20, message = "비밀번호는 8~20자여야 합니다")
    @Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).*$",
        message = "비밀번호는 대소문자와 숫자를 포함해야 합니다"
    )
    String password,

    @NotBlank(message = "이름은 필수입니다")
    @Size(min = 2, max = 50, message = "이름은 2~50자여야 합니다")
    String name,

    @NotNull(message = "생년월일은 필수입니다")
    @Past(message = "생년월일은 과거 날짜여야 합니다")
    LocalDate birthDate
) {}
```

---

## 4. 중첩 객체 검증

### @Valid로 중첩 객체 검증

```java
public record OrderRequest(
    @NotNull
    Long customerId,

    @NotEmpty(message = "주문 항목은 1개 이상이어야 합니다")
    @Valid  // 중첩 객체도 검증!
    List<OrderItemRequest> items,

    @Valid  // 중첩 객체도 검증!
    @NotNull
    ShippingAddress shippingAddress
) {}

public record OrderItemRequest(
    @NotNull
    Long productId,

    @Positive
    Integer quantity
) {}

public record ShippingAddress(
    @NotBlank
    String address,

    @NotBlank
    @Pattern(regexp = "\\d{5}", message = "우편번호는 5자리 숫자입니다")
    String zipCode
) {}
```

---

## 5. 검증 그룹

### 상황에 따라 다른 검증

```java
// 검증 그룹 정의
public interface CreateGroup {}
public interface UpdateGroup {}

public class ProductRequest {
    @Null(groups = CreateGroup.class, message = "생성 시 ID는 비워야 합니다")
    @NotNull(groups = UpdateGroup.class, message = "수정 시 ID는 필수입니다")
    private Long id;

    @NotBlank(groups = {CreateGroup.class, UpdateGroup.class})
    private String name;

    @NotNull(groups = CreateGroup.class)
    private BigDecimal price;
}
```

```java
// 컨트롤러에서 그룹 지정
@PostMapping
public Product create(
        @Validated(CreateGroup.class) @RequestBody ProductRequest request) {
    // id는 null이어야 함
}

@PutMapping("/{id}")
public Product update(
        @PathVariable Long id,
        @Validated(UpdateGroup.class) @RequestBody ProductRequest request) {
    // id는 필수
}
```

---

## 6. 커스텀 검증

### 6.1 커스텀 어노테이션 정의

```java
// 전화번호 검증 어노테이션
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PhoneNumberValidator.class)
public @interface PhoneNumber {
    String message() default "유효한 전화번호 형식이 아닙니다";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
```

### 6.2 검증기 구현

```java
public class PhoneNumberValidator implements ConstraintValidator<PhoneNumber, String> {

    private static final Pattern PHONE_PATTERN =
        Pattern.compile("^01[0-9]-?\\d{3,4}-?\\d{4}$");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;  // null은 @NotBlank에서 처리
        }
        return PHONE_PATTERN.matcher(value).matches();
    }
}
```

### 6.3 사용

```java
public record CustomerRequest(
    @NotBlank
    String name,

    @NotBlank
    @PhoneNumber  // 커스텀 어노테이션 사용
    String phoneNumber,

    @Email
    String email
) {}
```

### 6.4 클래스 레벨 검증

```java
// 비밀번호 확인 검증
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordMatchValidator.class)
public @interface PasswordMatch {
    String message() default "비밀번호가 일치하지 않습니다";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

public class PasswordMatchValidator
        implements ConstraintValidator<PasswordMatch, ChangePasswordRequest> {

    @Override
    public boolean isValid(ChangePasswordRequest request, ConstraintValidatorContext context) {
        if (request.getNewPassword() == null) {
            return true;
        }
        return request.getNewPassword().equals(request.getConfirmPassword());
    }
}

@PasswordMatch  // 클래스에 적용
public record ChangePasswordRequest(
    @NotBlank
    String currentPassword,

    @NotBlank
    @Size(min = 8)
    String newPassword,

    @NotBlank
    String confirmPassword
) {}
```

---

## 7. 예외 처리

### MethodArgumentNotValidException 처리

```java
@RestControllerAdvice
public class ValidationExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException e) {

        List<FieldError> fieldErrors = e.getBindingResult().getFieldErrors();

        List<ValidationError> errors = fieldErrors.stream()
            .map(error -> new ValidationError(
                error.getField(),
                error.getDefaultMessage(),
                error.getRejectedValue()
            ))
            .toList();

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(new ErrorResponse("VALIDATION_ERROR", "입력값이 올바르지 않습니다", errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException e) {

        List<ValidationError> errors = e.getConstraintViolations().stream()
            .map(violation -> new ValidationError(
                violation.getPropertyPath().toString(),
                violation.getMessage(),
                violation.getInvalidValue()
            ))
            .toList();

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(new ErrorResponse("VALIDATION_ERROR", "입력값이 올바르지 않습니다", errors));
    }
}
```

### 응답 형식

```json
{
  "code": "VALIDATION_ERROR",
  "message": "입력값이 올바르지 않습니다",
  "errors": [
    {
      "field": "quantity",
      "message": "수량은 1 이상이어야 합니다",
      "rejectedValue": -5
    },
    {
      "field": "email",
      "message": "유효한 이메일 형식이 아닙니다",
      "rejectedValue": "invalid-email"
    }
  ]
}
```

---

## 8. 메시지 국제화

### messages.properties 활용

```properties
# src/main/resources/messages.properties

# 기본 메시지
NotNull=필수 입력 항목입니다
NotBlank=필수 입력 항목입니다
Size.min={min}자 이상이어야 합니다
Size.max={max}자 이하여야 합니다

# 필드별 메시지
NotNull.orderRequest.productId=상품을 선택해주세요
Min.orderRequest.quantity=최소 {value}개 이상 주문해야 합니다
```

```java
public record OrderRequest(
    @NotNull  // messages.properties에서 메시지 로드
    Long productId,

    @Min(1)  // Min.orderRequest.quantity 메시지 사용
    Integer quantity
) {}
```

---

## 9. 우리 프로젝트 적용

### 주문 요청 DTO

```java
// common/src/main/java/com/example/common/dto/CreateOrderRequest.java
public record CreateOrderRequest(
    @NotNull(message = "상품 ID는 필수입니다")
    @Positive(message = "상품 ID는 양수여야 합니다")
    Long productId,

    @NotNull(message = "수량은 필수입니다")
    @Min(value = 1, message = "수량은 1개 이상이어야 합니다")
    @Max(value = 999, message = "수량은 999개 이하여야 합니다")
    Integer quantity,

    @NotNull(message = "고객 ID는 필수입니다")
    @Positive(message = "고객 ID는 양수여야 합니다")
    Long customerId,

    @Size(max = 500, message = "메모는 500자 이하여야 합니다")
    String memo
) {}
```

### 결제 요청 DTO

```java
public record PaymentRequest(
    @NotBlank(message = "주문 ID는 필수입니다")
    String orderId,

    @NotNull(message = "결제 금액은 필수입니다")
    @Positive(message = "결제 금액은 양수여야 합니다")
    BigDecimal amount,

    @NotNull(message = "고객 ID는 필수입니다")
    Long customerId,

    @NotBlank(message = "결제 수단은 필수입니다")
    @Pattern(regexp = "CARD|BANK|KAKAO|NAVER", message = "유효한 결제 수단이 아닙니다")
    String paymentMethod
) {}
```

---

## 10. 실습 과제

1. 주문 요청 DTO에 검증 어노테이션 추가
2. 커스텀 검증 어노테이션 만들기 (예: 주문번호 형식)
3. 검증 실패 시 일관된 에러 응답 구현
4. 검증 그룹으로 생성/수정 분리
5. 통합 테스트로 검증 동작 확인

---

## 참고 자료

- [Jakarta Bean Validation 3.0](https://jakarta.ee/specifications/bean-validation/3.0/)
- [Hibernate Validator](https://hibernate.org/validator/)
- [Spring Validation 가이드](https://docs.spring.io/spring-framework/reference/core/validation.html)

---

## 다음 단계

[07-exception-handling.md](./07-exception-handling.md) - 예외 처리로 이동
