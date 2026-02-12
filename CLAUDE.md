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

## 학습 가이드 세션 형식 (반드시 이 형식으로 진행)

학습 문서(`docs/study/phase3/`)를 가이드할 때 아래 형식을 **항상** 따른다:

### 세션 구성 (5단계)
```
1. 헤더: 세션 번호, 문서명, 목표, 소요시간
2. 핵심 포인트 N개: 문서 내용을 압축하여 핵심만 강조
   - ASCII 다이어그램, 표, 코드 블록 활용
   - 가독성 최우선 (터미널에서 읽기 쉽게)
3. Insight: 왜 중요한지, 실무/아키텍처 연결
4. 이해도 확인: 자유 답변형 질문 2~3개
   - Q1, Q2, Q3 형태로 번호 매김
   - 선택형이 아닌 본인 말로 설명하는 방식
5. 세션 마무리: 핵심 키워드 박스 + 다음 세션 예고
```

### 핵심 포인트 작성 규칙
- 문서 전체를 읽고 **5개 이내**의 핵심 포인트로 압축
- 각 포인트는 `## Point N/M: 제목` 형태
- 긴 설명 대신 **표, 비교, 다이어그램**으로 시각화
- 코드 예제는 핵심 부분만 발췌 (전체 복사 금지)

### 이해도 확인 규칙
- **자유 답변형** (선택형 금지)
- "본인 말로 설명해보세요" / "구분해보세요" 형태
- 사용자 답변 후 → 간결한 피드백 → 다음 세션 진행

### 진행 추적
- 각 세션 시작 시 현재 진행 상황 표시
- 형태: `✅ 01 → ✅ 02 → ⏳ 02-A → 03 → 04 → ...`

## 현재 상태
- **Phase 3**: Temporal 학습 (진행 중)
- Phase 3 학습 커리큘럼: `docs/study/phase3/README.md` 참조
- 진행 상세: `docs/PROGRESS.md` 참조

## 학습 경로
1. Phase 1: 기반 구축 ✅
2. Phase 2-A: REST Saga + 동시성/장애 대응 + **테스트 전략** ✅
3. Phase 2-B: MQ + Redis + Observability + **성능 테스트**
4. Phase 3: Temporal 연동 (진행 중)
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
- Temporal 1.26.0 + Spring Boot Starter (Phase 3)
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
| `docs/study/phase3/` | Phase 3 학습 문서 (15개, 리팩토링 완료) |
| `docs/study/phase3/README.md` | Phase 3 학습 커리큘럼 (5단계, 15문서) |
| `docs/study/phase3/_archive/` | 리팩토링 이전 원본 문서 백업 |
| `docs/study/devops/` | DevOps 학습 문서 (1개) |
| `docs/sessions/` | 세션별 기록 |
| `docs/TROUBLESHOOTING.md` | 트러블슈팅 |

## Phase 3 학습 커리큘럼 (Temporal)
```
Day 1: 01 → 02 → 02-A   (기초 + 개념 + 로컬 환경 실습)
Day 2: 03 → 04 → 05      (Durable Execution + Worker + Retry)
Day 3: 06 → 07 → 08      (Signal + 심화 + Spring 연동)
Day 4: 09 → 10 → 11      (Saga + 아키텍처 + Activity 설계)
Day 5: 12 → 13 → 14      (한계 + 운영 + FAQ)
```
상세: `docs/study/phase3/README.md` 참조
