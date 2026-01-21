# Session 1 - 2026-01-21

## 목표
프로젝트 초기 설정 및 방향 수립

## 진행 내용
- [x] 프로젝트 목적 및 방향 정의
- [x] 아키텍처 설계 (2레이어 구조)
- [x] 기술 스택 결정
- [x] CLAUDE.md 생성
- [x] docs 폴더 문서 구조 생성
- [x] 기술 스택 검증 (Spring Boot 4.0.1, Temporal GA 확인)
- [x] 프로젝트 면밀 검토 및 문서화
- [x] 아키텍처 결정 사항 문서 (DECISIONS.md) 생성
- [x] 검토 결과 문서 (REVIEW.md) 생성
- [x] 학습 경로 수정: Phase 2를 2-A(REST), 2-B(MQ)로 분리

## 결정 완료 사항
- [x] Saga 패턴: **Orchestration**
- [x] 서비스 통신: **동기 REST**
- [x] MQ: **Redis Stream**
- [x] DB: **공유 DB + 스키마 분리**
- [x] 서비스 구성: **4개** (Order, Inventory, Payment, Notification)
- [x] 테스트 전략: **단위 + 통합 (Testcontainers)**

## 메모
- Temporal Spring Boot Integration GA 출시됨 (2025-12-16)
- Temporal SDK의 Spring Boot 4 호환성은 Phase 3 전에 재확인 필요
- **학습 순서 변경 이유**: EDA/MQ를 잘 모르는 상태에서 MQ 기반 Saga 구현은 모순
- **새 학습 경로**: REST 기반 Saga로 분산 트랜잭션 어려움 체험 → MQ 추가 → Temporal 도입
- Notification Service는 Phase 2-B에서 MQ 이벤트 구독자로 구현

## 다음 세션 목표
- 멀티모듈 프로젝트 구조 설계 상세화
- Gradle 멀티모듈 설정 방법 학습
- Phase 1 시작: 멀티모듈 스켈레톤 생성
