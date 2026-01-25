# 실습 가이드

이 폴더는 각 Phase별 **실습 가이드**를 포함합니다.

## 사용 방법

1. **개념 학습**: `docs/study/` 폴더의 학습 문서를 먼저 읽습니다
2. **실습 진행**: `docs/practice/` 폴더의 가이드를 따라 직접 구현합니다
3. **검증**: 각 Step의 체크리스트와 검증 방법으로 확인합니다

---

## 폴더 구조

```
docs/
├── study/           # 개념 학습 문서 (이론)
│   ├── phase1/
│   ├── phase2a/
│   ├── phase2b/
│   └── phase3/
│
└── practice/        # 실습 가이드 (실전)
    ├── phase1/
    │   ├── step1-multimodule-setup.md
    │   ├── step2-common-module.md
    │   └── ... (추가 예정)
    └── ... (추가 예정)
```

---

## Phase 1 실습 가이드

| Step | 제목 | 사전 학습 | 상태 |
|------|------|----------|------|
| 1 | [멀티모듈 프로젝트 구조 설계](./phase1/step1-multimodule-setup.md) | 01-gradle-multimodule.md | 작성 완료 |
| 2 | [공통 모듈 (common) 구성](./phase1/step2-common-module.md) | 01-gradle-multimodule.md | 작성 완료 |
| 3 | Docker Compose 인프라 구성 | 04-docker-compose.md | 작성 예정 |
| 4 | Flyway DB 마이그레이션 | 02-flyway.md | 작성 예정 |
| 5 | Spring Profiles 설정 | 03-spring-profiles.md | 작성 예정 |
| 6 | 데이터 모델 설계 | - | 작성 예정 |
| 7 | 서비스 모듈 스켈레톤 | - | 작성 예정 |

---

## 실습 원칙

- **힌트 우선**: 정답 코드 대신 힌트와 방향을 제시합니다
- **자가 검증**: 각 Step마다 검증 방법을 제공합니다
- **트러블슈팅**: 흔한 문제와 해결책을 안내합니다
- **자가 점검**: 이해도 확인을 위한 질문을 포함합니다
