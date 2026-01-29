# Docker Compose - 로컬 인프라 구성

## 이 문서에서 배우는 것

- Docker와 Docker Compose의 기본 개념
- docker-compose.yml 파일 작성 방법
- 개발에 필요한 인프라 구성 (MySQL, Redis, Zipkin 등)
- 자주 사용하는 Docker Compose 명령어

---

## 1. Docker와 Docker Compose란?

### Docker란?

**Docker**는 애플리케이션을 **컨테이너**라는 격리된 환경에서 실행하는 플랫폼입니다.

```
[기존 방식: 직접 설치]
"MySQL 설치해야 해"
→ 다운로드 → 설치 → 설정 → 환경변수 → 시작 (30분+)
→ 버전 충돌, 설정 꼬임, 삭제 어려움 😩

[Docker 방식]
"MySQL 필요해"
→ docker run mysql (10초)
→ 깔끔한 격리 환경, 삭제도 한 줄 😎
```

### 컨테이너 vs 가상머신

```
┌─────────────────────────────────────────────────────────────┐
│                       가상머신 (VM)                          │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐                       │
│  │  App A  │ │  App B  │ │  App C  │                       │
│  ├─────────┤ ├─────────┤ ├─────────┤                       │
│  │ Guest OS│ │ Guest OS│ │ Guest OS│  ← 각각 전체 OS 필요  │
│  └─────────┘ └─────────┘ └─────────┘     (무거움)          │
│  ┌─────────────────────────────────────────────┐           │
│  │              Hypervisor                      │           │
│  └─────────────────────────────────────────────┘           │
│  ┌─────────────────────────────────────────────┐           │
│  │              Host OS                         │           │
│  └─────────────────────────────────────────────┘           │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                       컨테이너 (Docker)                      │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐                       │
│  │  App A  │ │  App B  │ │  App C  │                       │
│  └─────────┘ └─────────┘ └─────────┘                       │
│  ┌─────────────────────────────────────────────┐           │
│  │              Docker Engine                   │ ← 가벼움  │
│  └─────────────────────────────────────────────┘           │
│  ┌─────────────────────────────────────────────┐           │
│  │              Host OS                         │           │
│  └─────────────────────────────────────────────┘           │
└─────────────────────────────────────────────────────────────┘
```

### Docker Compose란?

**Docker Compose**는 여러 컨테이너를 한 번에 관리하는 도구입니다.

```
[Docker만 사용]
docker run mysql ...
docker run redis ...
docker run zipkin ...
→ 매번 긴 명령어 입력 😩
→ 컨테이너 간 네트워크 설정 복잡

[Docker Compose 사용]
docker-compose up
→ 모든 컨테이너 한 번에 시작 😎
→ 네트워크 자동 구성
```

---

## 2. Docker Compose 설치 확인

### 설치 확인

```bash
# Docker 버전 확인
docker --version
# Docker version 24.0.7, build afdd53b

# Docker Compose 버전 확인
docker compose version
# Docker Compose version v2.23.3
```

**참고**: Docker Desktop을 설치하면 Docker Compose가 함께 설치됩니다.

---

## 3. docker-compose.yml 기본 구조

### 파일 위치

```
spring-temporal-exam/
├── docker-compose.yml    ← 프로젝트 루트에 위치
├── build.gradle
├── settings.gradle
└── ...
```

### 기본 문법

```yaml
# docker-compose.yml
version: '3.8'  # Compose 파일 버전 (선택적)

services:       # 컨테이너 정의
  mysql:        # 서비스 이름 (컨테이너 이름)
    image: mysql:8.0
    # ... 설정

  redis:
    image: redis:7-alpine
    # ... 설정

volumes:        # 볼륨 정의 (데이터 영속화)
  mysql-data:

networks:       # 네트워크 정의 (선택적)
  app-network:
```

---

## 4. 우리 프로젝트의 docker-compose.yml

### 전체 구성

```yaml
# docker-compose.yml
version: '3.8'

services:
  # ============================================
  # MySQL - 데이터베이스
  # ============================================
  mysql:
    image: mysql:8.0
    container_name: spring-temporal-mysql
    environment:
      MYSQL_ROOT_PASSWORD: password
      MYSQL_CHARACTER_SET_SERVER: utf8mb4
      MYSQL_COLLATION_SERVER: utf8mb4_unicode_ci
      TZ: Asia/Seoul
    ports:
      - "3306:3306"
    volumes:
      - mysql-data:/var/lib/mysql
      - ./docker/mysql/init:/docker-entrypoint-initdb.d  # 초기화 스크립트
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_unicode_ci
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - app-network

  # ============================================
  # Redis - 캐시, 분산 락, MQ
  # ============================================
  redis:
    image: redis:7-alpine
    container_name: spring-temporal-redis
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data
    command: redis-server --appendonly yes  # AOF 영속성
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - app-network

  # ============================================
  # Zipkin - 분산 추적
  # ============================================
  zipkin:
    image: openzipkin/zipkin:latest
    container_name: spring-temporal-zipkin
    ports:
      - "9411:9411"
    environment:
      - STORAGE_TYPE=mem  # 메모리 저장 (개발용)
    networks:
      - app-network

  # ============================================
  # Prometheus - 메트릭 수집
  # ============================================
  prometheus:
    image: prom/prometheus:latest
    container_name: spring-temporal-prometheus
    ports:
      - "9090:9090"
    volumes:
      - ./docker/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus-data:/prometheus
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.path=/prometheus'
    networks:
      - app-network

  # ============================================
  # Grafana - 대시보드
  # ============================================
  grafana:
    image: grafana/grafana:latest
    container_name: spring-temporal-grafana
    ports:
      - "3000:3000"
    environment:
      - GF_SECURITY_ADMIN_USER=admin
      - GF_SECURITY_ADMIN_PASSWORD=admin
      - GF_USERS_ALLOW_SIGN_UP=false
    volumes:
      - grafana-data:/var/lib/grafana
      - ./docker/grafana/provisioning:/etc/grafana/provisioning
    depends_on:
      - prometheus
    networks:
      - app-network

  # ============================================
  # Loki - 로그 수집
  # ============================================
  loki:
    image: grafana/loki:latest
    container_name: spring-temporal-loki
    ports:
      - "3100:3100"
    volumes:
      - ./docker/loki/loki-config.yml:/etc/loki/local-config.yaml
      - loki-data:/loki
    command: -config.file=/etc/loki/local-config.yaml
    networks:
      - app-network

  # ============================================
  # Alertmanager - 알람 (Phase 2-B)
  # ============================================
  alertmanager:
    image: prom/alertmanager:latest
    container_name: spring-temporal-alertmanager
    ports:
      - "9093:9093"
    volumes:
      - ./docker/alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml
    command:
      - '--config.file=/etc/alertmanager/alertmanager.yml'
    networks:
      - app-network

# ============================================
# 볼륨 정의 (데이터 영속화)
# ============================================
volumes:
  mysql-data:
  redis-data:
  prometheus-data:
  grafana-data:
  loki-data:

# ============================================
# 네트워크 정의
# ============================================
networks:
  app-network:
    driver: bridge
```

---

## 5. 각 서비스 상세 설명

### 5.1 MySQL 설정

```yaml
mysql:
  image: mysql:8.0                    # 사용할 이미지
  container_name: spring-temporal-mysql  # 컨테이너 이름
  environment:                         # 환경 변수
    MYSQL_ROOT_PASSWORD: password      # root 비밀번호
  ports:
    - "3306:3306"                      # 호스트:컨테이너 포트 매핑
  volumes:
    - mysql-data:/var/lib/mysql        # 데이터 영속화
```

**volumes 설명**:
```
mysql-data:/var/lib/mysql

mysql-data   → 호스트의 볼륨 이름 (Docker가 관리)
/var/lib/mysql → 컨테이너 내부 경로 (MySQL 데이터 저장 위치)

컨테이너를 삭제해도 데이터는 볼륨에 보존됨!
```

### 5.2 MySQL 초기화 스크립트

```
docker/
└── mysql/
    └── init/
        └── 01-init-databases.sql
```

```sql
-- docker/mysql/init/01-init-databases.sql
-- 서비스별 데이터베이스 생성

CREATE DATABASE IF NOT EXISTS order_db
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS inventory_db
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS payment_db
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS notification_db
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

-- 확인
SHOW DATABASES;
```

**동작 원리**:
- `/docker-entrypoint-initdb.d` 폴더의 `.sql` 파일은 컨테이너 최초 시작 시 자동 실행
- 파일명 순서대로 실행 (01-, 02-, ...)

### 5.3 Redis 설정

```yaml
redis:
  image: redis:7-alpine               # Alpine 경량 이미지
  command: redis-server --appendonly yes  # AOF 영속성 활성화
  volumes:
    - redis-data:/data                # 데이터 영속화
```

**AOF (Append Only File)**:
- 모든 쓰기 명령을 로그로 저장
- 컨테이너 재시작 시 데이터 복구 가능

### 5.4 Healthcheck (심화)

#### 왜 필요한가?

**핵심 문제**: 컨테이너 상태 ≠ 서비스 상태

```
컨테이너 상태: Running ✅
실제 서비스: 아직 준비 안됨 ❌
```

| 예시 | 컨테이너 상태 | 실제 상태 |
|------|-------------|----------|
| MySQL 시작 직후 | Running | init.sql 실행 중 |
| Redis 시작 직후 | Running | RDB 파일 로딩 중 |
| Spring Boot 시작 | Running | Bean 초기화 중 |

**Healthcheck가 해결하는 것**:
- 컨테이너 내부 서비스가 **실제로 요청을 받을 수 있는 상태인지** 확인
- 다른 서비스가 의존할 때 **준비될 때까지 대기** 가능
- 운영 중 **장애 감지** 및 자동 복구 트리거

#### 동작 방식

```yaml
healthcheck:
  test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
  interval: 10s
  timeout: 5s
  retries: 5
```

**동작 흐름**:
```
컨테이너 시작
    │
    ▼
┌─────────────────────────────────┐
│  interval(10초)마다 test 명령 실행 │
│  mysqladmin ping -h localhost   │
└─────────────────────────────────┘
    │
    ├── 성공 (exit 0) → healthy
    │
    └── 실패 (exit 1)
            │
            ├── retries 미만 → 재시도 대기
            │
            └── retries 연속 실패 → unhealthy (확정)
```

**컨테이너 Health 상태 3가지**:

| 상태 | 의미 |
|------|------|
| `starting` | 첫 체크 전, 또는 체크 진행 중 |
| `healthy` | test 명령 성공 (exit code 0) |
| `unhealthy` | retries 횟수만큼 연속 실패 |

#### 각 옵션 상세

```yaml
healthcheck:
  test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
  interval: 10s
  timeout: 5s
  retries: 5
  start_period: 30s   # (선택) 시작 유예 기간
```

| 옵션 | 설명 | 권장 값 |
|------|------|---------|
| `test` | 실행할 명령어 | 서비스별 상이 |
| `interval` | 체크 간격 | 10~30초 |
| `timeout` | 명령 응답 대기 시간 | 3~10초 |
| `retries` | 연속 실패 허용 횟수 | 3~5회 |
| `start_period` | 시작 후 유예 기간 | 초기화 오래 걸리는 서비스에 설정 |

**`start_period`가 중요한 이유**:
- MySQL처럼 초기화가 오래 걸리는 서비스에 유용
- 이 기간 동안 실패해도 unhealthy로 카운트 안 함
- 예: `start_period: 60s` → 60초 동안은 실패해도 재시도

#### 실제 확인 방법

```bash
# 컨테이너 상태 확인 (STATUS 컬럼에 healthy 표시)
docker ps

# 출력 예시
CONTAINER ID   IMAGE       STATUS                   PORTS
abc123...      mysql:8.0   Up 2 min (healthy)       22306->3306
def456...      redis:7     Up 2 min (healthy)       22379->6379
```

```bash
# 상세 health 정보 확인
docker inspect temporal-exam-mysql --format='{{json .State.Health}}' | jq

# 출력 예시
{
  "Status": "healthy",
  "FailingStreak": 0,
  "Log": [
    {
      "Start": "2026-01-29T10:00:00.000Z",
      "End": "2026-01-29T10:00:00.100Z",
      "ExitCode": 0,
      "Output": "mysqld is alive\n"
    }
  ]
}
```

#### depends_on + condition (핵심!)

**단순 depends_on의 한계**:
```yaml
services:
  app:
    depends_on:
      - mysql   # mysql 컨테이너 "시작"만 기다림 (healthy 확인 안함)
```

**condition과 함께 사용** (권장):
```yaml
services:
  app:
    depends_on:
      mysql:
        condition: service_healthy  # healthy 될 때까지 대기!
```

**사용 가능한 condition**:

| condition | 의미 |
|-----------|------|
| `service_started` | 컨테이너 시작됨 (기본값) |
| `service_healthy` | healthcheck 통과 |
| `service_completed_successfully` | 컨테이너 종료 (exit 0) |

#### 서비스별 Healthcheck 예시

**MySQL**:
```yaml
healthcheck:
  test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
  # mysqladmin ping: MySQL 데몬이 연결 가능한지 확인
```

**Redis**:
```yaml
healthcheck:
  test: ["CMD", "redis-cli", "ping"]
  # redis-cli ping: Redis가 PONG 응답하는지 확인
```

**Spring Boot** (향후 컨테이너화 시):
```yaml
healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
  # Actuator health endpoint 호출
```

**PostgreSQL**:
```yaml
healthcheck:
  test: ["CMD-SHELL", "pg_isready -U postgres"]
```

#### 학습 포인트

**Q1: `start_period`가 없으면?**
- MySQL init.sql 실행이 오래 걸릴 경우, 초기에 unhealthy 상태가 될 수 있음
- retries 소진 후에도 계속 체크하긴 하지만, 의존 서비스가 시작 안 될 수 있음

**Q2: `mysqladmin ping`의 한계는?**
- MySQL 데몬만 확인, init.sql 완료 여부는 확인 불가
- 더 정확한 체크가 필요하면:
  ```yaml
  test: ["CMD-SHELL", "mysql -uroot -p$$MYSQL_ROOT_PASSWORD -e 'SELECT 1'"]
  ```

**Q3: MSA에서 Healthcheck의 의미는?**
- 서비스 간 의존성 순서 보장
- 로드밸런서가 healthy 인스턴스에만 트래픽 전달
- Kubernetes의 `livenessProbe`, `readinessProbe`와 유사한 개념

---

## 6. 설정 파일 준비

### 6.1 디렉토리 구조

```
spring-temporal-exam/
├── docker-compose.yml
└── docker/
    ├── mysql/
    │   └── init/
    │       └── 01-init-databases.sql
    ├── prometheus/
    │   └── prometheus.yml
    ├── grafana/
    │   └── provisioning/
    │       └── datasources/
    │           └── datasource.yml
    ├── loki/
    │   └── loki-config.yml
    └── alertmanager/
        └── alertmanager.yml
```

### 6.2 Prometheus 설정

```yaml
# docker/prometheus/prometheus.yml
global:
  scrape_interval: 15s  # 15초마다 메트릭 수집

scrape_configs:
  # Prometheus 자체 모니터링
  - job_name: 'prometheus'
    static_configs:
      - targets: ['localhost:9090']

  # Spring Boot 애플리케이션들
  - job_name: 'spring-boot-apps'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets:
          - 'host.docker.internal:8081'  # order-service
          - 'host.docker.internal:8082'  # inventory-service
          - 'host.docker.internal:8083'  # payment-service
```

**host.docker.internal**:
- Docker 컨테이너에서 호스트 머신에 접근하는 특수 DNS
- 호스트에서 실행 중인 Spring Boot 앱에 접근 가능

### 6.3 Loki 설정

```yaml
# docker/loki/loki-config.yml
auth_enabled: false

server:
  http_listen_port: 3100

ingester:
  lifecycler:
    ring:
      kvstore:
        store: inmemory
      replication_factor: 1

schema_config:
  configs:
    - from: 2020-10-24
      store: boltdb-shipper
      object_store: filesystem
      schema: v11
      index:
        prefix: index_
        period: 24h

storage_config:
  boltdb_shipper:
    active_index_directory: /loki/index
    cache_location: /loki/cache
  filesystem:
    directory: /loki/chunks
```

### 6.4 Grafana Datasource 설정

```yaml
# docker/grafana/provisioning/datasources/datasource.yml
apiVersion: 1

datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true

  - name: Loki
    type: loki
    access: proxy
    url: http://loki:3100
```

### 6.5 Alertmanager 설정

```yaml
# docker/alertmanager/alertmanager.yml
global:
  resolve_timeout: 5m

route:
  group_by: ['alertname']
  group_wait: 10s
  group_interval: 10s
  repeat_interval: 1h
  receiver: 'default-receiver'

receivers:
  - name: 'default-receiver'
    # Slack, Email 등 알림 설정 추가 가능
```

---

## 7. Docker Compose 명령어

### 7.1 기본 명령어

```bash
# 모든 서비스 시작 (백그라운드)
docker compose up -d

# 모든 서비스 시작 (포그라운드 - 로그 보임)
docker compose up

# 모든 서비스 중지
docker compose down

# 서비스 중지 + 볼륨 삭제 (데이터 초기화)
docker compose down -v

# 서비스 상태 확인
docker compose ps

# 서비스 로그 확인
docker compose logs

# 특정 서비스 로그 확인
docker compose logs mysql

# 로그 실시간 확인 (follow)
docker compose logs -f mysql
```

### 7.2 개별 서비스 관리

```bash
# 특정 서비스만 시작
docker compose up -d mysql redis

# 특정 서비스만 중지
docker compose stop mysql

# 특정 서비스 재시작
docker compose restart mysql

# 컨테이너 내부 접속
docker compose exec mysql bash

# MySQL 클라이언트 직접 실행
docker compose exec mysql mysql -uroot -ppassword
```

### 7.3 이미지 및 빌드

```bash
# 이미지 새로 받기 (업데이트)
docker compose pull

# 서비스 재생성
docker compose up -d --force-recreate

# 볼륨 초기화하고 새로 시작
docker compose down -v && docker compose up -d
```

---

## 8. 개발 워크플로우

### 8.1 처음 프로젝트 시작할 때

```bash
# 1. 프로젝트 clone
git clone <repository>
cd spring-temporal-exam

# 2. Docker Compose로 인프라 시작
docker compose up -d

# 3. 상태 확인
docker compose ps

# 4. 로그 확인 (문제 있는지)
docker compose logs

# 5. Spring Boot 앱 실행
./gradlew :service-order:bootRun
```

### 8.2 매일 개발 시작할 때

```bash
# 인프라 시작 (이미 실행 중이면 무시됨)
docker compose up -d

# IDE에서 Spring Boot 앱 실행
```

### 8.3 개발 종료할 때

```bash
# 인프라 중지 (데이터 유지)
docker compose stop

# 또는 그냥 두어도 됨 (리소스는 사용)
```

### 8.4 DB 초기화가 필요할 때

```bash
# MySQL 데이터 삭제하고 새로 시작
docker compose down -v
docker compose up -d mysql

# 또는 MySQL만 재생성
docker compose rm -sf mysql
docker volume rm spring-temporal-exam_mysql-data
docker compose up -d mysql
```

---

## 9. 문제 해결

### 9.1 포트 충돌

```
Error: bind: address already in use
```

**해결**:
```bash
# 3306 포트 사용 중인 프로세스 확인
# Windows
netstat -ano | findstr :3306

# Mac/Linux
lsof -i :3306

# 해당 프로세스 종료하거나, docker-compose.yml에서 포트 변경
ports:
  - "3307:3306"  # 호스트 포트를 3307로 변경
```

### 9.2 볼륨 권한 문제 (Linux)

```bash
# 볼륨 디렉토리 권한 설정
sudo chown -R 1000:1000 ./docker/
```

### 9.3 컨테이너 시작 안 됨

```bash
# 상세 로그 확인
docker compose logs mysql

# 컨테이너 상태 확인
docker compose ps -a

# 헬스체크 상태 확인
docker inspect spring-temporal-mysql | grep -A 20 "Health"
```

### 9.4 MySQL 접속 안 됨

```bash
# MySQL 컨테이너가 healthy 상태인지 확인
docker compose ps

# 직접 접속 테스트
docker compose exec mysql mysql -uroot -ppassword -e "SELECT 1"
```

---

## 10. Phase별 인프라 구성

### Phase 1: 기반 구축

```yaml
# docker-compose.yml (Phase 1 최소 구성)
services:
  mysql:
    # ... MySQL 설정만
```

### Phase 2-A: REST Saga

```yaml
# Phase 1 + Redis 추가
services:
  mysql:
    # ...
  redis:
    # ... 분산 락용
```

### Phase 2-B: MQ + Observability

```yaml
# 전체 구성
services:
  mysql:
  redis:
  zipkin:
  prometheus:
  grafana:
  loki:
  alertmanager:
```

### Phase 3: Temporal

```yaml
# Phase 2-B + Temporal 추가
services:
  # ... 기존 서비스들

  temporal:
    image: temporalio/auto-setup:latest
    ports:
      - "7233:7233"
    environment:
      - DB=mysql
      - MYSQL_SEEDS=mysql
    depends_on:
      mysql:
        condition: service_healthy

  temporal-ui:
    image: temporalio/ui:latest
    ports:
      - "8088:8080"
    environment:
      - TEMPORAL_ADDRESS=temporal:7233
    depends_on:
      - temporal
```

---

## 11. 실습 과제

1. `docker-compose.yml` 파일 생성
2. MySQL, Redis 서비스 설정
3. `docker/mysql/init/01-init-databases.sql` 작성
4. `docker compose up -d` 실행
5. MySQL 접속하여 데이터베이스 확인
6. Redis 접속하여 `PING` 테스트

---

## 참고 자료

- [Docker Compose 공식 문서](https://docs.docker.com/compose/)
- [Docker Hub - MySQL](https://hub.docker.com/_/mysql)
- [Docker Hub - Redis](https://hub.docker.com/_/redis)
- [Awesome Compose (예제 모음)](https://github.com/docker/awesome-compose)

---

## 다음 단계

Phase 1 학습 완료! [Phase 2-A: Saga 패턴](../phase2a/01-saga-pattern.md)으로 이동
