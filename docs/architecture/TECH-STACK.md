# 기술 스택 검증

> **마지막 검토**: 2026년 1월 28일
> **검토 결과**: [REVIEW-2026-01.md](../reviews/REVIEW-2026-01.md) 참조

## 검증 완료 항목

| 항목 | 상태 | 검증 내용 |
|------|------|----------|
| Spring Boot 3.4.0 | ✅ | Core 라이브러리와 동일 버전 |
| Java 21 | ✅ | LTS 버전 |
| Redisson 3.52.0 | ✅ | Spring Boot 3.4 호환 |
| Temporal Spring Boot Integration | ✅ | Spring Boot 3.x에서 검증됨 |

## 적용 버전 (2026년 1월 기준)

| 컴포넌트 | 적용 버전 | 비고 |
|----------|-----------|------|
| **Spring Boot** | 3.4.0 | Core 라이브러리와 동일 |
| **Temporal SDK** | 1.32.0 | [Releases](https://github.com/temporalio/sdk-java/releases) |
| **Redisson** | 3.52.0 | Spring Boot 3.4 호환 |
| **Grafana** | 12.3.x | [Download](https://grafana.com/grafana/download) |
| **Loki** | 3.6.x | [Release Notes](https://grafana.com/docs/loki/latest/release-notes/) |
| **Prometheus** | 3.9.x | [Download](https://prometheus.io/download/) |
| **Grafana Alloy** | latest | Promtail 대체 |

## 버전 선택 이유

### Spring Boot 3.4.0 선택

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Spring Boot 버전 결정                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [결정] Spring Boot 3.4.0                                           │
│                                                                      │
│  [이유]                                                             │
│  1. Core 라이브러리와 동일 버전 유지                                 │
│     └── 자체 개발 Core 라이브러리가 Spring Boot 3.4.0 기반          │
│     └── 버전 불일치 시 호환성 문제 발생                              │
│                                                                      │
│  2. Temporal SDK 호환성 보장                                        │
│     └── temporal-spring-boot-starter가 Spring Boot 3.x 공식 지원    │
│     └── Spring Boot 4.x 호환성은 미확인                             │
│                                                                      │
│  3. Redisson 3.52.0 호환                                            │
│     └── Redisson 4.0+ 필요 시 Spring Boot 4.x 전환 필요             │
│     └── 현재는 3.52.0으로 충분                                      │
│                                                                      │
│  [추후 고도화]                                                      │
│  └── Phase 3 완료 후 Spring Boot 4.x 마이그레이션 검토              │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

## 참고 링크

- [Spring Boot 3.4.0 Release](https://spring.io/blog/2024/11/21/spring-boot-3-4-0-available-now/)
- [Temporal Java SDK](https://github.com/temporalio/sdk-java)
- [Redisson 3.52.0 Release](https://github.com/redisson/redisson/releases)

---

## 주요 경고 사항

### ⚠️ Temporal Docker 이미지 변경

`temporalio/auto-setup` 이미지는 **Deprecated** 되었습니다.
`temporalio/docker-compose` 저장소는 **2026-01-05 아카이브**되었습니다.

**개발 환경 권장 설정**:
```bash
# 방법 1: Temporal CLI (가장 간단, SQLite 내장)
temporal server start-dev

# 방법 2: Docker로 CLI 실행
docker run --rm -p 7233:7233 -p 8233:8233 \
  temporalio/temporal:latest \
  server start-dev --ip 0.0.0.0
```

**Docker Compose 사용 시 (외부 DB 필요)**:
```bash
# 새로운 공식 예제 저장소
git clone https://github.com/temporalio/samples-server.git
cd samples-server/compose
docker-compose up -d
```

**프로덕션 환경**: [Temporal Deployment Guide](https://docs.temporal.io/self-hosted-guide/deployment) 참조

**참고 링크**:
- [samples-server/compose](https://github.com/temporalio/samples-server/tree/main/compose) - 새로운 Docker Compose 예제
- [temporalio/server](https://hub.docker.com/r/temporalio/server) - 프로덕션 이미지

### ⚠️ Promtail EOL (2026년 3월 2일)

Promtail은 **End-of-Life** 예정입니다. Grafana Alloy로 마이그레이션하세요.

| 기간 | 상태 | 지원 범위 |
|------|------|----------|
| ~ 2025-02-12 | Active | 모든 지원 |
| 2025-02-13 ~ 2026-02-28 | **LTS** | 보안 패치, 중요 버그 수정만 |
| 2026-03-02 ~ | **EOL** | 지원 종료 |

**마이그레이션 방법**:
```bash
# Promtail → Alloy 설정 자동 변환
alloy convert --source-format=promtail \
  --output=alloy-config.alloy \
  promtail-config.yml
```

**Grafana Alloy 장점**:
- OpenTelemetry Collector 기반
- 로그, 메트릭, 트레이스 통합 수집
- 활발한 개발 및 장기 지원

**참조**:
- [Promtail to Alloy Migration](https://grafana.com/docs/loki/latest/setup/migrate/migrate-to-alloy/)
- [Grafana Alloy 문서](https://grafana.com/docs/alloy/latest/)
- [Alloy Docker 이미지](https://hub.docker.com/r/grafana/alloy)

### ⚠️ Temporal + Spring Boot 4 호환성

- Temporal Spring Boot Starter는 **Spring Boot 3.x** 기준으로 문서화됨
- Spring Boot 4.0과의 호환성이 공식적으로 명시되지 않음
- [공식 샘플](https://github.com/temporalio/spring-boot-demo)이 Spring Boot 3 기준

**권장 조치**: Phase 3 시작 전 Temporal SDK의 Spring Boot 4 지원 여부 재확인 필요

### build.gradle 테스트 의존성

```gradle
testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'
```

빌드 및 테스트 실행 시 정상 동작 여부 확인 필요

## MQ 선택 비교: Redis Stream vs Kafka

| 구분 | Redis Stream | Kafka |
|------|-------------|-------|
| 설정 복잡도 | 낮음 | 중간~높음 |
| 지연 시간 | 매우 낮음 (ms 이하) | 낮음 |
| 메시지 내구성 | 설정 필요 | 기본 제공 |
| 확장성 | 중간 | 높음 |
| 학습 곡선 | 낮음 | 중간 |
| Docker 구성 | 간단 (Redis만) | 복잡 (ZK + Broker) |

**선택**: Redis Stream (설정 간단, 빠른 학습 가능)

## Saga 패턴 비교

| 구분 | Orchestration | Choreography |
|------|---------------|--------------|
| 제어 방식 | 중앙 오케스트레이터 | 분산 (이벤트 기반) |
| 디버깅 | 용이 | 어려움 |
| 모니터링 | 중앙 집중 가능 | 분산 추적 필요 |
| 복잡한 플로우 | 적합 | 부적합 |
| 업계 사용 비율 | 70-80% | 20-30% |

**선택**: Orchestration (플로우 이해 용이, Temporal 전환 자연스러움)

---

## Spring Cloud vs Kubernetes vs Temporal 비교

### MSA 기술 진화

```
[2015-2018] Netflix OSS 시대
├── Eureka, Ribbon, Hystrix, Zuul

[2019-2022] Netflix OSS Deprecated
├── Ribbon → Spring Cloud LoadBalancer
├── Hystrix → Resilience4j
├── Zuul → Spring Cloud Gateway
└── Sleuth → Micrometer Tracing

[2023-현재] 플랫폼 레벨 전환
├── Kubernetes 네이티브
├── Service Mesh (Istio)
└── Temporal/Cadence
```

### 기술 선택 매트릭스

| 관심사 | Spring Cloud | Kubernetes | Service Mesh | Temporal |
|--------|--------------|------------|--------------|----------|
| 서비스 디스커버리 | Eureka | K8s Service | - | - |
| 로드 밸런싱 | LoadBalancer | K8s Service | Istio | - |
| API 게이트웨이 | Gateway | Ingress | Istio Gateway | - |
| 설정 관리 | Config Server | ConfigMap | - | - |
| 서킷 브레이커 | Resilience4j | - | Istio | Activity 재시도 |
| 워크플로우 | - | - | - | **핵심 기능** |
| Saga 패턴 | 직접 구현 | - | - | **내장** |

### 이 프로젝트의 선택

| 기술 | 결정 | 이유 |
|------|------|------|
| Spring Cloud | **미사용** | 학습 목표 집중, Temporal과 역할 중복 |
| Kubernetes | **미사용** | Docker Compose로 충분, 인프라 학습 분리 |
| Service Mesh | **미사용** | K8s 없이 의미 없음, 학습 범위 외 |
| Temporal | **사용** | 핵심 학습 주제 |
| Resilience4j | **사용** | 장애 대응 패턴 학습 |

### 실무 확장 경로

```
[현재] Docker Compose + Temporal + Resilience4j
        │
        ├─[Option A]─▶ + Spring Cloud (Gateway, Eureka)
        │               └── Java 중심 환경에 적합
        │
        ├─[Option B]─▶ + Kubernetes
        │               └── 폴리글랏, 플랫폼 표준
        │
        └─[Option C]─▶ + K8s + Service Mesh (Istio)
                        └── 대규모 엔터프라이즈
```

**상세 가이드**: [MSA 아키텍처 선택 가이드](./MSA-ARCHITECTURE-GUIDE.md)
