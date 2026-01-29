# 트러블슈팅 기록

프로젝트 진행 중 발생한 문제와 해결 방법을 기록합니다.

---

## 문제 기록 템플릿

```markdown
## [카테고리] 문제 제목

**날짜**: YYYY-MM-DD

**환경**:
- OS:
- Java:
- Spring Boot:

**증상**:
문제 상황 설명

**원인**:
원인 분석

**해결 방법**:
해결 과정 설명

**참고 자료**:
- 링크
```

---

## 기록된 문제들

### [Spring] Jackson 역직렬화 오류 - Type definition error

**날짜**: 2026-01-30

**환경**:
- Spring Boot 3.5.9
- RestClient (Spring 6.1+)

**증상**:
```
"message": "Type definition error: [simple type, class com.hanumoka.common.dto.ApiResponse]"
```

Orchestrator에서 다른 서비스 호출 후 응답을 `ApiResponse.class`로 역직렬화할 때 오류 발생

**원인**:
DTO 클래스에 Jackson이 역직렬화할 수 있는 구조가 없음

```java
// 문제 코드
@Builder
@Getter
public class ApiResponse<T> {
    // @NoArgsConstructor 없음 → 기본 생성자 없음
    // @Setter 없음 → 필드 값 주입 불가
}
```

Jackson 역직렬화 과정:
1. 기본 생성자로 빈 객체 생성
2. Setter로 필드 값 주입
→ 둘 다 없으면 실패

**해결 방법**:
```java
@Builder
@Getter
@Setter            // 추가
@NoArgsConstructor // 추가
@AllArgsConstructor // 추가 (Builder와 함께 사용)
public class ApiResponse<T> {
```

`ApiResponse.java`와 `ErrorInfo.java` 모두 수정 필요

**적용 파일**:
- `common/src/main/java/com/hanumoka/common/dto/ApiResponse.java`
- `common/src/main/java/com/hanumoka/common/dto/ErrorInfo.java`

**참고 자료**:
- Jackson은 기본적으로 `NoArgsConstructor + Setter` 조합 사용
- 대안: `@JsonCreator` + `@JsonProperty` 또는 Builder 패턴 설정

---

---

## 카테고리

- `[Build]` - Gradle, 빌드 관련
- `[Config]` - 설정 관련
- `[Docker]` - Docker, Docker Compose 관련
- `[DB]` - 데이터베이스 관련
- `[MQ]` - 메시지 큐 관련
- `[Temporal]` - Temporal 관련
- `[Spring]` - Spring Framework 관련
- `[Test]` - 테스트 관련
