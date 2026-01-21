# Spring Profiles - 환경별 설정

## 이 문서에서 배우는 것

- Spring Profiles가 무엇이고 왜 필요한지 이해
- Profile별 설정 파일 작성 방법
- Profile 활성화 방법
- 실무에서의 활용 패턴

---

## 1. Spring Profiles란?

### 환경별로 다른 설정이 필요한 상황

개발, 테스트, 운영 환경은 각각 다른 설정이 필요합니다:

```
[개발 환경 - local]
- DB: localhost:3306
- 로그 레벨: DEBUG
- 외부 API: Mock 서버

[스테이징 환경 - staging]
- DB: staging-db.company.com:3306
- 로그 레벨: INFO
- 외부 API: 테스트 서버

[운영 환경 - prod]
- DB: prod-db.company.com:3306
- 로그 레벨: WARN
- 외부 API: 실제 서버
```

### Profile 없이 환경을 구분하면?

```java
// ❌ 나쁜 예: 코드에 환경 분기 하드코딩
public class DataSourceConfig {
    public DataSource dataSource() {
        String env = System.getenv("ENV");
        if ("prod".equals(env)) {
            return createProdDataSource();
        } else if ("staging".equals(env)) {
            return createStagingDataSource();
        } else {
            return createLocalDataSource();
        }
    }
}
```

**문제점**:
- 코드가 복잡해짐
- 새 환경 추가 시 코드 수정 필요
- 설정이 여기저기 분산됨

### Spring Profiles의 해결책

```
Profile = 환경별 설정 묶음

┌─────────────────────────────────────────────────────────┐
│                    Spring Boot                           │
│                                                          │
│  application.yml          ← 공통 설정                    │
│  application-local.yml    ← local 환경 설정              │
│  application-staging.yml  ← staging 환경 설정            │
│  application-prod.yml     ← prod 환경 설정               │
│                                                          │
│  활성화된 Profile에 따라 해당 설정 파일 자동 로드!       │
└─────────────────────────────────────────────────────────┘
```

---

## 2. Profile 설정 파일 구조

### 2.1 파일 네이밍 규칙

```
application.yml              ← 기본 설정 (모든 환경 공통)
application-{profile}.yml    ← 특정 Profile 설정
```

```
resources/
├── application.yml           ← 공통 (항상 로드)
├── application-local.yml     ← local Profile
├── application-dev.yml       ← dev Profile
├── application-staging.yml   ← staging Profile
└── application-prod.yml      ← prod Profile
```

### 2.2 설정 적용 순서

```
1. application.yml 로드 (기본)
2. application-{active-profile}.yml 로드
3. 동일한 키가 있으면 Profile 설정이 덮어씀

예시: spring.profiles.active=prod 인 경우
application.yml의 값 + application-prod.yml의 값
(충돌 시 prod가 우선)
```

---

## 3. 설정 파일 작성 예시

### 3.1 application.yml (공통 설정)

```yaml
# application.yml - 모든 환경에서 공통으로 사용
spring:
  application:
    name: service-order

  jpa:
    hibernate:
      ddl-auto: validate  # 운영 환경 기본값: 스키마 자동 변경 금지
    open-in-view: false
    properties:
      hibernate:
        format_sql: true
        default_batch_fetch_size: 100

  flyway:
    enabled: true
    locations: classpath:db/migration

server:
  port: 8080
  shutdown: graceful

# 로깅 기본 설정
logging:
  level:
    root: INFO
    org.springframework: INFO
```

### 3.2 application-local.yml (로컬 개발)

```yaml
# application-local.yml - 로컬 개발 환경
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/order_db
    username: root
    password: password

  jpa:
    hibernate:
      ddl-auto: create-drop  # 로컬에서는 자동 생성
    show-sql: true

  flyway:
    enabled: false  # 로컬에서는 Flyway 비활성화 (선택)

# 로컬 전용 로깅
logging:
  level:
    root: DEBUG
    com.example: DEBUG
    org.hibernate.SQL: DEBUG
    org.hibernate.orm.jdbc.bind: TRACE  # 바인딩 파라미터 출력

# 개발 편의 기능
spring:
  devtools:
    restart:
      enabled: true
```

### 3.3 application-dev.yml (개발 서버)

```yaml
# application-dev.yml - 개발 서버 환경
spring:
  datasource:
    url: jdbc:mysql://dev-db.company.internal:3306/order_db
    username: ${DB_USERNAME}  # 환경 변수에서 읽기
    password: ${DB_PASSWORD}

  jpa:
    show-sql: false

logging:
  level:
    root: INFO
    com.example: DEBUG
```

### 3.4 application-prod.yml (운영)

```yaml
# application-prod.yml - 운영 환경
spring:
  datasource:
    url: jdbc:mysql://prod-db.company.internal:3306/order_db
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 10
      connection-timeout: 5000

  jpa:
    show-sql: false

logging:
  level:
    root: WARN
    com.example: INFO

# 운영 환경 추가 설정
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

---

## 4. Profile 활성화 방법

### 4.1 application.yml에서 지정

```yaml
# application.yml
spring:
  profiles:
    active: local  # 기본 활성 Profile
```

### 4.2 명령줄 인자로 지정

```bash
# JAR 실행 시
java -jar app.jar --spring.profiles.active=prod

# Gradle 실행 시
./gradlew bootRun --args='--spring.profiles.active=prod'
```

### 4.3 환경 변수로 지정

```bash
# Linux/Mac
export SPRING_PROFILES_ACTIVE=prod
java -jar app.jar

# Windows
set SPRING_PROFILES_ACTIVE=prod
java -jar app.jar
```

### 4.4 IDE에서 지정

```
[IntelliJ IDEA]
Run Configuration → Environment variables
SPRING_PROFILES_ACTIVE=local

또는
Run Configuration → Active profiles
local
```

### 우선순위

```
1. 명령줄 인자 (--spring.profiles.active=prod)
2. 환경 변수 (SPRING_PROFILES_ACTIVE=prod)
3. application.yml의 spring.profiles.active
```

---

## 5. 고급 기능

### 5.1 여러 Profile 동시 활성화

```bash
# 여러 Profile 동시 활성화 (쉼표로 구분)
java -jar app.jar --spring.profiles.active=prod,monitoring
```

```yaml
# application-monitoring.yml - 모니터링 전용 설정
management:
  endpoints:
    web:
      exposure:
        include: "*"
```

### 5.2 Profile Groups (Spring Boot 2.4+)

여러 Profile을 그룹으로 묶어서 관리:

```yaml
# application.yml
spring:
  profiles:
    group:
      prod: "prod-db,prod-monitoring,prod-security"
      dev: "dev-db,dev-monitoring"
```

```bash
# prod 활성화 → prod-db, prod-monitoring, prod-security 모두 활성화
java -jar app.jar --spring.profiles.active=prod
```

### 5.3 Profile별 Bean 등록

```java
// local 환경에서만 사용되는 Bean
@Configuration
@Profile("local")
public class LocalConfig {

    @Bean
    public EmailService emailService() {
        return new MockEmailService();  // 로컬에서는 Mock 사용
    }
}

// prod 환경에서만 사용되는 Bean
@Configuration
@Profile("prod")
public class ProdConfig {

    @Bean
    public EmailService emailService() {
        return new SmtpEmailService();  // 운영에서는 실제 SMTP 사용
    }
}
```

### 5.4 Profile 조건 표현식

```java
// 여러 Profile 중 하나라도 활성화되면
@Profile({"dev", "staging"})

// NOT 표현식
@Profile("!prod")  // prod가 아닌 모든 환경

// 복잡한 조건은 @Conditional 사용
@Conditional(CustomCondition.class)
```

---

## 6. 환경 변수 활용

### 6.1 설정에서 환경 변수 참조

```yaml
# application-prod.yml
spring:
  datasource:
    url: jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME:order_db}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
```

**문법**:
- `${ENV_VAR}`: 환경 변수 값 사용
- `${ENV_VAR:default}`: 환경 변수 없으면 기본값 사용

### 6.2 민감 정보 관리

```yaml
# ❌ 나쁜 예: 비밀번호 하드코딩
spring:
  datasource:
    password: mySecretPassword123

# ✓ 좋은 예: 환경 변수 사용
spring:
  datasource:
    password: ${DB_PASSWORD}
```

**운영 환경 설정**:
```bash
# 쿠버네티스 Secret
kubectl create secret generic db-credentials \
  --from-literal=DB_PASSWORD=mySecretPassword123

# Docker
docker run -e DB_PASSWORD=mySecretPassword123 myapp
```

---

## 7. 실무 패턴

### 7.1 로컬/개발/운영 3단계 구조

```
application.yml          ← 공통 (변경 적음)
application-local.yml    ← 로컬 개발 (각자 다를 수 있음)
application-dev.yml      ← 개발 서버 (팀 공유)
application-prod.yml     ← 운영 서버 (민감 정보는 환경 변수)
```

### 7.2 테스트 Profile

```yaml
# application-test.yml
spring:
  datasource:
    url: jdbc:h2:mem:testdb  # 인메모리 DB 사용
    driver-class-name: org.h2.Driver

  jpa:
    hibernate:
      ddl-auto: create-drop

  flyway:
    enabled: false  # 테스트에서는 Flyway 비활성화
```

```java
// 테스트 클래스에서 Profile 지정
@SpringBootTest
@ActiveProfiles("test")
class OrderServiceTest {
    // ...
}
```

### 7.3 설정 분리 전략

```
[방법 1] 환경별 파일 분리
application-local.yml
application-dev.yml
application-prod.yml

[방법 2] 기능별 파일 분리
application-db.yml       ← DB 설정
application-cache.yml    ← 캐시 설정
application-security.yml ← 보안 설정

→ Profile Groups로 조합
spring:
  profiles:
    group:
      prod: "db,cache,security,prod-specific"
```

---

## 8. 우리 프로젝트 적용

### 서비스별 설정 구조

```
service-order/
└── src/main/resources/
    ├── application.yml           ← 공통 설정
    ├── application-local.yml     ← 로컬
    ├── application-dev.yml       ← 개발 서버
    ├── application-prod.yml      ← 운영
    └── application-test.yml      ← 테스트
```

### 예시: service-order 설정

**application.yml**
```yaml
spring:
  application:
    name: service-order

  jpa:
    open-in-view: false
    properties:
      hibernate:
        default_batch_fetch_size: 100

server:
  port: 8081

# 기본은 local Profile
spring:
  profiles:
    active: local
```

**application-local.yml**
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/order_db
    username: root
    password: password

  jpa:
    hibernate:
      ddl-auto: create-drop
    show-sql: true

logging:
  level:
    com.example.order: DEBUG
```

**application-test.yml**
```yaml
spring:
  datasource:
    url: jdbc:tc:mysql:8.0:///order_db  # Testcontainers
    driver-class-name: org.testcontainers.jdbc.ContainerDatabaseDriver

  jpa:
    hibernate:
      ddl-auto: create-drop
```

---

## 9. 디버깅 및 확인

### 9.1 활성화된 Profile 확인

```java
@Component
public class ProfileChecker implements CommandLineRunner {

    @Autowired
    private Environment environment;

    @Override
    public void run(String... args) {
        String[] activeProfiles = environment.getActiveProfiles();
        System.out.println("Active Profiles: " + Arrays.toString(activeProfiles));
    }
}
```

### 9.2 로그로 확인

애플리케이션 시작 시 로그에 표시됨:
```
The following profiles are active: local
```

### 9.3 Actuator로 확인

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: env,info
```

```bash
curl http://localhost:8080/actuator/env
```

---

## 10. 주의사항

### 10.1 .gitignore 설정

```gitignore
# 로컬 설정은 커밋하지 않음 (개인별로 다를 수 있음)
application-local.yml

# 또는 별도 파일로 관리
application-local-*.yml
```

### 10.2 운영 설정 보안

```yaml
# ❌ 운영 비밀번호를 Git에 커밋하지 마세요!
# application-prod.yml
spring:
  datasource:
    password: ${DB_PASSWORD}  # 환경 변수로만!
```

### 10.3 Profile 기본값 설정

```yaml
# application.yml
spring:
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:local}  # 환경 변수 없으면 local
```

---

## 11. 실습 과제

1. `application.yml`에 공통 설정 작성
2. `application-local.yml`에 로컬 DB 설정 작성
3. `application-test.yml`에 H2 DB 설정 작성
4. IntelliJ에서 Profile 변경하며 실행해보기
5. 로그에서 "Active Profiles" 확인하기

---

## 참고 자료

- [Spring Boot 공식 문서 - Profiles](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.profiles)
- [Spring Boot 공식 문서 - External Configuration](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.external-config)
- [Baeldung - Spring Profiles](https://www.baeldung.com/spring-profiles)

---

## 다음 단계

[04-docker-compose.md](./04-docker-compose.md) - Docker Compose로 이동
