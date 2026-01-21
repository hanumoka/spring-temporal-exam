# 기술 스택 검증

## 검증 완료 항목

| 항목 | 상태 | 검증 내용 |
|------|------|----------|
| Spring Boot 4.0.1 | ✅ | 2025년 12월 18일 출시, Spring Framework 7 기반 |
| Java 21 | ✅ | Spring Boot 4는 Java 17~25 지원 |
| `spring-boot-starter-webmvc` | ✅ | Spring Boot 4에서 `web` → `webmvc`로 변경됨 |
| Temporal Spring Boot Integration | ✅ | 2025년 12월 16일 GA 출시 |

## 참고 링크

- [Spring Boot 4.0.1 릴리즈](https://spring.io/blog/2025/12/18/spring-boot-4-0-1-available-now/)
- [Spring Boot 4.0 마이그레이션 가이드](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Temporal Spring Boot Integration GA](https://community.temporal.io/t/spring-boot-integration-is-now-in-ga/18770)

## 주의 사항

### Temporal + Spring Boot 4 호환성

- Temporal Spring Boot Starter는 **Spring Boot 3.x** 기준으로 문서화됨
- Spring Boot 4.0과의 호환성이 공식적으로 명시되지 않음
- [공식 샘플](https://github.com/temporalio/spring-boot-demo)이 Spring Boot 3 기준

**권장 조치**: Phase 3 시작 전 Temporal SDK의 Spring Boot 4 지원 여부 재확인 필요

### build.gradle 테스트 의존성

```gradle
testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'
```

빌드 및 테스트 실행 시 정상 동작 여부 확인 필요

## MQ 선택 비교: Redis Stream vs Kafka

| 구분 | Redis Stream | Kafka |
|------|-------------|-------|
| 설정 복잡도 | 낮음 | 중간~높음 |
| 지연 시간 | 매우 낮음 (ms 이하) | 낮음 |
| 메시지 내구성 | 설정 필요 | 기본 제공 |
| 확장성 | 중간 | 높음 |
| 학습 곡선 | 낮음 | 중간 |
| Docker 구성 | 간단 (Redis만) | 복잡 (ZK + Broker) |

**선택**: Redis Stream (설정 간단, 빠른 학습 가능)

## Saga 패턴 비교

| 구분 | Orchestration | Choreography |
|------|---------------|--------------|
| 제어 방식 | 중앙 오케스트레이터 | 분산 (이벤트 기반) |
| 디버깅 | 용이 | 어려움 |
| 모니터링 | 중앙 집중 가능 | 분산 추적 필요 |
| 복잡한 플로우 | 적합 | 부적합 |

**선택**: Orchestration (플로우 이해 용이, Temporal 전환 자연스러움)
