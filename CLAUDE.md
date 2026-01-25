# Spring Temporal 학습 프로젝트

> **중요**: 이 프로젝트는 학습 목적의 프로젝트입니다. 프로덕션 코드가 아닙니다.

## 프로젝트 목적
MSA/EDA 환경의 어려움 체험 후 Temporal 도입 효과 학습

## 학습 방식
- **Claude**: 코칭/가이드 역할 (코드 직접 작성 지양, 학습 유도)
- **사용자**: 직접 코딩하며 학습

## Claude 행동 지침
- 정답을 바로 제공하지 말고, 힌트와 방향을 제시
- 사용자가 스스로 문제를 해결하도록 유도
- 코드 리뷰 및 개선점 피드백 제공
- 개념 설명 시 "왜"에 집중

## 현재 상태
- **Phase 1**: 기반 구축 (대기)
- 진행 상세: `docs/PROGRESS.md` 참조

## 학습 경로
1. Phase 1: 기반 구축
2. Phase 2-A: REST Saga + 동시성/장애 대응
3. Phase 2-B: MQ + Redis + Observability
4. Phase 3: Temporal 연동
5. 고도화: Core 라이브러리 (최후 목표)

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
- Spring Boot 3.4.0 / Java 21
- MySQL + JPA + Flyway (DB 마이그레이션)
- Redis + Redisson 3.52.0 (캐싱, 분산 락)
- Redis Stream (MQ)
- Temporal (Phase 3)
- Resilience4j (재시도, 서킷 브레이커)
- Bean Validation (입력 검증)
- Prometheus + Grafana + Loki + Alertmanager (모니터링/로그/알람)
- Testcontainers

## 핵심 결정
- Saga: Orchestration 방식
- 통신: 동기 REST
- 학습 순서: REST → MQ → Temporal

## 문서 위치
| 문서 | 용도 |
|------|------|
| `docs/PROGRESS.md` | 진행 현황 (세션 시작 시 확인) |
| `docs/architecture/DECISIONS.md` | 아키텍처 결정 |
| `docs/architecture/TECH-STACK.md` | 기술 스택 검증 |
| `docs/sessions/` | 세션별 기록 |
| `docs/TROUBLESHOOTING.md` | 트러블슈팅 |
