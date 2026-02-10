# Phase 3: Temporal 학습 커리큘럼

> **목표**: Temporal의 개념부터 Spring Boot 연동, 프로덕션 운영까지 완전 이해
> **구성**: 15개 문서, 5단계 학습 경로
> **총 분량**: ~8,200줄 (기존 14,500줄에서 43% 축소, 중복 제거)

---

## 학습 경로

### Level 1: 기초 이해 (왜 & 뭔지 & 직접 확인)

| # | 문서 | 핵심 질문 | 줄 수 |
|---|------|----------|-------|
| 01 | [왜 Temporal이 필요한가?](./01-why-temporal.md) | Phase 2-A의 고통을 Temporal이 어떻게 해결? | ~490 |
| 02 | [핵심 개념 5가지](./02-core-concepts.md) | Workflow, Activity, Worker, Task Queue, Server? | ~600 |
| 02-A | [로컬 환경 구축과 Web UI 실습](./02-A-local-setup-and-ui.md) | Docker Compose로 직접 띄워보고 눈으로 확인! | ~920 |

### Level 2: 내부 동작 (어떻게 작동하는지)

| # | 문서 | 핵심 질문 | 줄 수 |
|---|------|----------|-------|
| 03 | [Durable Execution과 Event History](./03-durable-execution.md) | 서버가 죽어도 어떻게 복구? | ~590 |
| 04 | [Worker와 Event Flow](./04-worker-event-flow.md) | Worker 내부에서 무슨 일이? | ~470 |
| 05 | [재시도 정책과 타임아웃](./05-retry-timeout.md) | 실패하면 어떻게 재시도? | ~450 |

### Level 3: 통신 & 심화

| # | 문서 | 핵심 질문 | 줄 수 |
|---|------|----------|-------|
| 06 | [Signal, Query, Update](./06-signal-query-update.md) | 실행 중인 Workflow와 어떻게 소통? | ~530 |
| 07 | [심화 기능](./07-advanced-topics.md) | Timer, Child WF, Continue-As-New? | ~550 |

### Level 4: Spring 실전

| # | 문서 | 핵심 질문 | 줄 수 |
|---|------|----------|-------|
| 08 | [Spring Boot 연동](./08-spring-integration.md) | Spring에서 Temporal 어떻게 설정? | ~610 |
| 09 | [Saga와 Temporal](./09-saga-with-temporal.md) | Phase 2-A Saga를 Temporal로 전환? | ~500 |
| 10 | [MSA 아키텍처 흐름도](./10-msa-architecture.md) | 전체 시스템이 어떻게 연결? | ~380 |
| 11 | [Activity 설계 가이드](./11-activity-design.md) | 멱등성, 동시성, 격리는 누가 책임? | ~730 |

### Level 5: 운영

| # | 문서 | 핵심 질문 | 줄 수 |
|---|------|----------|-------|
| 12 | [한계와 Phase 2 기술 조합](./12-limitations-combo.md) | Temporal이 못하는 건 뭔가? | ~490 |
| 13 | [프로덕션 가이드](./13-production.md) | Versioning, 배포, 모니터링? | ~480 |
| 14 | [FAQ와 트러블슈팅](./14-faq-troubleshooting.md) | 자주 하는 실수와 해결법? | ~410 |

---

## 권장 학습 순서

```
Day 1: 01 → 02 → 02-A  (기초 + 개념 + 로컬 환경 실습)
Day 2: 03 → 04 → 05     (Durable Execution + Worker + Retry)
Day 3: 06 → 07 → 08     (Signal + 심화 + Spring 연동)
Day 4: 09 → 10 → 11     (Saga + 아키텍처 + Activity 설계)
Day 5: 12 → 13 → 14     (한계 + 운영 + FAQ)
```

---

## 원본 문서

리팩토링 이전 원본 문서는 `_archive/` 폴더에 보관되어 있습니다.
