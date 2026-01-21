# 아키텍처 결정 사항 (ADR)

## 결정 요약

| 번호 | 항목 | 결정 |
|------|------|------|
| D001 | Saga 패턴 | Orchestration |
| D002 | 서비스 통신 | 동기 REST |
| D003 | 메시지 큐 | Redis Stream (Phase 2-B) |
| D004 | DB 구성 | 공유 DB + 스키마 분리 |
| D005 | 서비스 구성 | 4개 (Order, Inventory, Payment, Notification) |
| D006 | 테스트 전략 | 단위 + 통합 (Testcontainers) |
| D007 | Spring Cloud | 미사용 (학습 범위 집중) |
| D008 | Kubernetes | 미사용 (Docker Compose로 대체) |
| D009 | Service Mesh | 미사용 (학습 범위 외) |

---

## D001. Saga 패턴 방식

**결정**: Orchestration

**근거**:
- Temporal이 Orchestration 방식이므로 순수 구현 → Temporal 전환이 자연스러움
- 디버깅 용이, 플로우 파악 쉬움

---

## D002. 서비스 간 통신 방식

**결정**: 동기 REST

**통신 구조**:
```
Orchestrator
  [1] POST /orders ──────────────> [Order Service]
  [2] POST /inventory/reserve ───> [Inventory Service]
  [3] POST /payments ────────────> [Payment Service]
  [4] PUT /orders/{id}/confirm ──> [Order Service]
```

---

## D003. 메시지 큐 선택

**결정**: Redis Stream

**사용 용도**: 이벤트 발행/구독 (Notification Service)

---

## D004. 데이터베이스 구성

**결정**: 공유 DB + 스키마 분리

```
MySQL
├── order_db
├── inventory_db
├── payment_db
└── notification_db
```

---

## D005. 서비스 구성

| 서비스 | 역할 | 통신 방식 |
|--------|------|----------|
| Order Service | 주문 생성/확정/취소 | REST |
| Inventory Service | 재고 예약/확정/복구 | REST |
| Payment Service | 결제 처리/환불 | REST |
| Notification Service | 알림 발송 | MQ 구독 |

---

## D006. 테스트 전략

| 수준 | 도구 | 범위 |
|------|------|------|
| 단위 테스트 | JUnit 5, Mockito | 개별 클래스 |
| 통합 테스트 | Testcontainers | DB, MQ 연동 |

---

## D100. 2레이어 아키텍처

```
┌─────────────────────────────────────────────┐
│           Orchestration Layer               │
│  ┌──────────────┐    ┌──────────────────┐  │
│  │ Pure 구현    │ or │ Temporal 구현    │  │
│  └──────────────┘    └──────────────────┘  │
└─────────────────────────────────────────────┘
                    │ REST
                    ▼
┌─────────────────────────────────────────────┐
│         Business Service Layer              │
│  ┌───────┐ ┌─────────┐ ┌───────┐ ┌──────┐  │
│  │ Order │ │Inventory│ │Payment│ │Notif.│  │
│  └───────┘ └─────────┘ └───────┘ └──────┘  │
└─────────────────────────────────────────────┘
```

---

## D101. 학습 경로

```
Phase 2-A: 동기 REST 기반 Saga (MQ 없이)
    ↓ 분산 트랜잭션의 어려움 체험
Phase 2-B: MQ 이벤트 추가 + Redis 학습
    ↓ EDA의 복잡성 인지
Phase 3: Temporal 연동
    → "왜 Temporal이 필요한지" 체감
```

---

## D007. Spring Cloud 미사용

**결정**: 미사용

**고려된 컴포넌트**:
- Spring Cloud Gateway (API 게이트웨이)
- Eureka (서비스 디스커버리)
- Spring Cloud Config (설정 관리)
- Spring Cloud LoadBalancer (로드밸런싱)

**미사용 이유**:

```
1. 학습 목표 집중
   └── Temporal과 분산 트랜잭션이 핵심 주제
   └── Spring Cloud는 별도의 큰 학습 주제

2. 복잡도 관리
   └── 핵심 개념 이해에 방해될 수 있음
   └── localhost 직접 접근으로 충분

3. Temporal과의 역할 중복
   └── 서비스 간 통신은 Temporal Activity로 처리
   └── 재시도/타임아웃은 Temporal이 담당
```

**대안**:
- 서비스 디스커버리: 직접 URL 지정 (localhost:808x)
- 설정 관리: Spring Profiles + 환경변수

**향후 확장**: 실무 적용 시 필요에 따라 추가 가능

---

## D008. Kubernetes 미사용

**결정**: 미사용 (Docker Compose로 대체)

**미사용 이유**:

```
1. 학습 환경 단순화
   └── 로컬 개발 환경에서 K8s는 과도함
   └── Docker Compose로 충분한 컨테이너 환경 제공

2. 인프라 학습과 분리
   └── K8s는 DevOps 영역
   └── 애플리케이션 개발에 집중

3. Temporal과 독립적
   └── Temporal은 K8s 없이도 동작
   └── Docker Compose에서 충분히 학습 가능
```

**K8s가 대체하는 Spring Cloud 기능**:

| Spring Cloud | Kubernetes |
|--------------|------------|
| Eureka | K8s Service + DNS |
| Config Server | ConfigMap/Secret |
| Ribbon | K8s Service (서버사이드 LB) |
| Gateway | Ingress Controller |

**향후 확장**: 프로덕션 배포 시 K8s 전환 권장

---

## D009. Service Mesh 미사용

**결정**: 미사용

**고려된 기술**: Istio, Linkerd

**미사용 이유**:

```
1. 학습 범위 외
   └── Service Mesh는 인프라 레벨 기술
   └── K8s 없이는 의미 없음

2. 복잡도
   └── Istio 학습 곡선이 가파름
   └── 현재 학습 목표와 무관

3. Temporal로 충분
   └── 비즈니스 레벨 오케스트레이션은 Temporal
   └── 네트워크 레벨 기능은 현재 불필요
```

**Service Mesh가 제공하는 기능**:
- mTLS 자동 암호화
- 서킷 브레이커 (플랫폼 레벨)
- 트래픽 관리 (카나리, A/B)
- 분산 추적 자동 수집

**향후 확장**: 대규모 프로덕션 환경에서 고려

---

## 관련 문서

- [MSA 아키텍처 선택 가이드](./MSA-ARCHITECTURE-GUIDE.md) - 환경별 아키텍처 비교
