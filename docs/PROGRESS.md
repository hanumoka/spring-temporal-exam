# 프로젝트 진행 현황

## 현재 상태

- **현재 Phase**: Phase 1 - 기반 구축
- **마지막 업데이트**: 2026-01-21

---

## Phase 1: 기반 구축

| 항목 | 상태 |
|------|------|
| 멀티모듈 프로젝트 구조 설계 | 대기 |
| 공통 모듈 (common) 구성 | 대기 |
| 데이터 모델 설계 | 대기 |
| 각 서비스 모듈 스켈레톤 생성 | 대기 |
| Docker Compose 인프라 구성 | 대기 |
| Flyway DB 마이그레이션 설정 | 대기 |
| Spring Profiles 환경별 설정 | 대기 |

## Phase 2-A: 동기 REST 기반 Saga

| 항목 | 상태 |
|------|------|
| Order 서비스 도메인/API 구현 | 대기 |
| Inventory 서비스 도메인/API 구현 | 대기 |
| Payment 서비스 도메인/API 구현 | 대기 |
| 오케스트레이터 REST 호출 구현 | 대기 |
| 보상 트랜잭션 구현 | 대기 |
| 글로벌 예외 처리 | 대기 |
| Resilience4j 재시도/타임아웃 | 대기 |
| MDC 로깅 | 대기 |
| 재고 차감 분산 락 (Redisson) | 대기 |
| 낙관적 락 (JPA @Version) | 대기 |
| 멱등성 처리 (Idempotency Key) | 대기 |
| Bean Validation 입력 검증 | 대기 |

## Phase 2-B: MQ + Redis + Observability

| 항목 | 상태 |
|------|------|
| Redis 기초 학습 | 대기 |
| Redis Stream 학습 | 대기 |
| Redisson 학습 | 대기 |
| Notification 서비스 구현 | 대기 |
| OpenTelemetry/Zipkin 연동 | 대기 |
| Spring Boot Actuator 설정 | 대기 |
| Micrometer + Prometheus 연동 | 대기 |
| Grafana 대시보드 구성 | 대기 |
| Loki 로그 수집 연동 | 대기 |
| Alertmanager 장애 알림 설정 | 대기 |
| Outbox 패턴 (이벤트 발행 신뢰성) | 대기 |

## Phase 3: Temporal 연동

| 항목 | 상태 |
|------|------|
| Temporal 로컬 인프라 구성 | 대기 |
| Workflow/Activity 정의 | 대기 |
| 기존 로직 Temporal 전환 | 대기 |

---

## 세션 기록

세션별 상세 기록은 `sessions/` 폴더 참조:
- [Session 1 - 2026-01-21](./sessions/SESSION-001.md): 프로젝트 초기 설정

---

## 세션 템플릿

새 세션 파일 생성 시: `sessions/SESSION-NNN.md`

```markdown
# Session N - YYYY-MM-DD

## 목표

## 진행 내용
- [ ]

## 메모

## 다음 세션 목표
```
