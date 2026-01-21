# MSA 환경별 아키텍처 선택 가이드

## 이 문서의 목적

MSA(Microservice Architecture) 환경에서 **Spring Cloud**, **Kubernetes**, **Service Mesh**, **Temporal**을 어떻게 조합하여 사용할지에 대한 가이드입니다.

이 프로젝트의 기술 선택 배경과 실무에서의 적용 방향을 설명합니다.

---

## 1. MSA 기술 스택의 진화

```
┌─────────────────────────────────────────────────────────────────────┐
│                    MSA 기술 스택의 진화                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [2015-2018] Netflix OSS 시대                                       │
│  ├── Eureka (서비스 디스커버리)                                      │
│  ├── Ribbon (클라이언트 로드밸런싱)                                  │
│  ├── Hystrix (서킷 브레이커)                                        │
│  └── Zuul (API 게이트웨이)                                          │
│                                                                      │
│  [2019-2022] Netflix OSS 유지보수 모드 → Deprecated                 │
│  ├── Ribbon → Spring Cloud LoadBalancer                             │
│  ├── Hystrix → Resilience4j                                         │
│  ├── Zuul → Spring Cloud Gateway                                    │
│  └── Sleuth → Micrometer Tracing (Spring Boot 3.x)                  │
│                                                                      │
│  [2023-현재] 플랫폼 레벨 전환 + 워크플로우 엔진                       │
│  ├── Kubernetes 네이티브 기능 활용                                   │
│  ├── Service Mesh (Istio, Linkerd)                                  │
│  └── Temporal/Cadence 워크플로우 오케스트레이션                      │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Deprecated된 Netflix OSS 컴포넌트

| Deprecated | 대체 기술 | 비고 |
|------------|----------|------|
| Netflix Hystrix | **Resilience4j** | Spring Cloud Circuit Breaker |
| Netflix Ribbon | **Spring Cloud LoadBalancer** | 2020.0.0에서 제거 |
| Netflix Zuul | **Spring Cloud Gateway** | 권장 대체재 |
| Spring Cloud Sleuth | **Micrometer Tracing** | Spring Boot 3.x |
| Hystrix Dashboard | **Prometheus/Grafana** | 별도 모니터링 필요 |

---

## 2. 세 가지 환경별 아키텍처

### 시나리오 A: Spring Cloud 중심 (K8s 미사용)

```
┌─────────────────────────────────────────────────────────────────────┐
│                 시나리오 A: Spring Cloud 중심                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  적합한 상황:                                                        │
│  ├── VM 또는 베어메탈 환경                                           │
│  ├── Java/Spring 생태계 통일                                         │
│  └── 운영팀이 K8s 경험 부족                                          │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────┐       │
│  │                  Spring Cloud Gateway                     │       │
│  └────────────────────────┬─────────────────────────────────┘       │
│                           │                                          │
│  ┌────────────────────────┼─────────────────────────────────┐       │
│  │                   Eureka Server                           │       │
│  └────────────────────────┼─────────────────────────────────┘       │
│          ┌────────────────┼────────────────┐                        │
│          ▼                ▼                ▼                        │
│    ┌──────────┐    ┌──────────┐    ┌──────────┐                    │
│    │ Order    │    │ Payment  │    │ Inventory│                    │
│    │ Service  │    │ Service  │    │ Service  │                    │
│    │          │    │          │    │          │                    │
│    │+Resilience4j│+Resilience4j│+Resilience4j│                     │
│    │+LoadBalancer│+LoadBalancer│+LoadBalancer│                     │
│    └──────────┘    └──────────┘    └──────────┘                    │
│                                                                      │
│  필요 컴포넌트:                                                      │
│  ├── Spring Cloud Gateway (API 게이트웨이)                          │
│  ├── Eureka (서비스 디스커버리)                                      │
│  ├── Spring Cloud LoadBalancer (로드밸런싱)                         │
│  ├── Resilience4j (서킷 브레이커, 재시도)                           │
│  ├── Spring Cloud Config (설정 관리) - 선택                         │
│  └── Micrometer Tracing (분산 추적)                                 │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

**장점:**
- Java 개발자에게 익숙한 환경
- Spring 생태계와 자연스러운 통합
- 별도의 인프라 학습 불필요

**단점:**
- Java 언어에 종속됨
- 인프라 레벨 기능을 애플리케이션에서 처리

---

### 시나리오 B: Kubernetes 중심 (Spring Cloud 최소화)

```
┌─────────────────────────────────────────────────────────────────────┐
│                 시나리오 B: Kubernetes 중심                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  적합한 상황:                                                        │
│  ├── K8s 환경 운영 가능                                              │
│  ├── 폴리글랏 (다양한 언어 서비스)                                   │
│  └── 플랫폼 레벨 추상화 선호                                         │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────┐       │
│  │              Kubernetes Ingress / Gateway API             │       │
│  └────────────────────────┬─────────────────────────────────┘       │
│                           │                                          │
│  ┌────────────────────────┼─────────────────────────────────┐       │
│  │              Kubernetes Service Discovery                 │       │
│  │                  (kube-dns / CoreDNS)                     │       │
│  └────────────────────────┼─────────────────────────────────┘       │
│          ┌────────────────┼────────────────┐                        │
│          ▼                ▼                ▼                        │
│    ┌──────────┐    ┌──────────┐    ┌──────────┐                    │
│    │ Order    │    │ Payment  │    │ Inventory│                    │
│    │ (Java)   │    │ (Go)     │    │ (Python) │  ← 폴리글랏 가능   │
│    │          │    │          │    │          │                    │
│    │+Resilience4j│           │              │                      │
│    └──────────┘    └──────────┘    └──────────┘                    │
│                                                                      │
│  K8s가 대체하는 것:                                                  │
│  ├── Eureka → K8s Service + DNS                                     │
│  ├── Spring Cloud Config → ConfigMap/Secret                        │
│  ├── Ribbon → K8s Service (서버사이드 LB)                           │
│  └── Zuul/Gateway → Ingress Controller                              │
│                                                                      │
│  여전히 필요한 것:                                                   │
│  ├── Resilience4j (애플리케이션 레벨 서킷 브레이커)                 │
│  └── Micrometer (메트릭/추적) 또는 Service Mesh                     │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

**장점:**
- 언어 독립적 (폴리글랏)
- 플랫폼 표준 기술 사용
- 인프라와 애플리케이션 관심사 분리

**단점:**
- K8s 학습 필요
- 운영 복잡도 증가

---

### 시나리오 C: K8s + Service Mesh + Temporal (권장 - 대규모)

```
┌─────────────────────────────────────────────────────────────────────┐
│           시나리오 C: K8s + Service Mesh + Temporal                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  적합한 상황:                                                        │
│  ├── 복잡한 비즈니스 워크플로우                                      │
│  ├── 장기 실행 트랜잭션 (Saga)                                       │
│  ├── 높은 신뢰성 요구                                                │
│  └── 대규모 MSA 환경                                                 │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────┐       │
│  │           Kubernetes Ingress / Gateway API                │       │
│  └────────────────────────┬─────────────────────────────────┘       │
│                           │                                          │
│  ┌────────────────────────┼─────────────────────────────────┐       │
│  │              Service Mesh (Istio/Linkerd)                 │       │
│  │  ┌─────────────────────────────────────────────────────┐ │       │
│  │  │ - mTLS 자동 암호화                                   │ │       │
│  │  │ - 서킷 브레이커 (플랫폼 레벨)                        │ │       │
│  │  │ - 트래픽 관리 (카나리, A/B)                          │ │       │
│  │  │ - 분산 추적 자동 수집                                │ │       │
│  │  └─────────────────────────────────────────────────────┘ │       │
│  └────────────────────────┼─────────────────────────────────┘       │
│                           │                                          │
│  ┌────────────────────────┼─────────────────────────────────┐       │
│  │                   Temporal Cluster                        │       │
│  │  ┌─────────────────────────────────────────────────────┐ │       │
│  │  │ - 워크플로우 오케스트레이션                          │ │       │
│  │  │ - 상태 관리 및 내구성                                │ │       │
│  │  │ - 자동 재시도 및 보상                                │ │       │
│  │  │ - 장기 실행 프로세스                                 │ │       │
│  │  └─────────────────────────────────────────────────────┘ │       │
│  └────────────────────────┼─────────────────────────────────┘       │
│          ┌────────────────┼────────────────┐                        │
│          ▼                ▼                ▼                        │
│    ┌──────────┐    ┌──────────┐    ┌──────────┐                    │
│    │ Order    │    │ Payment  │    │ Inventory│                    │
│    │ Worker   │    │ Worker   │    │ Worker   │                    │
│    └──────────┘    └──────────┘    └──────────┘                    │
│                                                                      │
│  역할 분담:                                                          │
│  ├── Service Mesh: 네트워크 레벨 (mTLS, 라우팅, 관측성)             │
│  ├── Temporal: 비즈니스 레벨 (워크플로우, Saga, 상태)               │
│  └── K8s: 인프라 레벨 (스케줄링, 스케일링, 디스커버리)              │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

**장점:**
- 완전한 관측성과 보안
- 비즈니스 로직과 인프라 관심사 완전 분리
- 높은 신뢰성과 확장성

**단점:**
- 높은 복잡도
- 운영 전문성 필요
- 학습 곡선이 가파름

---

## 3. 기술 선택 매트릭스

| 관심사 | Spring Cloud | Kubernetes | Service Mesh | Temporal |
|--------|--------------|------------|--------------|----------|
| **서비스 디스커버리** | Eureka | K8s Service | - | - |
| **로드 밸런싱** | LoadBalancer | K8s Service | Istio | - |
| **API 게이트웨이** | Gateway | Ingress | Istio Gateway | - |
| **설정 관리** | Config Server | ConfigMap | - | - |
| **서킷 브레이커** | Resilience4j | - | Istio | Activity 재시도 |
| **재시도** | Resilience4j | - | Istio | 내장 |
| **분산 추적** | Micrometer | - | 자동 | 내장 |
| **mTLS** | 수동 설정 | - | 자동 | - |
| **워크플로우** | - | - | - | 핵심 기능 |
| **Saga 패턴** | 직접 구현 | - | - | 내장 |
| **상태 관리** | 직접 구현 | - | - | 내장 |

---

## 4. Temporal과 다른 기술의 관계

```
┌─────────────────────────────────────────────────────────────────────┐
│                 Temporal의 역할과 경계                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Temporal이 담당하는 것:                                             │
│  ├── 비즈니스 워크플로우 오케스트레이션                              │
│  ├── Saga 패턴 (보상 트랜잭션)                                       │
│  ├── 장기 실행 프로세스 상태 관리                                    │
│  ├── Activity 재시도 및 타임아웃                                     │
│  └── 워크플로우 레벨 분산 추적                                       │
│                                                                      │
│  Temporal이 담당하지 않는 것 (다른 기술 필요):                       │
│  ├── 서비스 디스커버리 → K8s Service / Eureka                       │
│  ├── API 게이트웨이 → Ingress / Spring Cloud Gateway                │
│  ├── 네트워크 레벨 mTLS → Service Mesh                              │
│  ├── 트래픽 관리 (카나리, A/B) → Service Mesh                       │
│  └── 인프라 메트릭 수집 → Prometheus / Micrometer                   │
│                                                                      │
│  중복/대체 가능한 영역:                                              │
│  ├── 서킷 브레이커: Resilience4j vs Temporal Activity 재시도        │
│  │   └── 권장: 단순 호출은 Resilience4j, 워크플로우는 Temporal       │
│  │                                                                   │
│  └── 재시도 로직: Resilience4j vs Temporal                          │
│      └── 권장: Temporal Activity 내에서는 Temporal 재시도 사용       │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Resilience4j vs Temporal 사용 구분

| 상황 | 권장 기술 | 이유 |
|------|----------|------|
| 단순 REST 호출 | Resilience4j | 가볍고 설정이 간단 |
| Temporal Activity 내부 | Temporal 재시도 | Temporal이 상태를 관리 |
| 워크플로우 오케스트레이션 | Temporal | 복잡한 상태와 보상 처리 |
| 외부 API 호출 (비워크플로우) | Resilience4j | 워크플로우 외부에서 사용 |

---

## 5. 이 프로젝트의 선택

### 현재 프로젝트 범위

```
┌─────────────────────────────────────────────────────────────────────┐
│                    현재 프로젝트 기술 선택                           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  선택된 기술:                                                        │
│  ├── Docker Compose 기반 로컬 환경                                   │
│  ├── 직접 URL 지정 (localhost:808x)                                  │
│  ├── Resilience4j (서킷 브레이커, 재시도)                            │
│  ├── Temporal (워크플로우 오케스트레이션)                            │
│  └── OpenTelemetry + Prometheus + Grafana (관측성)                  │
│                                                                      │
│  의도적으로 제외된 기술:                                             │
│  ├── Spring Cloud (Eureka, Gateway, Config)                         │
│  ├── Kubernetes                                                      │
│  └── Service Mesh (Istio)                                           │
│                                                                      │
│  제외 이유:                                                          │
│  1. 학습 목표 집중: Temporal과 분산 트랜잭션에 집중                  │
│  2. 복잡도 관리: 핵심 개념 이해에 집중                               │
│  3. 인프라 독립: K8s/Mesh는 별도 DevOps 영역                        │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 실무 확장 시 권장 경로

```
[현재 프로젝트 - 학습용]
Docker Compose + Temporal + Resilience4j
           │
           ▼
[실무 확장 Option A - Java 중심]
+ Spring Cloud Gateway
+ Eureka
+ Spring Cloud LoadBalancer
           │
           ▼
[실무 확장 Option B - K8s 전환]
+ Kubernetes Deployment
+ K8s Service/Ingress
+ ConfigMap/Secret
           │
           ▼
[실무 확장 Option C - 대규모]
+ Service Mesh (Istio)
+ Temporal Cloud (관리형)
+ 완전한 관측성 스택
```

---

## 6. 실무 환경별 권장사항

### 스타트업 / 소규모 팀

```
권장: 시나리오 A (Spring Cloud) 또는 현재 프로젝트 구성

이유:
- 빠른 개발 속도
- 적은 인프라 관리 부담
- Java 개발자가 모든 것을 관리 가능

구성:
- Docker Compose 또는 단순 VM
- Spring Cloud Gateway + Eureka (필요시)
- Resilience4j
- Temporal (복잡한 워크플로우가 있는 경우)
```

### 중규모 조직

```
권장: 시나리오 B (Kubernetes 중심)

이유:
- 다양한 언어/프레임워크 사용 가능
- 표준화된 배포 파이프라인
- 자동 스케일링 필요

구성:
- Kubernetes
- Ingress Controller (nginx, traefik)
- Resilience4j (Java 서비스)
- Temporal
- Prometheus + Grafana
```

### 대규모 조직 / 엔터프라이즈

```
권장: 시나리오 C (K8s + Service Mesh + Temporal)

이유:
- 수백 개 이상의 마이크로서비스
- 엄격한 보안 요구사항
- 멀티 리전/멀티 클라우드

구성:
- Kubernetes (멀티 클러스터)
- Service Mesh (Istio)
- Temporal Cloud
- 완전한 관측성 스택
```

---

## 7. 미래 학습 로드맵

현재 프로젝트 이후 확장 학습이 필요한 경우:

| 우선순위 | 주제 | 설명 |
|---------|------|------|
| 1순위 | OpenFeign | 선언적 HTTP 클라이언트 (RestTemplate 대체) |
| 2순위 | Spring Cloud Gateway | API 게이트웨이 기초 |
| 3순위 | Kubernetes 기초 | 컨테이너 오케스트레이션 |
| 4순위 | Service Mesh (Istio) | 네트워크 레벨 관리 |

---

## 참고 자료

- [Spring Cloud for Microservices Compared to Kubernetes | Red Hat](https://developers.redhat.com/blog/2016/12/09/spring-cloud-for-microservices-compared-to-kubernetes)
- [A New Era Of Spring Cloud - Piotr's TechBlog](https://piotrminkowski.com/2020/05/01/a-new-era-of-spring-cloud/)
- [Spring Boot Integration - Temporal Documentation](https://docs.temporal.io/develop/java/spring-boot-integration)
- [Build a Reliable System in a Microservices World at Snap](https://eng.snap.com/build_a_reliable_system_in_a_microservices_world_at_snap)
- [Top 10 Spring Cloud Microservices Best Practices | Medium](https://medium.com/javarevisited/top-10-spring-cloud-microservices-best-practices-removed-deprecated-features-1f94b21ea549)
