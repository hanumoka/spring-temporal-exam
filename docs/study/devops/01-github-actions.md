# GitHub Actions CI/CD

## 개요

### What (무엇인가)
GitHub Actions는 GitHub에서 제공하는 CI/CD 플랫폼으로, 코드 푸시/PR 시 자동으로 빌드, 테스트, 배포를 수행합니다.

### Why (왜 필요한가)

```
┌─────────────────────────────────────────────────────────────────────┐
│                    마이크로서비스 CI/CD 필요성                       │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [수동 배포의 문제]                                                  │
│  ├── 휴먼 에러 (잘못된 설정, 누락된 테스트)                         │
│  ├── 느린 피드백 (문제 발견까지 시간 소요)                          │
│  └── 확장 어려움 (서비스 수 증가 시 관리 불가)                      │
│                                                                      │
│  [CI/CD 도입 효과]                                                  │
│  ├── 자동화된 테스트 → 품질 보장                                    │
│  ├── 빠른 피드백 → 문제 조기 발견                                   │
│  └── 일관된 배포 → 신뢰성 향상                                      │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 1. 파이프라인 아키텍처

```
┌─────────────────────────────────────────────────────────────────────┐
│                    CI/CD 파이프라인 구조                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [Trigger]                                                          │
│  Push to main / PR                                                  │
│       │                                                              │
│       ▼                                                              │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  Build & Unit Test                                           │    │
│  │  ├── Gradle Build                                            │    │
│  │  └── JUnit 5 테스트                                          │    │
│  └─────────────────────────────────────────────────────────────┘    │
│       │                                                              │
│       ▼                                                              │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  Contract Test                                                │    │
│  │  ├── Consumer 테스트 (Pact 파일 생성)                        │    │
│  │  └── Provider 테스트 (계약 검증)                             │    │
│  └─────────────────────────────────────────────────────────────┘    │
│       │                                                              │
│       ▼                                                              │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  Integration Test (Testcontainers)                           │    │
│  │  └── MySQL, Redis 연동 테스트                                │    │
│  └─────────────────────────────────────────────────────────────┘    │
│       │                                                              │
│       ▼                                                              │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  Security Scan                                                │    │
│  │  ├── Trivy (컨테이너 취약점)                                 │    │
│  │  └── CodeQL (코드 보안 분석)                                 │    │
│  └─────────────────────────────────────────────────────────────┘    │
│       │                                                              │
│       ▼                                                              │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  Docker Build & Push                                          │    │
│  │  └── ghcr.io 또는 Docker Hub                                 │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 2. 기본 CI 워크플로우

### 2.1 빌드 및 테스트

```yaml
# .github/workflows/ci.yml
name: CI

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main, develop]

env:
  JAVA_VERSION: '21'
  GRADLE_VERSION: '8.5'

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          java-version: ${{ env.JAVA_VERSION }}
          distribution: 'temurin'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v3

      - name: Cache Gradle packages
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
          restore-keys: |
            ${{ runner.os }}-gradle-

      - name: Build
        run: ./gradlew build -x test

      - name: Unit Tests
        run: ./gradlew test

      - name: Upload Test Results
        uses: actions/upload-artifact@v4
        if: always()
        with:
          name: test-results
          path: '**/build/reports/tests/'
```

---

## 3. 서비스별 독립 파이프라인

### 3.1 변경 감지 기반 빌드

```yaml
# .github/workflows/service-order.yml
name: Service Order CI

on:
  push:
    branches: [main]
    paths:
      - 'service-order/**'
      - 'common/**'
      - '.github/workflows/service-order.yml'
  pull_request:
    paths:
      - 'service-order/**'
      - 'common/**'

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Build Common
        run: ./gradlew :common:build

      - name: Build Service Order
        run: ./gradlew :service-order:build

      - name: Run Tests
        run: ./gradlew :service-order:test

  integration-test:
    needs: build
    runs-on: ubuntu-latest

    services:
      mysql:
        image: mysql:8.0
        env:
          MYSQL_ROOT_PASSWORD: root
          MYSQL_DATABASE: order_db
        ports:
          - 3306:3306
        options: >-
          --health-cmd="mysqladmin ping -h localhost"
          --health-interval=10s
          --health-timeout=5s
          --health-retries=5

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Run Integration Tests
        run: ./gradlew :service-order:integrationTest
        env:
          SPRING_PROFILES_ACTIVE: ci
```

---

## 4. Docker 빌드 및 푸시

### 4.1 멀티스테이지 Dockerfile

```dockerfile
# service-order/Dockerfile
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Gradle 캐싱
COPY gradle gradle
COPY gradlew build.gradle settings.gradle ./
COPY common/build.gradle common/
COPY service-order/build.gradle service-order/

RUN ./gradlew dependencies --no-daemon

# 소스 복사 및 빌드
COPY common/src common/src
COPY service-order/src service-order/src

RUN ./gradlew :service-order:bootJar --no-daemon

# 런타임 이미지
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 보안: non-root 사용자
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

COPY --from=builder /app/service-order/build/libs/*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 4.2 Docker 빌드 워크플로우

```yaml
# .github/workflows/docker-build.yml
name: Docker Build

on:
  push:
    branches: [main]
    tags: ['v*']

env:
  REGISTRY: ghcr.io
  IMAGE_NAME: ${{ github.repository }}

jobs:
  build-and-push:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        service: [service-order, service-inventory, service-payment, orchestrator-pure]

    permissions:
      contents: read
      packages: write

    steps:
      - uses: actions/checkout@v4

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Log in to Registry
        uses: docker/login-action@v3
        with:
          registry: ${{ env.REGISTRY }}
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Extract metadata
        id: meta
        uses: docker/metadata-action@v5
        with:
          images: ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}/${{ matrix.service }}
          tags: |
            type=sha,prefix=
            type=ref,event=branch
            type=semver,pattern={{version}}

      - name: Build and Push
        uses: docker/build-push-action@v5
        with:
          context: .
          file: ${{ matrix.service }}/Dockerfile
          push: true
          tags: ${{ steps.meta.outputs.tags }}
          labels: ${{ steps.meta.outputs.labels }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
```

---

## 5. 보안 스캔

### 5.1 Trivy 컨테이너 스캔

```yaml
# .github/workflows/security.yml
name: Security Scan

on:
  push:
    branches: [main]
  schedule:
    - cron: '0 0 * * 1'  # 매주 월요일

jobs:
  trivy:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Build Docker Image
        run: docker build -t test-image:${{ github.sha }} -f service-order/Dockerfile .

      - name: Run Trivy vulnerability scanner
        uses: aquasecurity/trivy-action@master
        with:
          image-ref: 'test-image:${{ github.sha }}'
          format: 'sarif'
          output: 'trivy-results.sarif'
          severity: 'CRITICAL,HIGH'

      - name: Upload Trivy scan results
        uses: github/codeql-action/upload-sarif@v3
        with:
          sarif_file: 'trivy-results.sarif'

  codeql:
    runs-on: ubuntu-latest

    permissions:
      security-events: write

    steps:
      - uses: actions/checkout@v4

      - name: Initialize CodeQL
        uses: github/codeql-action/init@v3
        with:
          languages: java

      - name: Build
        run: ./gradlew build -x test

      - name: Perform CodeQL Analysis
        uses: github/codeql-action/analyze@v3
```

---

## 6. 전체 파이프라인

### 6.1 통합 워크플로우

```yaml
# .github/workflows/main.yml
name: Main Pipeline

on:
  push:
    branches: [main]

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  # 1단계: 빌드 및 단위 테스트
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      - run: ./gradlew build

  # 2단계: Contract 테스트
  contract-test:
    needs: build
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      - run: ./gradlew contractTest

  # 3단계: 통합 테스트
  integration-test:
    needs: contract-test
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      - run: ./gradlew integrationTest

  # 4단계: 보안 스캔
  security-scan:
    needs: build
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Run Trivy
        uses: aquasecurity/trivy-action@master
        with:
          scan-type: 'fs'
          scan-ref: '.'

  # 5단계: Docker 빌드 (모든 테스트 통과 시)
  docker:
    needs: [integration-test, security-scan]
    runs-on: ubuntu-latest
    strategy:
      matrix:
        service: [service-order, service-inventory, service-payment]
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - uses: docker/build-push-action@v5
        with:
          context: .
          file: ${{ matrix.service }}/Dockerfile
          push: true
          tags: ghcr.io/${{ github.repository }}/${{ matrix.service }}:${{ github.sha }}
```

---

## 7. 핵심 학습 포인트

### 7.1 CI/CD 베스트 프랙티스

```
┌─────────────────────────────────────────────────────────────────────┐
│                    CI/CD 베스트 프랙티스                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  1. 서비스별 독립 파이프라인                                         │
│     └── 변경된 서비스만 빌드/테스트/배포                            │
│                                                                      │
│  2. 병렬 실행                                                        │
│     └── 독립적인 Job은 동시 실행                                    │
│                                                                      │
│  3. 캐싱 활용                                                        │
│     └── Gradle, Docker 레이어 캐싱                                  │
│                                                                      │
│  4. 시크릿 관리                                                      │
│     └── GitHub Secrets 사용, 코드에 노출 금지                       │
│                                                                      │
│  5. 실패 시 빠른 피드백                                              │
│     └── Slack 알림, PR 상태 체크                                    │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 7.2 보안 체크리스트

| 항목 | 도구 | 빈도 |
|------|------|------|
| 컨테이너 취약점 | Trivy | 모든 빌드 |
| 코드 보안 | CodeQL | 모든 PR |
| 의존성 취약점 | Dependabot | 자동 |
| 시크릿 노출 | Gitleaks | 모든 커밋 |

---

## 관련 문서

- [D023 CI/CD 파이프라인](../../architecture/DECISIONS.md#d023-cicd-파이프라인-전략)
- [GitHub Actions 공식 문서](https://docs.github.com/en/actions)
- [10-contract-testing.md](../phase2a/10-contract-testing.md)
