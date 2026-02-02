# Spring Temporal 학습 프로젝트

> **중요**: 이 프로젝트는 학습 목적의 프로젝트입니다. 프로덕션 코드가 아닙니다.

## 프로젝트 목적
MSA/EDA 환경의 어려움 체험 후 Temporal 도입 효과 학습

## 학습 방식
- **Claude**: 가이드 역할 (따라할 수 있는 구체적인 가이드 제공)
- **사용자**: 가이드를 보고 직접 따라하며 학습

## Claude 행동 지침
- **구체적인 Step-by-Step 가이드 제공** (힌트가 아닌 직접 따라할 수 있는 형태)
- 파일 경로, 코드, 명령어를 명확하게 제시
- **각 작업에 대한 학습 지식 함께 제공**:
  - **What**: 이 작업이 무엇인지
  - **Why**: 왜 이 작업을 하는지 (목적, 필요성)
  - **Structure**: 어떤 구조인지 (파일 구조, 아키텍처)
  - **How**: 어떻게 동작하는지 (내부 동작 원리)
- 코드 리뷰 및 개선점 피드백 제공
- 트러블슈팅 시에도 구체적인 해결 방법 제시

## 현재 상태
- **Phase 2-A**: 동기 REST 기반 Saga (진행 중)
- 진행 상세: `docs/PROGRESS.md` 참조
- **목표 완료일**: 2026-02-08 (7일 집중)

## 학습 경로
1. Phase 1: 기반 구축 ✅
2. Phase 2-A: REST Saga + 동시성/장애 대응 + **테스트 전략** (진행 중)
3. Phase 2-B: MQ + Redis + Observability + **성능 테스트**
4. Phase 3: Temporal 연동
5. DevOps: CI/CD 파이프라인
6. 고도화: Core 라이브러리 (최후 목표)

## 모듈 구조
```
common/                 # 공통 (DTO, 이벤트, 예외)
service-order/          # 주문
service-inventory/      # 재고
service-payment/        # 결제
service-notification/   # 알림 (MQ 구독)
orchestrator-pure/      # 순수 구현
orchestrator-temporal/  # Temporal 구현
```

## 기술 스택
- Spring Boot 3.5.9 / Java 21 (Virtual Threads 지원)
- MySQL + JPA + Flyway (DB 마이그레이션)
- Redis + Redisson 3.52.0 (캐싱, 분산 락)
- Redis Stream (MQ)
- Temporal 1.32.0 + Spring Boot Starter (Phase 3)
- Resilience4j (재시도, 서킷 브레이커)
- Bean Validation (입력 검증)
- **Grafana Tempo** + Prometheus + Grafana + Loki + Alertmanager (Observability)
- **Pact** (Contract Testing)
- **k6** (성능 테스트)
- Testcontainers
- **GitHub Actions** (CI/CD)

## 핵심 결정 (25개)
- D001-D018: 기존 결정
- D019: 테스트 전략 확장 (Contract Testing)
- D020: Saga Isolation (Dirty Read/Lost Update 대응)
- D021: Redis 분산 락 심화 (10가지 함정)
- D022: 성능 테스트 (k6)
- D023: CI/CD 파이프라인 (GitHub Actions)
- D024: 분산 추적 현대화 (Zipkin → Tempo)
- D025: Virtual Threads 활성화

## 문서 위치
| 문서 | 용도 |
|------|------|
| `docs/PROGRESS.md` | 진행 현황 (세션 시작 시 확인) |
| `docs/architecture/DECISIONS.md` | 아키텍처 결정 (25개) |
| `docs/architecture/TECH-STACK.md` | 기술 스택 검증 |
| `docs/study/phase2a/` | Phase 2-A 학습 문서 (14개) |
| `docs/study/phase2b/` | Phase 2-B 학습 문서 (9개) |
| `docs/study/phase3/` | Phase 3 학습 문서 (3개) |
| `docs/study/devops/` | DevOps 학습 문서 (1개) |
| `docs/sessions/` | 세션별 기록 |
| `docs/TROUBLESHOOTING.md` | 트러블슈팅 |

## 보강된 학습 문서 (신규)
| 문서 | 내용 |
|------|------|
| `10-contract-testing.md` | Pact 기반 계약 테스트 |
| `11-saga-isolation.md` | Saga Dirty Read/Lost Update 해결 |
| `12-redis-lock-pitfalls.md` | 분산 락 10가지 함정 |
| `05-opentelemetry-tempo.md` | Grafana Tempo 분산 추적 |
| `09-performance-testing.md` | k6 부하 테스트 |
| `devops/01-github-actions.md` | CI/CD 파이프라인 |
