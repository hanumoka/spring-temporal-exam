# HTTP Test Files

IntelliJ IDEA HTTP Client를 사용한 API 테스트 파일입니다.

## 사용 방법

1. IntelliJ IDEA에서 `.http` 파일 열기
2. 각 요청 옆의 ▶ (실행) 버튼 클릭
3. 또는 `Ctrl + Enter`로 현재 요청 실행

## 파일 구성

| 파일 | 용도 | 실행 순서 |
|------|------|----------|
| `01-inventory-setup.http` | 테스트 데이터 준비 (상품/재고) | 1번 |
| `02-order-service.http` | Order Service 개별 테스트 | - |
| `03-payment-service.http` | Payment Service 개별 테스트 | - |
| `04-saga-orchestrator.http` | **Saga 통합 테스트** | 2번 |

## 테스트 순서

### 1. 인프라 실행
```bash
cd spring-temporal-exam-docker
docker-compose up -d
```

### 2. 서비스 실행 (4개 터미널)
```bash
# 터미널 1
./gradlew :service-order:bootRun

# 터미널 2
./gradlew :service-inventory:bootRun

# 터미널 3
./gradlew :service-payment:bootRun

# 터미널 4
./gradlew :orchestrator-pure:bootRun
```

### 3. 테스트 데이터 준비
`01-inventory-setup.http` 파일에서:
- "1. 상품 등록" 실행

### 4. Saga 테스트
`04-saga-orchestrator.http` 파일에서:
- "1. 주문 Saga 실행 (정상)" 실행
- 결과 확인

## 포트 정보

| 서비스 | 포트 |
|--------|------|
| Orchestrator | 8080 |
| Order Service | 8081 |
| Inventory Service | 8082 |
| Payment Service | 8083 |
| MySQL | 21306 |
| Redis | 21379 |

## 환경 변수

`http-client.env.json` 파일에서 환경별 호스트 설정 가능

## 팁

- 요청 사이에 `###`로 구분
- 응답은 하단 패널에서 확인
- History에서 이전 요청/응답 확인 가능
