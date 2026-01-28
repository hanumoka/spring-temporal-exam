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

### 4.0 루트 프로젝트와 하위 모듈 이해하기

#### 폴더 구조로 이해하기

```
spring-temporal-exam/              ← 루트 프로젝트 (폴더)
├── build.gradle                   ← 루트의 빌드 설정
├── settings.gradle                ← 모듈 등록 (여기서 하위 모듈 지정)
├── gradle/
│   └── libs.versions.toml         ← 버전 카탈로그
│
├── common/                        ← 하위 모듈 1 (폴더)
│   ├── build.gradle               ← common 모듈의 빌드 설정
│   └── src/
│
├── service-order/                 ← 하위 모듈 2 (폴더)
│   ├── build.gradle               ← service-order 모듈의 빌드 설정
│   └── src/
│
└── service-payment/               ← 하위 모듈 3 (폴더)
    ├── build.gradle               ← service-payment 모듈의 빌드 설정
    └── src/
```

**핵심 포인트:**
- **루트 프로젝트** = 전체를 감싸는 최상위 폴더
- **하위 모듈** = 루트 안에 있는 각각의 폴더 (독립적인 프로젝트처럼 동작)
- 각 모듈은 **자신만의 build.gradle**을 가짐

#### 루트는 "컨테이너", 하위 모듈은 "실제 코드"

```
┌─────────────────────────────────────────────────────────────────┐
│  루트 프로젝트 (spring-temporal-exam)                            │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  역할:                                                       ││
│  │  • 하위 모듈들을 묶어주는 컨테이너                            ││
│  │  • 공통 설정 정의 (Java 버전, 저장소 등)                      ││
│  │  • 직접 실행되지 않음 (코드 없음)                             ││
│  └─────────────────────────────────────────────────────────────┘│
│                                                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐           │
│  │   common     │  │service-order │  │service-payment│          │
│  │   (모듈)     │  │   (모듈)     │  │    (모듈)    │           │
│  │              │  │              │  │              │           │
│  │ • DTO 클래스 │  │ • 주문 API   │  │ • 결제 API   │           │
│  │ • 공통 예외  │  │ • 주문 서비스│  │ • 결제 서비스│           │
│  │ • 라이브러리 │  │ • 실행 가능  │  │ • 실행 가능  │           │
│  └──────────────┘  └──────────────┘  └──────────────┘           │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### build.gradle이 여러 개인 이유

```
질문: 왜 build.gradle이 여러 개 있나요?

답변:
┌─────────────────────────────────────────────────────────────────┐
│                                                                  │
│  루트/build.gradle        →  "모든 모듈의 공통 설정"             │
│  ├── Java 21 사용하겠다                                         │
│  ├── 모든 모듈에 Lombok 추가하겠다                              │
│  └── Maven Central에서 라이브러리 다운로드하겠다                │
│                                                                  │
│  common/build.gradle      →  "common 모듈만의 설정"              │
│  └── validation 라이브러리 추가                                 │
│                                                                  │
│  service-order/build.gradle → "service-order 모듈만의 설정"     │
│  ├── Spring Boot 웹 기능 추가                                   │
│  ├── JPA 추가                                                   │
│  └── MySQL 드라이버 추가                                        │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

결과:
• service-order는 "루트 공통 설정" + "자신만의 설정" 모두 적용됨
• 중복 코드 없이 효율적으로 관리 가능
```

#### 설정이 적용되는 흐름

```
┌─────────────────────────────────────────────────────────────────┐
│                    설정 적용 흐름                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  루트 build.gradle                                               │
│  │                                                               │
│  ├── allprojects { ... }                                        │
│  │   │                                                          │
│  │   ├──→ 루트 프로젝트에 적용 ✓                                │
│  │   ├──→ common에 적용 ✓                                       │
│  │   ├──→ service-order에 적용 ✓                                │
│  │   └──→ service-payment에 적용 ✓                              │
│  │                                                               │
│  └── subprojects { ... }                                        │
│      │                                                          │
│      ├──→ 루트 프로젝트에 적용 ✗ (제외!)                        │
│      ├──→ common에 적용 ✓                                       │
│      ├──→ service-order에 적용 ✓                                │
│      └──→ service-payment에 적용 ✓                              │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**왜 이렇게 구분할까?**

```
allprojects에 넣을 것:
├── group = 'com.hanumoka'     → 모든 프로젝트의 그룹 ID
├── version = '0.0.1-SNAPSHOT' → 모든 프로젝트의 버전
└── repositories { }           → 라이브러리 다운로드 위치

subprojects에 넣을 것:
├── apply plugin: 'java'       → 루트는 Java 코드가 없으므로 제외
├── dependencies { lombok }    → 루트는 의존성이 필요 없으므로 제외
└── java { toolchain { } }     → 루트는 컴파일할 코드가 없으므로 제외
```

#### 실제 빌드 시 동작

```bash
# 전체 빌드 (루트에서 실행)
./gradlew build

# 내부적으로 일어나는 일:
# 1. 루트 build.gradle 읽음
# 2. settings.gradle에서 하위 모듈 목록 확인
# 3. 각 모듈별로:
#    - 루트의 allprojects 설정 적용
#    - 루트의 subprojects 설정 적용
#    - 해당 모듈의 build.gradle 설정 적용
#    - 컴파일 및 빌드 실행
```

```bash
# 특정 모듈만 빌드
./gradlew :service-order:build
#          ↑ 콜론(:)은 루트를 의미
#            :service-order = 루트의 service-order 모듈

# 결과:
# • common 모듈 먼저 빌드 (service-order가 의존하므로)
# • service-order 모듈 빌드
```

#### 요약 테이블

| 구분 | 루트 프로젝트 | 하위 모듈 |
|------|--------------|----------|
| **역할** | 컨테이너, 공통 설정 | 실제 코드, 실행 가능 |
| **build.gradle** | 공통 설정 정의 | 모듈별 설정 정의 |
| **src 폴더** | 보통 없음 | 있음 (Java 코드) |
| **실행 가능** | ✗ | ✓ (Spring Boot 앱) |
| **allprojects 적용** | ✓ | ✓ |
| **subprojects 적용** | ✗ | ✓ |

---

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

#### 왜 필요한가?

**기존 방식의 문제점:**
```groovy
// module-A/build.gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web:3.4.0'
}

// module-B/build.gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web:3.3.0'  // 버전 불일치!
}
```

- 버전이 여러 파일에 흩어져 있음
- 버전 불일치 발생 가능
- 업그레이드 시 여러 파일 수정 필요

#### 기본 구조

`gradle/libs.versions.toml` 파일은 4개 섹션으로 구성됩니다:

```toml
[versions]      # 버전 정의
[libraries]     # 라이브러리 정의
[bundles]       # 라이브러리 묶음 (선택)
[plugins]       # 플러그인 정의
```

#### [versions] 섹션

버전 번호만 정의합니다:

```toml
[versions]
spring-boot = "3.4.0"
redisson = "3.52.0"
resilience4j = "2.2.0"
flyway = "10.8.1"
testcontainers = "1.19.3"
```

#### [libraries] 섹션

라이브러리를 정의합니다. 세 가지 방식이 있습니다:

**방식 A: 버전 참조 (version.ref)** - 권장
```toml
redisson = { module = "org.redisson:redisson-spring-boot-starter", version.ref = "redisson" }
#                       ↑ group:artifact                            ↑ [versions]의 키 참조
```

**방식 B: 버전 직접 지정**
```toml
redisson = { module = "org.redisson:redisson-spring-boot-starter", version = "3.52.0" }
```

**방식 C: 버전 생략 (BOM 관리 의존성)**
```toml
# Spring Boot BOM이 관리하는 의존성은 버전 생략 가능
spring-boot-starter-web = { module = "org.springframework.boot:spring-boot-starter-web" }
mysql-connector = { module = "com.mysql:mysql-connector-j" }
```

#### [bundles] 섹션 (선택)

자주 함께 쓰는 라이브러리를 묶음으로 정의:

```toml
[bundles]
spring-web = ["spring-boot-starter-web", "spring-boot-starter-validation"]
database = ["spring-boot-starter-data-jpa", "mysql-connector", "flyway-core", "flyway-mysql"]
test = ["spring-boot-starter-test", "testcontainers-junit", "testcontainers-mysql"]
```

**사용:**
```groovy
dependencies {
    implementation libs.bundles.spring.web    // 2개 라이브러리 한번에
    implementation libs.bundles.database      // 4개 라이브러리 한번에
    testImplementation libs.bundles.test
}
```

#### [plugins] 섹션

Gradle 플러그인 정의:

```toml
[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
spring-dependency-management = { id = "io.spring.dependency-management", version = "1.1.6" }
```

**사용:**
```groovy
plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}
```

#### 전체 예시

```toml
# gradle/libs.versions.toml

[versions]
spring-boot = "3.4.0"
redisson = "3.52.0"
resilience4j = "2.2.0"
flyway = "10.8.1"
testcontainers = "1.19.3"

[libraries]
# Spring Boot (BOM 관리 - 버전 생략)
spring-boot-starter-web = { module = "org.springframework.boot:spring-boot-starter-web" }
spring-boot-starter-data-jpa = { module = "org.springframework.boot:spring-boot-starter-data-jpa" }
spring-boot-starter-validation = { module = "org.springframework.boot:spring-boot-starter-validation" }
spring-boot-starter-data-redis = { module = "org.springframework.boot:spring-boot-starter-data-redis" }
spring-boot-starter-test = { module = "org.springframework.boot:spring-boot-starter-test" }

# Database
mysql-connector = { module = "com.mysql:mysql-connector-j" }
flyway-core = { module = "org.flywaydb:flyway-core", version.ref = "flyway" }
flyway-mysql = { module = "org.flywaydb:flyway-mysql", version.ref = "flyway" }

# Redis
redisson = { module = "org.redisson:redisson-spring-boot-starter", version.ref = "redisson" }

# Resilience
resilience4j-spring-boot = { module = "io.github.resilience4j:resilience4j-spring-boot3", version.ref = "resilience4j" }

# Lombok
lombok = { module = "org.projectlombok:lombok" }

# Test
testcontainers-junit = { module = "org.testcontainers:junit-jupiter", version.ref = "testcontainers" }
testcontainers-mysql = { module = "org.testcontainers:mysql", version.ref = "testcontainers" }

[bundles]
database = ["spring-boot-starter-data-jpa", "mysql-connector", "flyway-core", "flyway-mysql"]
test = ["spring-boot-starter-test", "testcontainers-junit", "testcontainers-mysql"]

[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
spring-dependency-management = { id = "io.spring.dependency-management", version = "1.1.6" }
```

#### 명명 규칙

| TOML 키 | Gradle 접근자 |
|---------|--------------|
| `spring-boot-starter-web` | `libs.spring.boot.starter.web` |
| `flyway-core` | `libs.flyway.core` |
| `testcontainers-mysql` | `libs.testcontainers.mysql` |

**규칙**: `-`(하이픈) → `.`(점)으로 변환

#### 장점 요약

| 장점 | 설명 |
|------|------|
| **중앙 집중 관리** | 모든 버전이 한 파일에 |
| **IDE 자동완성** | `libs.` 입력 시 제안 |
| **타입 안전** | 오타 시 빌드 에러 |
| **업그레이드 용이** | 버전 변경 시 한 곳만 수정 |

---

### 4.3 루트 build.gradle

#### Gradle 기본 개념

Gradle은 **빌드 자동화 도구**입니다. `build.gradle` 파일에서 다음을 정의합니다:

```
┌─────────────────────────────────────────────────────────┐
│                    build.gradle 구조                     │
├─────────────────────────────────────────────────────────┤
│  plugins { }      ← 기능 확장 (Java 컴파일, Spring 등)    │
│  repositories { } ← 라이브러리 다운로드 위치              │
│  dependencies { } ← 사용할 외부 라이브러리               │
│  tasks { }        ← 빌드 작업 정의                       │
└─────────────────────────────────────────────────────────┘
```

#### 전체 예시 (한 줄씩 설명)

```groovy
// build.gradle (루트)

// ═══════════════════════════════════════════════════════
// 1. plugins 블록: 이 프로젝트에서 사용할 플러그인 선언
// ═══════════════════════════════════════════════════════
plugins {
    id 'java'
    // ↑ Java 컴파일 기능 활성화 (javac 사용)

    alias(libs.plugins.spring.boot) apply false
    // ↑ libs.versions.toml에서 정의한 플러그인 참조
    // ↑ "apply false" = 루트에는 적용 안 함, 하위 모듈에서 개별 적용

    alias(libs.plugins.spring.dependency.management) apply false
    // ↑ Spring Boot BOM(버전 관리) 플러그인
}

// ═══════════════════════════════════════════════════════
// 2. allprojects 블록: 루트 + 모든 하위 모듈에 적용
// ═══════════════════════════════════════════════════════
allprojects {
    group = 'com.hanumoka'
    // ↑ Maven 좌표의 groupId (패키지명과 유사)
    // ↑ 예: com.hanumoka:service-order:0.0.1-SNAPSHOT

    version = '0.0.1-SNAPSHOT'
    // ↑ 프로젝트 버전
    // ↑ SNAPSHOT = 개발 중인 버전

    repositories {
        mavenCentral()
        // ↑ 라이브러리를 다운로드할 저장소
        // ↑ Maven Central: 가장 큰 공개 저장소
    }
}

// ═══════════════════════════════════════════════════════
// 3. subprojects 블록: 하위 모듈에만 적용 (루트 제외)
// ═══════════════════════════════════════════════════════
subprojects {
    apply plugin: 'java'
    // ↑ 모든 하위 모듈에 Java 플러그인 적용
    // ↑ "apply plugin"은 plugins {} 블록 밖에서 사용하는 방식

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
            // ↑ Java 21 사용 강제
            // ↑ 시스템에 Java 21이 없으면 자동 다운로드
        }
    }

    // ───────────────────────────────────────────────────
    // 공통 의존성: 모든 하위 모듈에서 사용할 라이브러리
    // ───────────────────────────────────────────────────
    dependencies {
        compileOnly libs.lombok
        // ↑ 컴파일 시에만 필요 (런타임에는 불필요)
        // ↑ Lombok은 컴파일 시 코드를 생성하고 사라짐

        annotationProcessor libs.lombok
        // ↑ 어노테이션 처리기 (컴파일 시 @Getter 등 처리)

        testImplementation libs.spring.boot.starter.test
        // ↑ 테스트 코드에서만 사용하는 의존성
    }

    tasks.named('test') {
        useJUnitPlatform()
        // ↑ JUnit 5 사용 설정
    }
}
```

#### plugins 블록 상세 설명

```groovy
plugins {
    // 방식 1: 플러그인 ID 직접 지정
    id 'java'

    // 방식 2: 버전 카탈로그에서 참조 (권장)
    alias(libs.plugins.spring.boot) apply false
}
```

**`apply false`의 의미:**

```
┌─────────────────────────────────────────────────────────┐
│              apply true vs apply false                   │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  plugins {                                               │
│      id 'java'                      // apply true (기본) │
│      alias(...) apply false         // 선언만, 적용 안함 │
│  }                                                       │
│                                                          │
│  왜 apply false?                                         │
│  ├── 루트 프로젝트는 실행 가능한 앱이 아님               │
│  ├── Spring Boot 플러그인은 JAR/WAR 패키징 기능 제공     │
│  └── 하위 모듈에서 필요할 때만 개별 적용                 │
│                                                          │
│  예시:                                                   │
│  ├── common 모듈: 라이브러리 → Spring Boot 플러그인 불필요│
│  └── service-order: 실행 앱 → Spring Boot 플러그인 필요  │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

#### allprojects vs subprojects

```
┌─────────────────────────────────────────────────────────┐
│           프로젝트 구조와 적용 범위                       │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  spring-temporal-exam/        ← 루트 프로젝트            │
│  ├── common/                  ← 하위 모듈                │
│  ├── service-order/           ← 하위 모듈                │
│  └── service-payment/         ← 하위 모듈                │
│                                                          │
│  ┌─────────────┬─────────────────────────────────────┐  │
│  │   블록       │ 적용 대상                           │  │
│  ├─────────────┼─────────────────────────────────────┤  │
│  │ allprojects │ 루트 + common + service-order + ... │  │
│  │ subprojects │ common + service-order + ...        │  │
│  │             │ (루트 제외!)                         │  │
│  └─────────────┴─────────────────────────────────────┘  │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

#### dependencies 블록 상세 설명

```groovy
dependencies {
    // ═══════════════════════════════════════════════════
    // 의존성 구성(Configuration)별 용도
    // ═══════════════════════════════════════════════════

    implementation libs.spring.boot.starter.web
    // ↑ 컴파일 + 런타임에 필요
    // ↑ 가장 일반적인 의존성 선언 방식

    compileOnly libs.lombok
    // ↑ 컴파일 시에만 필요, 런타임에는 불필요
    // ↑ 예: Lombok, Annotation Processors

    runtimeOnly libs.mysql.connector
    // ↑ 런타임에만 필요, 컴파일 시 불필요
    // ↑ 예: JDBC 드라이버 (인터페이스는 JDK에 있음)

    testImplementation libs.spring.boot.starter.test
    // ↑ 테스트 코드에서만 사용
    // ↑ 메인 코드에서는 사용 불가

    annotationProcessor libs.lombok
    // ↑ 컴파일 시 어노테이션 처리
    // ↑ @Getter, @Setter 등을 실제 코드로 변환
}
```

**의존성 구성 요약:**

| 구성 | 컴파일 | 런타임 | 테스트 | 용도 |
|------|--------|--------|--------|------|
| `implementation` | ✓ | ✓ | ✓ | 일반 라이브러리 |
| `compileOnly` | ✓ | ✗ | ✗ | Lombok, Annotation |
| `runtimeOnly` | ✗ | ✓ | ✓ | JDBC 드라이버 |
| `testImplementation` | ✗ | ✗ | ✓ | 테스트 라이브러리 |
| `annotationProcessor` | 컴파일 시 처리 | - | - | 코드 생성기 |

#### 핵심 개념 요약

| 블록 | 적용 범위 | 주요 용도 |
|------|----------|----------|
| `plugins` | 현재 프로젝트 | 기능 확장 (Java, Spring Boot) |
| `allprojects` | 루트 + 모든 하위 | group, version, repositories |
| `subprojects` | 하위 모듈만 | 공통 플러그인, 공통 의존성 |
| `dependencies` | 현재 프로젝트 | 외부 라이브러리 선언 |

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
spring-boot = "3.4.0"

# 버전 변경 시 한 곳만 수정하면 됨
```

---

## 9. 실습 가이드

### Step 1: 멀티모듈 프로젝트 구조 설계

#### 1-1. settings.gradle 모듈 등록

**해야 할 일**: 7개 모듈을 등록하세요.

```
common
service-order
service-inventory
service-payment
service-notification
orchestrator-pure
orchestrator-temporal
```

**힌트**: `include` 키워드 사용

**검증**:
```bash
./gradlew projects
```

---

#### 1-2. 버전 카탈로그 생성

**해야 할 일**: `gradle/libs.versions.toml` 파일 생성

| 라이브러리 | 권장 버전 |
|-----------|----------|
| Spring Boot | 3.4.0 |
| Flyway | 10.8.1 |
| Redisson | 3.52.0 |
| Resilience4j | 2.2.0 |

**힌트**:
```toml
[versions]
# 버전 정의

[libraries]
# 라이브러리 정의

[plugins]
# 플러그인 정의
```

---

#### 1-3. 루트 build.gradle 설정

**해야 할 일**: `allprojects`와 `subprojects` 블록 설정

- **allprojects**: group, version, repositories
- **subprojects**: java 플러그인, Java 21 toolchain, 공통 의존성

**주의**: 루트에서는 `spring-boot` 플러그인을 직접 적용하지 않음

---

#### 1-4. 모듈 폴더 생성

**해야 할 일**: 7개 모듈 폴더와 build.gradle 생성

| 모듈 | 특성 | 주요 의존성 |
|------|------|-----------|
| common | 라이브러리 모듈 | validation |
| service-* | 실행 가능 | web, jpa, mysql |
| orchestrator-* | 실행 가능 | web, common |

**검증**:
```bash
./gradlew :common:build
./gradlew :service-order:build
```

---

### Step 2: 공통 모듈 (common) 구성

#### 2-1. common/build.gradle 작성

**고려 사항**:
- `java-library` 플러그인 사용
- `api` vs `implementation` 차이 이해

```groovy
plugins {
    id 'java-library'
}

dependencies {
    api libs.spring.boot.starter.validation  // 전이됨
    // implementation은 전이 안됨
}
```

---

#### 2-2. 공통 DTO 정의

**패키지 구조**:
```
common/src/main/java/com/hanumoka/common/
└── dto/
    ├── order/
    │   ├── CreateOrderRequest.java
    │   ├── OrderResponse.java
    │   └── OrderStatus.java (enum)
    ├── inventory/
    │   └── ReserveStockRequest.java
    └── payment/
        └── ProcessPaymentRequest.java
```

**Bean Validation 적용**: `@NotNull`, `@Positive`, `@Size` 등

---

#### 2-3. 공통 예외 클래스 정의

**패키지 구조**:
```
common/src/main/java/com/hanumoka/common/
└── exception/
    ├── BusinessException.java
    ├── ErrorCode.java (enum)
    ├── OrderNotFoundException.java
    ├── InsufficientStockException.java
    └── PaymentFailedException.java
```

---

#### 2-4. 공통 이벤트 클래스 정의

**패키지 구조**:
```
common/src/main/java/com/hanumoka/common/
└── event/
    ├── DomainEvent.java
    ├── OrderCreatedEvent.java
    └── PaymentCompletedEvent.java
```

---

### 최종 검증

```bash
# 전체 빌드
./gradlew clean build

# 모듈 의존성 확인
./gradlew dependencies
```

**성공 기준**:
- [ ] 7개 모듈 모두 빌드 성공
- [ ] 순환 참조 없음
- [ ] 버전 카탈로그 정상 작동

---

### 트러블슈팅

| 문제 | 원인 | 해결 |
|------|------|------|
| "Could not find method xxx" | Gradle 버전 불일치 | gradle-wrapper.properties 확인 |
| 모듈을 찾을 수 없음 | settings.gradle 미등록 | `include` 문 확인 |
| 순환 참조 | 모듈 간 양방향 의존 | 섹션 7.1 참고 |

---

### 자가 점검 질문

1. `api`와 `implementation`의 차이는 무엇인가요?
2. 왜 비즈니스 예외는 RuntimeException을 상속하나요?
3. record와 class 중 DTO에 어떤 것을 선택했나요? 그 이유는?

---

## 참고 자료

- [Spring 공식 가이드 - Multi Module Project](https://spring.io/guides/gs/multi-module/)
- [Gradle 멀티모듈 베스트 프랙티스](https://bootify.io/multi-module/best-practices-for-spring-boot-multi-module.html)
- [Reflectoring - Multi-Module with Gradle](https://reflectoring.io/spring-boot-gradle-multi-module/)
- [Java Code Geeks - Multimodule Best Practices](https://www.javacodegeeks.com/2025/06/multimodule-spring-boot-projects-with-maven-gradle-best-practices.html)

---

## 다음 단계

[02-flyway.md](./02-flyway.md) - DB 마이그레이션으로 이동
