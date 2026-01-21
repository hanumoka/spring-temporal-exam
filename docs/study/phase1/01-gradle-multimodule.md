# Gradle 멀티모듈 프로젝트

## 이 문서에서 배우는 것

- 멀티모듈 프로젝트가 무엇인지 이해
- 왜 멀티모듈 구조가 필요한지 파악
- Gradle을 사용한 멀티모듈 프로젝트 구성 방법
- 모듈 간 의존성 관리 방법
- 멀티모듈 주요 이슈와 해결법 (순환참조, 빈 스캔, Entity Scan 등)

---

## 1. 멀티모듈 프로젝트란?

### 단일 모듈 vs 멀티모듈

**단일 모듈 프로젝트**는 하나의 `build.gradle` 파일로 전체 프로젝트를 관리합니다:

```
my-project/
├── build.gradle
├── settings.gradle
└── src/
    └── main/
        └── java/
            └── com/example/
                ├── order/
                ├── payment/
                ├── inventory/
                └── notification/
```

**멀티모듈 프로젝트**는 기능별로 독립된 모듈을 가집니다:

```
my-project/
├── build.gradle              ← 루트 빌드 파일
├── settings.gradle           ← 모듈 등록
├── common/                   ← 공통 모듈
│   ├── build.gradle
│   └── src/
├── service-order/            ← 주문 모듈
│   ├── build.gradle
│   └── src/
├── service-payment/          ← 결제 모듈
│   ├── build.gradle
│   └── src/
└── service-inventory/        ← 재고 모듈
    ├── build.gradle
    └── src/
```

---

## 2. 왜 멀티모듈이 필요한가?

### 2.1 관심사의 분리 (Separation of Concerns)

```
[단일 모듈의 문제]
모든 코드가 한 곳에 있으면:
- 주문 코드가 결제 코드를 직접 참조
- 결제 코드가 재고 코드를 직접 참조
- 스파게티 코드 발생!

[멀티모듈의 장점]
모듈별로 분리하면:
- 각 모듈의 책임이 명확
- 의존성 방향이 강제됨
- 코드 수정 영향 범위가 명확
```

### 2.2 빌드/테스트 효율성

```bash
# 단일 모듈: 전체 빌드
./gradlew build  ← 모든 코드 빌드 (느림)

# 멀티모듈: 특정 모듈만 빌드
./gradlew :service-order:build  ← 주문 모듈만 빌드 (빠름)
```

### 2.3 팀 협업 용이성

```
Team A → service-order 모듈 담당
Team B → service-payment 모듈 담당
Team C → service-inventory 모듈 담당

→ 충돌 최소화, 독립적 개발 가능
```

### 2.4 마이크로서비스 전환 용이성

```
멀티모듈 구조

┌──────────────────────────────────────────┐
│              하나의 프로젝트              │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐    │
│  │  Order  │ │ Payment │ │Inventory│    │
│  └─────────┘ └─────────┘ └─────────┘    │
└──────────────────────────────────────────┘
                    │
                    ▼ 나중에 분리 가능
┌─────────┐   ┌─────────┐   ┌─────────┐
│  Order  │   │ Payment │   │Inventory│
│ Service │   │ Service │   │ Service │
└─────────┘   └─────────┘   └─────────┘
   독립 서버       독립 서버       독립 서버
```

---

## 3. 프로젝트 구조 설계

### 우리 프로젝트의 모듈 구조

```
spring-temporal-exam/
├── build.gradle                    ← 루트 빌드 설정
├── settings.gradle                 ← 모듈 등록
├── gradle/
│   └── libs.versions.toml          ← 버전 카탈로그 (권장)
│
├── common/                         ← 공통 모듈
│   ├── build.gradle
│   └── src/main/java/
│       └── com/example/common/
│           ├── dto/                ← 공유 DTO
│           ├── event/              ← 공유 이벤트
│           └── exception/          ← 공유 예외
│
├── service-order/                  ← 주문 서비스
│   ├── build.gradle
│   └── src/main/java/
│       └── com/example/order/
│           ├── domain/
│           ├── repository/
│           ├── service/
│           └── controller/
│
├── service-inventory/              ← 재고 서비스
├── service-payment/                ← 결제 서비스
├── service-notification/           ← 알림 서비스
│
├── orchestrator-pure/              ← 순수 오케스트레이터
└── orchestrator-temporal/          ← Temporal 오케스트레이터
```

### 모듈 간 의존성 관계

```
                    ┌──────────────────┐
                    │      common      │
                    │   (공통 모듈)     │
                    └────────┬─────────┘
                             │ (의존)
         ┌───────────────────┼───────────────────┐
         │                   │                   │
         ▼                   ▼                   ▼
┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
│  service-order  │ │service-inventory│ │ service-payment │
└────────┬────────┘ └────────┬────────┘ └────────┬────────┘
         │                   │                   │
         └───────────────────┼───────────────────┘
                             │ (의존)
                             ▼
                ┌────────────────────────┐
                │   orchestrator-pure    │
                │ (오케스트레이터)         │
                └────────────────────────┘
```

**규칙**:
- `common`은 다른 모듈에 의존하지 않음
- 서비스 모듈들은 `common`에만 의존
- `orchestrator`는 서비스 모듈들에 의존
- **순환 의존 금지!** (A→B→A)

---

## 4. Gradle 설정 파일 작성

### 4.1 settings.gradle (모듈 등록)

```groovy
// settings.gradle
rootProject.name = 'spring-temporal-exam'

// 모든 하위 모듈 등록
include 'common'
include 'service-order'
include 'service-inventory'
include 'service-payment'
include 'service-notification'
include 'orchestrator-pure'
include 'orchestrator-temporal'
```

**설명**:
- `rootProject.name`: 전체 프로젝트 이름
- `include`: 포함할 모듈 이름 (폴더명과 동일해야 함)

---

### 4.2 gradle/libs.versions.toml (버전 카탈로그)

버전 카탈로그는 **Gradle 7.0+**에서 지원하는 기능으로, 모든 의존성 버전을 한 곳에서 관리합니다.

```toml
# gradle/libs.versions.toml

[versions]
spring-boot = "4.0.1"
java = "21"
lombok = "1.18.30"

[libraries]
# Spring Boot Starters
spring-boot-starter-web = { module = "org.springframework.boot:spring-boot-starter-webmvc" }
spring-boot-starter-data-jpa = { module = "org.springframework.boot:spring-boot-starter-data-jpa" }
spring-boot-starter-validation = { module = "org.springframework.boot:spring-boot-starter-validation" }
spring-boot-starter-test = { module = "org.springframework.boot:spring-boot-starter-test" }

# Database
mysql-connector = { module = "com.mysql:mysql-connector-j" }
flyway-core = { module = "org.flywaydb:flyway-core" }
flyway-mysql = { module = "org.flywaydb:flyway-mysql" }

# Lombok
lombok = { module = "org.projectlombok:lombok", version.ref = "lombok" }

# Redis
spring-boot-starter-data-redis = { module = "org.springframework.boot:spring-boot-starter-data-redis" }
redisson = { module = "org.redisson:redisson-spring-boot-starter", version = "4.0.0" }  # Spring Boot 4 호환

# Resilience4j
resilience4j-spring-boot = { module = "io.github.resilience4j:resilience4j-spring-boot3", version = "2.2.0" }

# Test
testcontainers-junit = { module = "org.testcontainers:junit-jupiter" }
testcontainers-mysql = { module = "org.testcontainers:mysql" }

[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
spring-dependency-management = { id = "io.spring.dependency-management", version = "1.1.4" }
```

**장점**:
- 모든 버전을 한 곳에서 관리
- IDE 자동완성 지원
- 타입 안전한 의존성 선언

---

### 4.3 루트 build.gradle

```groovy
// build.gradle (루트)
plugins {
    id 'java'
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

// 모든 프로젝트(루트 + 하위)에 적용
allprojects {
    group = 'com.example'
    version = '0.0.1-SNAPSHOT'

    repositories {
        mavenCentral()
    }
}

// 하위 프로젝트에만 적용
subprojects {
    apply plugin: 'java'

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    // 공통 의존성
    dependencies {
        compileOnly libs.lombok
        annotationProcessor libs.lombok

        testImplementation libs.spring.boot.starter.test
    }

    tasks.named('test') {
        useJUnitPlatform()
    }
}
```

**핵심 개념**:

| 블록 | 적용 범위 | 용도 |
|------|----------|------|
| `allprojects` | 루트 + 모든 하위 모듈 | group, version, repositories |
| `subprojects` | 하위 모듈만 | 공통 플러그인, 의존성 |

---

### 4.4 common 모듈 build.gradle

```groovy
// common/build.gradle
plugins {
    id 'java-library'  // 다른 모듈에서 사용할 라이브러리
}

dependencies {
    // 다른 모듈에 전이되는 의존성 (api)
    api libs.spring.boot.starter.validation

    // 이 모듈에서만 사용하는 의존성 (implementation)
    implementation libs.spring.boot.starter.web
}
```

**`api` vs `implementation`의 차이**:

```
common 모듈에서 validation을 api로 선언하면:
→ service-order가 common을 의존할 때 validation도 자동으로 포함

common 모듈에서 web을 implementation으로 선언하면:
→ service-order가 common을 의존해도 web은 포함되지 않음
```

```groovy
// 시각적으로 표현
common {
    api 'validation'        // → service-order에서도 사용 가능
    implementation 'web'    // → common 내부에서만 사용
}

service-order {
    implementation project(':common')
    // validation 사용 가능 ✓
    // web 사용 불가 ✗
}
```

---

### 4.5 서비스 모듈 build.gradle

```groovy
// service-order/build.gradle
plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    // 다른 모듈 의존
    implementation project(':common')

    // Spring Boot
    implementation libs.spring.boot.starter.web
    implementation libs.spring.boot.starter.data.jpa
    implementation libs.spring.boot.starter.validation

    // Database
    runtimeOnly libs.mysql.connector
    implementation libs.flyway.core
    implementation libs.flyway.mysql

    // Test
    testImplementation libs.testcontainers.junit
    testImplementation libs.testcontainers.mysql
}
```

**`project(':common')`**:
- 같은 프로젝트 내 다른 모듈을 의존
- 콜론(`:`)은 루트를 의미 (`:common` = 루트의 common 모듈)

---

### 4.6 오케스트레이터 모듈 build.gradle

```groovy
// orchestrator-pure/build.gradle
plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    // 공통 모듈
    implementation project(':common')

    // 서비스 모듈들 (REST 클라이언트로 호출)
    // 실제로는 HTTP 클라이언트를 사용하므로 직접 의존은 선택적

    // Spring Boot
    implementation libs.spring.boot.starter.web

    // Resilience4j
    implementation libs.resilience4j.spring.boot
}
```

---

## 5. 빌드 및 실행

### 5.1 전체 빌드

```bash
# 루트 디렉토리에서 실행
./gradlew build
```

### 5.2 특정 모듈만 빌드

```bash
# 주문 서비스만 빌드
./gradlew :service-order:build

# common 모듈만 빌드
./gradlew :common:build
```

### 5.3 특정 모듈 실행

```bash
# 주문 서비스 실행
./gradlew :service-order:bootRun
```

### 5.4 의존성 확인

```bash
# service-order의 의존성 트리 확인
./gradlew :service-order:dependencies
```

---

## 6. 모듈 간 코드 공유

### 6.1 공통 DTO 정의 (common 모듈)

```java
// common/src/main/java/com/example/common/dto/OrderDto.java
package com.example.common.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record OrderDto(
    Long id,

    @NotNull(message = "상품 ID는 필수입니다")
    Long productId,

    @Positive(message = "수량은 1 이상이어야 합니다")
    Integer quantity,

    @NotNull(message = "고객 ID는 필수입니다")
    Long customerId
) {}
```

### 6.2 서비스 모듈에서 사용

```java
// service-order/src/main/java/com/example/order/controller/OrderController.java
package com.example.order.controller;

import com.example.common.dto.OrderDto;  // common 모듈에서 import
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
public class OrderController {

    @PostMapping
    public OrderDto createOrder(@Valid @RequestBody OrderDto orderDto) {
        // 주문 생성 로직
        return orderDto;
    }
}
```

---

## 7. 멀티모듈 주요 이슈와 해결법

### 7.1 순환 참조 (Circular Dependency)

**문제 상황**

```
common ──depends──▶ domain
   ▲                   │
   └───────────────────┘
       순환 발생!
```

```groovy
// common/build.gradle
dependencies {
    implementation project(':domain')  // ❌ 순환!
}

// domain/build.gradle
dependencies {
    implementation project(':common')  // common → domain → common
}
```

**해결 방법 1: 모듈 계층 명확히 설계**

```
common (최하위, 의존성 없음)
   ↑
domain (common만 의존)
   ↑
application (domain 의존)
   ↑
api / worker (application 의존)
```

**해결 방법 2: 공통 인터페이스 추출**

```java
// common 모듈에 인터페이스 정의
public interface PaymentClient {
    PaymentResult process(PaymentRequest request);
}

// service-payment에서 구현
@Component
public class PaymentService implements PaymentClient { }

// service-order에서 사용 (인터페이스에만 의존)
@Service
public class OrderService {
    private final PaymentClient paymentClient;
}
```

---

### 7.2 Component Scan 문제

**문제 상황: 다른 모듈의 Bean을 못 찾음**

```
프로젝트 구조:
├── api/
│   └── com.example.api.ApiApplication.java  ← @SpringBootApplication
└── domain/
    └── com.example.domain.service.OrderService.java  ← @Service
```

```java
// ApiApplication.java
@SpringBootApplication  // 기본: com.example.api 패키지만 스캔
public class ApiApplication { }

// 결과: OrderService 빈을 찾을 수 없음!
// Error: No qualifying bean of type 'OrderService'
```

**해결 방법 1: 패키지 통일 (권장)**

```
모든 모듈의 기본 패키지를 동일하게:

├── api/      com.example.xxx
├── domain/   com.example.xxx
└── common/   com.example.xxx

@SpringBootApplication이 com.example 하위를 모두 스캔
```

**해결 방법 2: scanBasePackages 명시**

```java
@SpringBootApplication(
    scanBasePackages = {
        "com.example.api",
        "com.example.domain",
        "com.example.common"
    }
)
public class ApiApplication { }
```

**해결 방법 3: @ComponentScan 추가**

```java
@SpringBootApplication
@ComponentScan(basePackages = "com.example")
public class ApiApplication { }
```

---

### 7.3 Entity Scan 문제 (JPA)

**문제 상황: JPA 엔티티를 못 찾음**

```java
// domain 모듈: com.example.domain.entity.Order
@Entity
public class Order { }

// api 모듈에서 실행 시
// Error: "Not a managed type: class com.example.domain.entity.Order"
```

**해결 방법**

```java
@SpringBootApplication
@EntityScan(basePackages = "com.example.domain.entity")
@EnableJpaRepositories(basePackages = "com.example.domain.repository")
public class ApiApplication { }
```

**또는 통합 설정 클래스 생성**

```java
// domain 모듈에 설정 클래스 추가
@Configuration
@EntityScan(basePackages = "com.example.domain.entity")
@EnableJpaRepositories(basePackages = "com.example.domain.repository")
public class DomainJpaConfig { }

// api 모듈에서 import
@SpringBootApplication
@Import(DomainJpaConfig.class)
public class ApiApplication { }
```

---

### 7.4 설정 파일 충돌

**문제 상황: 여러 모듈에 application.yml이 존재**

```
├── common/src/main/resources/application.yml
├── domain/src/main/resources/application.yml
└── api/src/main/resources/application.yml

어떤 설정이 적용될까? → 예측 어려움, 덮어쓰기 발생
```

**해결 방법 1: 실행 모듈에만 application.yml 배치**

```
├── common/src/main/resources/  (설정 파일 없음)
├── domain/src/main/resources/  (설정 파일 없음)
└── api/src/main/resources/application.yml  (유일한 설정)
```

**해결 방법 2: 모듈별 별도 파일명 사용**

```
├── common/  → common-config.yml
├── domain/  → domain-config.yml
└── api/     → application.yml (메인)
```

```yaml
# api/src/main/resources/application.yml
spring:
  config:
    import:
      - classpath:common-config.yml
      - classpath:domain-config.yml
```

---

### 7.5 테스트 시 빈 주입 실패

**문제 상황: @SpringBootApplication 클래스가 없는 모듈에서 테스트**

```java
// domain 모듈 테스트
@SpringBootTest  // ❌ @SpringBootApplication 클래스가 없음!
class OrderServiceTest { }

// Error: Unable to find a @SpringBootConfiguration
```

**해결 방법 1: 테스트용 설정 클래스 생성**

```java
// domain/src/test/java/com/example/domain/DomainTestConfig.java
@TestConfiguration
@ComponentScan("com.example.domain")
@EnableJpaRepositories("com.example.domain.repository")
@EntityScan("com.example.domain.entity")
public class DomainTestConfig { }

// 테스트에서 사용
@SpringBootTest(classes = DomainTestConfig.class)
class OrderServiceTest { }
```

**해결 방법 2: 슬라이스 테스트 활용**

```java
// JPA 관련만 로드
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class OrderRepositoryTest { }

// 서비스 레이어만 테스트
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {
    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderService orderService;
}
```

---

### 7.6 의존성 전이 문제 (api vs implementation)

**문제 상황: 다른 모듈에서 의존성 사용 불가**

```groovy
// domain/build.gradle
dependencies {
    implementation(libs.jackson)  // implementation = 전이 안됨
}

// api/build.gradle
dependencies {
    implementation project(':domain')
}

// api 모듈에서 Jackson 사용 시 컴파일 에러!
```

**해결 방법: 노출해야 하는 의존성은 api 사용**

```groovy
// domain/build.gradle
dependencies {
    api(libs.jackson)  // api = 전이됨
}
```

**가이드라인**

```
api 사용해야 하는 경우:
├── 모듈의 public API(메서드 시그니처)에서 사용하는 타입
├── DTO, 인터페이스에 노출되는 라이브러리
└── 예: 공통 DTO에서 사용하는 Jackson 어노테이션

implementation 사용해야 하는 경우:
├── 내부 구현에서만 사용
├── 외부에 노출할 필요 없는 라이브러리
└── 예: 내부 유틸리티 라이브러리
```

```
시각적으로 표현:

common {
    api 'validation'        // → 의존하는 모듈에서도 사용 가능
    implementation 'guava'  // → common 내부에서만 사용
}

service-order {
    implementation project(':common')
    // validation 사용 가능 ✓ (api로 선언됨)
    // guava 사용 불가 ✗ (implementation으로 선언됨)
}
```

---

### 7.7 리소스 파일 접근 문제

**문제 상황: 다른 모듈의 리소스 파일 접근 실패**

```
common/src/main/resources/templates/email.html

// api 모듈에서 접근 시
getClass().getResource("/templates/email.html")  // null 반환 가능
```

**해결 방법 1: 리소스 JAR 포함 확인**

```groovy
// common/build.gradle
sourceSets {
    main {
        resources {
            srcDirs("src/main/resources")
        }
    }
}
```

**해결 방법 2: ClassPathResource 사용**

```java
// Spring의 ClassPathResource 사용 (권장)
Resource resource = new ClassPathResource("templates/email.html");
InputStream inputStream = resource.getInputStream();
```

---

### 7.8 테스트 코드/유틸리티 공유

**문제 상황: common 모듈의 테스트 유틸리티를 다른 모듈에서 사용**

```groovy
// common/build.gradle
// 테스트 유틸리티를 다른 모듈에서 사용하려면:
configurations {
    testArtifacts
}

task testJar(type: Jar) {
    archiveClassifier = 'tests'
    from sourceSets.test.output
}

artifacts {
    testArtifacts testJar
}

// service-order/build.gradle
dependencies {
    testImplementation project(path: ':common', configuration: 'testArtifacts')
}
```

---

### 7.9 멀티모듈 체크리스트

```
[ ] 모듈 계층 설계 (순환 참조 방지)
[ ] 패키지 구조 통일 또는 scanBasePackages 설정
[ ] @EntityScan, @EnableJpaRepositories 설정
[ ] 설정 파일은 실행 모듈에만 배치
[ ] api vs implementation 적절히 사용
[ ] 테스트 설정 클래스 준비
[ ] 리소스 파일 접근 방식 통일
```

---

## 8. 베스트 프랙티스

### 8.1 모듈 명명 규칙

```
✓ 좋은 예:
  - common
  - service-order
  - service-payment
  - orchestrator-pure

✗ 나쁜 예:
  - order (너무 짧음)
  - OrderServiceModule (너무 김)
  - svc_order (일관성 없음)
```

### 8.2 의존성 방향 강제

```groovy
// ArchUnit을 사용한 의존성 방향 검증
// build.gradle
testImplementation 'com.tngtech.archunit:archunit-junit5:1.2.1'
```

```java
// 테스트 코드로 의존성 방향 검증
@ArchTest
static final ArchRule commonShouldNotDependOnServices =
    noClasses()
        .that().resideInAPackage("..common..")
        .should().dependOnClassesThat()
        .resideInAPackage("..service..");
```

### 8.3 버전 카탈로그 활용

```toml
# 버전 한 곳에서 관리
[versions]
spring-boot = "4.0.1"

# 버전 변경 시 한 곳만 수정하면 됨
```

---

## 9. 실습 과제

1. 루트에 `settings.gradle` 생성하고 3개 모듈 등록하기
2. `libs.versions.toml` 파일 생성하고 Spring Boot 의존성 정의하기
3. `common` 모듈에 공통 DTO 클래스 만들기
4. `service-order` 모듈에서 `common` 의존하고 DTO 사용하기
5. `./gradlew build`로 전체 빌드 성공시키기

---

## 참고 자료

- [Spring 공식 가이드 - Multi Module Project](https://spring.io/guides/gs/multi-module/)
- [Gradle 멀티모듈 베스트 프랙티스](https://bootify.io/multi-module/best-practices-for-spring-boot-multi-module.html)
- [Reflectoring - Multi-Module with Gradle](https://reflectoring.io/spring-boot-gradle-multi-module/)
- [Java Code Geeks - Multimodule Best Practices](https://www.javacodegeeks.com/2025/06/multimodule-spring-boot-projects-with-maven-gradle-best-practices.html)

---

## 다음 단계

[02-flyway.md](./02-flyway.md) - DB 마이그레이션으로 이동
