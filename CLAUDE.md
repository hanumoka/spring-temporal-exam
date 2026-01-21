# Spring Temporal 학습 프로젝트

## 프로젝트 목적
MSA/EDA 환경의 어려움 체험 후 Temporal 도입 효과 학습

## 학습 방식
- **Claude**: 코칭/가이드 역할
- **사용자**: 직접 코딩

## 현재 상태
- **Phase 1**: 기반 구축 (대기)
- 진행 상세: `docs/PROGRESS.md` 참조

## 학습 경로
1. Phase 1: 기반 구축
2. Phase 2-A: REST Saga + 동시성/장애 대응
3. Phase 2-B: MQ + Redis + Observability
4. Phase 3: Temporal 연동

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
- Spring Boot 4.0.1 / Java 21
- MySQL + JPA + Flyway (DB 마이그레이션)
- Redis + Redisson (캐싱, 분산 락)
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
