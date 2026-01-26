# Step 1: 멀티모듈 프로젝트 구조 설계

> **사전 학습**: [01-gradle-multimodule.md](../../study/phase1/01-gradle-multimodule.md) 를 먼저 읽어주세요.

## 목표

Spring Boot 3.4.0 기반 멀티모듈 프로젝트 구조를 직접 구성합니다.

---

## 1-1. settings.gradle 모듈 등록

### 해야 할 일

현재 `settings.gradle`에 7개 모듈을 등록하세요.

### 등록할 모듈

```
common
service-order
service-inventory
service-payment
service-notification
orchestrator-pure
orchestrator-temporal
```

### 힌트

- `include` 키워드를 사용합니다
- 각 모듈은 루트 프로젝트의 하위 디렉토리가 됩니다

### 검증 방법

```bash
./gradlew projects
```

7개 모듈이 출력되면 성공입니다.

### 체크리스트

```
[ ] settings.gradle에 7개 모듈 등록
[ ] ./gradlew projects로 모듈 목록 확인
```

---

## 1-2. 버전 카탈로그 생성

### 해야 할 일

`gradle/libs.versions.toml` 파일을 생성하고 의존성 버전을 중앙 관리하세요.

### 정의할 버전

| 라이브러리 | 권장 버전 |
|-----------|----------|
| Spring Boot | 3.4.0 |
| Lombok | (Spring Boot BOM) |
| MySQL Connector | (Spring Boot BOM) |
| Flyway | 10.8.1 |
| Redisson | 3.52.0 |
| Resilience4j | 2.2.0 |

### 힌트

버전 카탈로그 구조:
```toml
[versions]
# 버전 정의

[libraries]
# 라이브러리 정의

[plugins]
# 플러그인 정의
```

### 참고할 키워드

- `module = "group:artifact"`
- `version.ref = "버전키"`
- Spring Boot BOM 관리 라이브러리는 버전 생략 가능

### 검증 방법

```bash
./gradlew dependencies --configuration compileClasspath
```

### 체크리스트

```
[ ] gradle/libs.versions.toml 파일 생성
[ ] [versions] 섹션에 버전 정의
[ ] [libraries] 섹션에 라이브러리 정의
[ ] [plugins] 섹션에 플러그인 정의
```

---

## 1-3. 루트 build.gradle 설정

### 해야 할 일

루트 `build.gradle`에 `allprojects`와 `subprojects` 블록을 설정하세요.

### 설정할 내용

1. **allprojects**: 모든 프로젝트 공통 설정
   - group
   - version
   - repositories

2. **subprojects**: 하위 모듈 공통 설정
   - java 플러그인
   - Java 21 toolchain
   - 공통 의존성 (lombok, test)
   - 테스트 설정

### 힌트

```groovy
allprojects {
    // 모든 프로젝트에 적용
}

subprojects {
    // 하위 모듈에만 적용
    apply plugin: 'java'

    java {
        toolchain {
            // Java 버전 설정
        }
    }
}
```

### 주의사항

- 루트 프로젝트에서는 `spring-boot` 플러그인을 직접 적용하지 않습니다
- 각 서비스 모듈에서 개별적으로 적용합니다

### 검증 방법

```bash
./gradlew build
```

### 체크리스트

```
[ ] allprojects 블록 설정
[ ] subprojects 블록 설정
[ ] Java 21 toolchain 설정
[ ] 공통 의존성 설정
```

---

## 1-4. 모듈 폴더 생성

### 해야 할 일

7개 모듈 폴더와 기본 구조를 생성하세요.

### 폴더 구조

```
spring-temporal-exam/
├── common/
│   ├── build.gradle
│   └── src/main/java/
├── service-order/
│   ├── build.gradle
│   └── src/main/java/
├── service-inventory/
│   ├── build.gradle
│   └── src/main/java/
├── service-payment/
│   ├── build.gradle
│   └── src/main/java/
├── service-notification/
│   ├── build.gradle
│   └── src/main/java/
├── orchestrator-pure/
│   ├── build.gradle
│   └── src/main/java/
└── orchestrator-temporal/
    ├── build.gradle
    └── src/main/java/
```

### 힌트

각 모듈의 `build.gradle`은 최소한 다음을 포함해야 합니다:
- 필요한 플러그인
- 해당 모듈에 필요한 의존성

### 모듈별 특성

| 모듈 | 특성 | 주요 의존성 |
|------|------|-----------|
| common | 라이브러리 모듈 | validation |
| service-* | 실행 가능한 애플리케이션 | web, jpa, mysql |
| orchestrator-* | 실행 가능한 애플리케이션 | web, common |

### 검증 방법

```bash
./gradlew :common:build
./gradlew :service-order:build
```

### 체크리스트

```
[ ] common/ 폴더 및 build.gradle 생성
[ ] service-order/ 폴더 및 build.gradle 생성
[ ] service-inventory/ 폴더 및 build.gradle 생성
[ ] service-payment/ 폴더 및 build.gradle 생성
[ ] service-notification/ 폴더 및 build.gradle 생성
[ ] orchestrator-pure/ 폴더 및 build.gradle 생성
[ ] orchestrator-temporal/ 폴더 및 build.gradle 생성
[ ] 각 모듈 개별 빌드 성공
```

---

## 최종 검증

### 전체 빌드

```bash
./gradlew clean build
```

### 모듈 의존성 확인

```bash
./gradlew dependencies
```

### 성공 기준

- [ ] 7개 모듈 모두 빌드 성공
- [ ] 순환 참조 없음
- [ ] 버전 카탈로그 정상 작동

---

## 트러블슈팅

### 문제: "Could not find method xxx"

**원인**: Gradle 버전과 문법 불일치
**해결**: `gradle/wrapper/gradle-wrapper.properties`에서 Gradle 버전 확인

### 문제: 모듈을 찾을 수 없음

**원인**: settings.gradle에 모듈 미등록
**해결**: `include` 문 확인

### 문제: 순환 참조

**원인**: 모듈 간 의존성이 양방향
**해결**: 학습 문서의 "순환 참조 해결" 섹션 참고

---

## 다음 단계

Step 1 완료 후 → [step2-common-module.md](./step2-common-module.md)
