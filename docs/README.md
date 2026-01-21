# Spring Temporal 연동 예제 프로젝트

MSA 환경에서 EDA 적용 시 발생하는 어려움을 해결하기 위한 학습 프로젝트입니다.

## 학습 대상

| 분야 | 기술 |
|------|------|
| EDA | Saga 패턴 (Orchestration), Outbox 패턴 |
| Temporal | Workflow Engine |
| Redis | 기본 자료구조, 캐싱 |
| Redis Stream | 메시지 큐, Consumer Group |
| Redisson | 분산 락, Spring 연동 |
| 동시성 제어 | 분산 락, 낙관적 락, 멱등성 |
| 장애 대응 | Resilience4j |
| 입력 검증 | Bean Validation |
| DB 관리 | Flyway 마이그레이션 |
| 설정 관리 | Spring Profiles |
| Observability | MDC, OpenTelemetry/Zipkin |
| 모니터링 | Actuator, Micrometer, Prometheus, Grafana |
| 로그 수집 | Loki |
| 알람 | Alertmanager |

## 비즈니스 시나리오

```
[고객] → [주문] → [재고 차감] → [결제] → [주문 확정]
                      ↓            ↓
                 (실패 시 복구)  (실패 시 환불)
```

## 학습 경로

1. **Phase 1**: 기반 구축 (멀티모듈, 인프라)
2. **Phase 2-A**: REST 기반 Saga + 동시성/장애 대응
3. **Phase 2-B**: MQ 추가 + Redis + Observability
4. **Phase 3**: Temporal 연동

## 기술 스택

| 구분 | 기술 |
|------|------|
| Framework | Spring Boot 4.0.1 |
| Language | Java 21 |
| Database | MySQL (공유 DB + 스키마 분리) |
| Cache/Lock | Redis + Redisson |
| Message Queue | Redis Stream |
| Workflow | Temporal |

## 모듈 구조

```
spring-temporal-exam/
├── common/                 # 공통 모듈
├── service-order/          # 주문 서비스
├── service-inventory/      # 재고 서비스
├── service-payment/        # 결제 서비스
├── service-notification/   # 알림 서비스
├── orchestrator-pure/      # 순수 오케스트레이터
└── orchestrator-temporal/  # Temporal 오케스트레이터
```

## 문서 목록

| 문서 | 설명 |
|------|------|
| [PROGRESS.md](./PROGRESS.md) | 진행 현황 |
| [architecture/DECISIONS.md](./architecture/DECISIONS.md) | 아키텍처 결정 사항 |
| [architecture/TECH-STACK.md](./architecture/TECH-STACK.md) | 기술 스택 검증 |
| [sessions/](./sessions/) | 세션별 기록 |
| [TROUBLESHOOTING.md](./TROUBLESHOOTING.md) | 트러블슈팅 |
