# Temporal 로컬 환경 구축과 Web UI 실습

> **대상**: 02-core-concepts.md를 읽고 5가지 핵심 개념(Workflow, Activity, Worker, Task Queue, Temporal Server)을 이해한 학습자
> **목표**: Docker Compose로 Temporal 환경을 직접 띄우고, Web UI에서 Workflow를 눈으로 확인하는 것
> **선행 문서**: [02-core-concepts.md](./02-core-concepts.md)
>
> **버전 정보**: Temporal Server 1.25.2 (Docker) / Temporal SDK 1.26.0 (Java) / Temporal UI 2.31.2
>
> **⚠️ 참고**: 이 프로젝트는 `temporalio/auto-setup:1.25.2` 이미지를 사용합니다.
> 이 이미지는 **Deprecated** 상태입니다 (docker-compose 저장소 2026-01-05 아카이브).
> 학습 목적으로는 문제없으나, 새 프로젝트에서는 `temporal server start-dev` (CLI) 또는
> `temporalio/server` 이미지를 권장합니다. 상세: [TECH-STACK.md](../../architecture/TECH-STACK.md#️-temporal-docker-이미지-변경)

---

## 목차

1. [Docker Compose 구성 상세 설명](#1-docker-compose-구성-상세-설명)
2. [Temporal Server 내부 아키텍처](#2-temporal-server-내부-아키텍처)
3. [로컬 환경 실행 가이드](#3-로컬-환경-실행-가이드)
4. [Temporal Web UI 완전 가이드](#4-temporal-web-ui-완전-가이드)
5. [핸즈온 실습: 첫 번째 Workflow 실행과 UI 탐색](#5-핸즈온-실습-첫-번째-workflow-실행과-ui-탐색)
6. [실습 체크리스트](#6-실습-체크리스트)

---

## 1. Docker Compose 구성 상세 설명

### 1.1 What: 우리 프로젝트의 Docker Compose 구성

프로젝트의 Docker Compose 파일(`spring-temporal-exam-docker/docker-compose.yml`)은 5개의 서비스를 정의합니다.
이 서비스들은 두 가지 역할로 나뉩니다: **애플리케이션 인프라**와 **Temporal 인프라**.

```
+-----------------------------------------------------------------------+
|                    Docker Compose 서비스 구성                           |
+-----------------------------------------------------------------------+
|                                                                        |
|  [애플리케이션 인프라]          [Temporal 인프라]                       |
|  +------------------+          +---------------------------+           |
|  | mysql            |          | temporal-postgresql       |           |
|  | (MySQL 8.0)      |          | (PostgreSQL 15)           |           |
|  | 포트: 21306      |          | 포트: 21432               |           |
|  +------------------+          +---------------------------+           |
|  +------------------+          +---------------------------+           |
|  | redis            |          | temporal                  |           |
|  | (Redis 7 Alpine) |          | (auto-setup:1.25.2)       |           |
|  | 포트: 21379      |          | 포트: 21733               |           |
|  +------------------+          +---------------------------+           |
|                                +---------------------------+           |
|                                | temporal-ui               |           |
|                                | (UI:2.31.2)               |           |
|                                | 포트: 21088               |           |
|                                +---------------------------+           |
+-----------------------------------------------------------------------+
```

### 1.2 각 서비스의 역할

| 서비스 | 이미지 | 호스트 포트 | 컨테이너 포트 | 역할 |
|--------|--------|-------------|---------------|------|
| `mysql` | mysql:8.0 | **21306** | 3306 | 애플리케이션 비즈니스 데이터 (주문/재고/결제) |
| `redis` | redis:7-alpine | **21379** | 6379 | 캐시, 분산 락, 멱등성 키, Redis Stream |
| `temporal-postgresql` | postgres:15-alpine | **21432** | 5432 | Temporal 내부 상태 저장 (Event History 등) |
| `temporal` | temporalio/auto-setup:1.25.2 | **21733** | 7233 | Temporal Server (gRPC 엔드포인트) |
| `temporal-ui` | temporalio/ui:2.31.2 | **21088** | 8080 | Temporal Web UI (모니터링/디버깅) |

### 1.3 Why: Temporal은 왜 별도 PostgreSQL이 필요한가?

이 프로젝트에는 DB가 **2개** 있습니다: MySQL(21306)과 PostgreSQL(21432). 왜 분리했을까요?

```
  질문: "MySQL 하나로 다 쓰면 안 되나요?"
  답변: 기술적으로는 가능하지만, 반드시 분리해야 합니다.

  +--------------------------------------------------------------------+
  |                   DB 분리 이유                                      |
  +--------------------------------------------------------------------+
  |                                                                      |
  |  MySQL (21306)                  PostgreSQL (21432)                   |
  |  +--------------------------+  +---------------------------------+  |
  |  | 비즈니스 데이터          |  | Temporal 내부 데이터            |  |
  |  | - orders 테이블          |  | - Event History                 |  |
  |  | - inventory 테이블       |  | - Workflow 실행 상태            |  |
  |  | - payments 테이블        |  | - Task Queue 메타데이터         |  |
  |  | - outbox_events 테이블   |  | - Timer 정보                    |  |
  |  +--------------------------+  | - Namespace 설정                |  |
  |                                +---------------------------------+  |
  |  관리 주체: 개발자 (우리)      관리 주체: Temporal Server          |
  |  마이그레이션: Flyway          마이그레이션: auto-setup 자동 처리  |
  |  스키마: 우리가 설계           스키마: Temporal이 정의             |
  +--------------------------------------------------------------------+

  분리하는 핵심 이유 3가지:
  1. 장애 격리: Temporal DB 장애가 비즈니스 DB에 영향을 주지 않음
  2. 독립적 스케일링: 각 DB를 독립적으로 튜닝/확장 가능
  3. 관심사 분리: Temporal 스키마는 Temporal이 관리, 비즈니스 스키마는 우리가 관리
```

### 1.4 서비스 의존관계 (depends_on)

Docker Compose에서 `depends_on`으로 정의된 시작 순서입니다.

```
  시작 순서 (의존관계):

  Phase 1: 독립 서비스 (의존 없음, 동시 시작)
  +-------------------+  +-------------------+  +-------------------+
  | mysql             |  | redis             |  | temporal-         |
  |                   |  |                   |  | postgresql        |
  | healthcheck:      |  | healthcheck:      |  | healthcheck:      |
  | mysqladmin ping   |  | redis-cli ping    |  | pg_isready        |
  +-------------------+  +-------------------+  +---------+---------+
                                                          |
  Phase 2: temporal-postgresql이 healthy 되면             |
  +-------------------------------------------------------v---------+
  | temporal (auto-setup:1.25.2)                                     |
  | depends_on: temporal-postgresql (condition: service_healthy)     |
  | healthcheck: tctl cluster health (start_period: 30s)            |
  +---------------------------------------+-------------------------+
                                          |
  Phase 3: temporal이 healthy 되면        |
  +---------------------------------------v-------------------------+
  | temporal-ui                                                      |
  | depends_on: temporal (condition: service_healthy)               |
  +------------------------------------------------------------------+

  총 시작 시간: 약 40~60초 (healthcheck 대기 포함)
```

**핵심 포인트**: 모든 `depends_on`은 `condition: service_healthy`를 사용합니다.
단순히 컨테이너가 시작된 것이 아니라, healthcheck가 통과할 때까지 기다립니다.

### 1.5 네트워크 토폴로지

모든 서비스는 `temporal-exam-network`라는 단일 브릿지 네트워크에 연결됩니다.

```
  +-------------------------------------------------------------------+
  |  Docker Bridge Network: temporal-exam-network                      |
  |                                                                     |
  |  컨테이너 간 통신: 컨테이너 이름으로 DNS 해석                      |
  |                                                                     |
  |  +---------------------+     +---------------------+               |
  |  | temporal-exam-mysql |     | temporal-exam-redis  |               |
  |  | 내부: 3306          |     | 내부: 6379           |               |
  |  | 호스트: 21306       |     | 호스트: 21379        |               |
  |  +---------------------+     +---------------------+               |
  |                                                                     |
  |  +----------------------------+                                    |
  |  | temporal-exam-postgresql   |                                    |
  |  | 내부: 5432                 |                                    |
  |  | 호스트: 21432              |<-------+                           |
  |  +----------------------------+        |                           |
  |                                        | SQL (포트 5432)           |
  |  +----------------------------+        |                           |
  |  | temporal-exam-server       |--------+                           |
  |  | 내부: 7233                 |                                    |
  |  | 호스트: 21733              |<-------+                           |
  |  +----------------------------+        |                           |
  |                                        | gRPC (포트 7233)          |
  |  +----------------------------+        |                           |
  |  | temporal-exam-ui           |--------+                           |
  |  | 내부: 8080                 |                                    |
  |  | 호스트: 21088              |                                    |
  |  +----------------------------+                                    |
  |                                                                     |
  +-------------------------------------------------------------------+

  호스트 머신 (개발 PC)
  +-------------------------------------------------------------------+
  |  Spring Boot 앱 (orchestrator-temporal 등)                         |
  |  -> Temporal: localhost:21733 (gRPC)                               |
  |  -> MySQL:    localhost:21306 (JDBC)                               |
  |  -> Redis:    localhost:21379 (Redisson)                           |
  +-------------------------------------------------------------------+
  |  브라우저                                                          |
  |  -> Temporal UI: http://localhost:21088                            |
  +-------------------------------------------------------------------+

  중요: 컨테이너 내부에서는 컨테이너 이름으로 통신합니다.
  - temporal -> temporal-postgresql (컨테이너 이름 = DNS)
  - temporal-ui -> temporal:7233 (TEMPORAL_ADDRESS 환경변수)
  호스트에서 접속할 때만 localhost + 매핑 포트를 사용합니다.
```

---

## 2. Temporal Server 내부 아키텍처

### 2.1 What: Temporal Server의 4가지 내부 서비스

02-core-concepts.md에서 Temporal Server를 "두뇌"라고 배웠습니다.
이 두뇌 안에는 4가지 전문 부서가 있습니다.

```
+-----------------------------------------------------------------------+
|                 Temporal Server 내부 구조 (4개 서비스)                  |
+-----------------------------------------------------------------------+
|                                                                        |
|  +------------------------------------------------------------------+ |
|  |  Frontend Service (접수 창구)                                     | |
|  |  - 모든 외부 요청의 진입점 (API Gateway 역할)                    | |
|  |  - gRPC 엔드포인트 제공 (포트 7233)                              | |
|  |  - 요청 검증, 인증/인가                                          | |
|  |  - Rate Limiting                                                  | |
|  |  - 내부 서비스로 요청 라우팅                                      | |
|  +------------------------------------------------------------------+ |
|                              |                                         |
|              +---------------+----------------+                        |
|              v               v                v                        |
|  +------------------+ +------------------+ +---------------------+    |
|  | History Service  | | Matching Service | | Worker Service      |    |
|  | (기록 보관소)    | | (매칭 센터)      | | (내부 관리자)       |    |
|  |                  | |                  | |                     |    |
|  | - Event History  | | - Task Queue     | | - 내부 시스템 작업  |    |
|  |   저장/관리     | |   관리           | | - Archival          |    |
|  | - Workflow 상태  | | - Worker와 Task  | | - Replication       |    |
|  |   머신 관리     | |   매칭           | | - Cross-cluster     |    |
|  | - Timer 관리    | | - Long Polling   | |   작업              |    |
|  | - Mutable State | |   처리           | |                     |    |
|  +--------+--------+ +------------------+ +---------------------+    |
|           |                                                            |
|           v                                                            |
|  +------------------------------------------------------------------+ |
|  |  Persistence Layer (저장소)                                       | |
|  |  PostgreSQL / MySQL / Cassandra / 기타                            | |
|  +------------------------------------------------------------------+ |
+-----------------------------------------------------------------------+
```

### 2.2 각 서비스의 역할 상세

#### Frontend Service - 접수 창구

| 항목 | 설명 |
|------|------|
| **역할** | 모든 gRPC 요청의 진입점 |
| **하는 일** | SDK에서 오는 StartWorkflow, SignalWorkflow, QueryWorkflow 등의 요청을 받아 내부 서비스로 라우팅 |
| **비유** | 병원의 접수처: 환자(요청)를 적절한 진료과(서비스)로 안내 |

#### History Service - 기록 보관소

| 항목 | 설명 |
|------|------|
| **역할** | Workflow 상태와 Event History 관리 |
| **하는 일** | Event 기록, Workflow 상태 머신 전이, Timer 관리, Mutable State 유지 |
| **비유** | 법원 서기관: 재판(Workflow)의 모든 기록을 빠짐없이 기록하고 보관 |
| **핵심** | Temporal의 가장 중요한 서비스. Durable Execution의 근간 |

#### Matching Service - 매칭 센터

| 항목 | 설명 |
|------|------|
| **역할** | Task Queue 관리와 Worker 매칭 |
| **하는 일** | Task를 Task Queue에 넣고, Long Polling하는 Worker에게 할당 |
| **비유** | 배달 앱 매칭 시스템: 주문(Task)과 라이더(Worker)를 매칭 |

#### Worker Service (내부) - 내부 관리자

| 항목 | 설명 |
|------|------|
| **역할** | Temporal 내부 시스템 작업 실행 |
| **하는 일** | Archival(히스토리 보관), Cross-cluster 복제, 내부 시스템 Workflow |
| **주의** | **우리가 만드는 Worker와 다릅니다!** 이것은 Temporal 서버 내부의 Worker입니다 |

### 2.3 4개 서비스 간 통신 흐름

```
  Client (SDK)                    Temporal Server 내부
  +-----------+                   +----------------------------------------+
  |           |    gRPC           |                                        |
  | Workflow  |------------------>| Frontend Service                       |
  | Client    |   StartWorkflow  |     |                                  |
  |           |                   |     | 1. 요청 검증                     |
  +-----------+                   |     | 2. 내부 라우팅                   |
                                  |     v                                  |
  +-----------+                   | History Service                        |
  |           |    gRPC           |     |                                  |
  | Worker    |<----------------->|     | 3. Event History에               |
  |           | Long Polling      |     |    WorkflowExecutionStarted 기록 |
  |           | (PollWorkflowTask)|     | 4. WorkflowTask 생성             |
  |           |                   |     v                                  |
  +-----------+                   | Matching Service                       |
                                  |     |                                  |
                                  |     | 5. Task Queue에 Task 추가        |
                                  |     | 6. Long Polling 중인 Worker에게  |
                                  |     |    Task 할당                     |
                                  +----------------------------------------+
```

### 2.4 개발 모드 vs 프로덕션 모드

```
  [개발 모드] auto-setup 이미지 (우리 프로젝트)
  +----------------------------------------------------------+
  |  temporalio/auto-setup:1.25.2 (단일 컨테이너)             |
  |                                                            |
  |  +----------+ +----------+ +----------+ +-----------+     |
  |  | Frontend | | History  | | Matching | | Worker    |     |
  |  | Service  | | Service  | | Service  | | Service   |     |
  |  +----------+ +----------+ +----------+ +-----------+     |
  |                                                            |
  |  4개 서비스가 하나의 프로세스에서 실행                      |
  |  + 자동 스키마 마이그레이션 + default Namespace 생성       |
  +----------------------------------------------------------+

  장점: 설정 간단, 빠른 시작
  단점: 스케일링 불가, 프로덕션 부적합


  [프로덕션 모드] 각 서비스를 개별 컨테이너로 배포
  +------------+  +------------+  +------------+  +------------+
  | Frontend   |  | History    |  | Matching   |  | Worker     |
  | Service    |  | Service    |  | Service    |  | Service    |
  | x 2~3대   |  | x 3~5대   |  | x 2~3대   |  | x 1~2대   |
  +------------+  +------------+  +------------+  +------------+
        |               |               |               |
        +---------------+---------------+---------------+
                        |
                  +------------+
                  | PostgreSQL |
                  | (HA 구성)  |
                  +------------+

  장점: 독립적 스케일링, 장애 격리, 고가용성
  단점: 설정 복잡, 인프라 비용 증가

  우리 프로젝트는 학습 목적이므로 개발 모드를 사용합니다.
  auto-setup 이미지가 스키마 생성, Namespace 초기화를 자동으로 처리합니다.
```

---

## 3. 로컬 환경 실행 가이드

### 3.1 사전 준비

| 필요 소프트웨어 | 버전 | 확인 명령어 |
|-----------------|------|-------------|
| Docker Desktop | 최신 | `docker --version` |
| Docker Compose | v2+ | `docker compose version` |

### 3.2 Step-by-Step 실행

#### Step 1: Docker Compose 파일 위치로 이동

```bash
cd spring-temporal-exam-docker
```

#### Step 2: 전체 서비스 시작

```bash
docker compose up -d
```

`-d` 옵션은 백그라운드(detached) 모드로 실행합니다.

#### Step 3: 시작 상태 확인

```bash
docker compose ps
```

**기대 결과**: 5개 서비스 모두 `running` 또는 `healthy` 상태

```
NAME                        STATUS                   PORTS
temporal-exam-mysql         running (healthy)        0.0.0.0:21306->3306/tcp
temporal-exam-redis         running (healthy)        0.0.0.0:21379->6379/tcp
temporal-exam-postgresql    running (healthy)        0.0.0.0:21432->5432/tcp
temporal-exam-server        running (healthy)        0.0.0.0:21733->7233/tcp
temporal-exam-ui            running                  0.0.0.0:21088->8080/tcp
```

#### Step 4: Temporal Server 준비 상태 확인

Temporal Server는 `start_period: 30s`로 설정되어 있어, 완전히 준비되기까지 30초 이상 걸릴 수 있습니다.

```bash
# 방법 1: healthcheck 로그 확인
docker inspect --format='{{.State.Health.Status}}' temporal-exam-server
# 기대 결과: healthy

# 방법 2: tctl로 직접 확인
docker exec temporal-exam-server tctl --address temporal:7233 cluster health
# 기대 결과: SERVING

# 방법 3: Namespace 목록 확인
docker exec temporal-exam-server tctl --address temporal:7233 namespace list
# 기대 결과: default Namespace가 출력됨
```

#### Step 5: 로그 확인 (선택 사항)

```bash
# 전체 로그 확인 (실시간)
docker compose logs -f

# 특정 서비스 로그만 확인
docker compose logs -f temporal
docker compose logs -f temporal-ui
```

### 3.3 Health Check 상세

각 서비스의 healthcheck 설정을 이해해 두면 트러블슈팅에 도움이 됩니다.

| 서비스 | healthcheck 명령어 | interval | timeout | retries |
|--------|-------------------|----------|---------|---------|
| mysql | `mysqladmin ping -h localhost` | 10s | 5s | 5 |
| redis | `redis-cli ping` | 10s | 5s | 5 |
| temporal-postgresql | `pg_isready -U temporal` | 10s | 5s | 5 |
| temporal | `tctl --address temporal:7233 cluster health` | 10s | 5s | 10 |
| temporal-ui | (없음 - temporal이 healthy면 시작) | - | - | - |

### 3.4 자주 발생하는 문제와 해결

#### 문제 1: 포트 충돌

```
Error: Bind for 0.0.0.0:21306 failed: port is already allocated
```

**원인**: 21306 포트를 다른 프로세스가 사용 중
**해결**:
```bash
# Windows: 어떤 프로세스가 포트를 사용 중인지 확인
netstat -ano | findstr :21306

# 해당 프로세스 종료 후 다시 시작
docker compose up -d
```

#### 문제 2: Temporal Server가 healthy가 되지 않음

```bash
# 로그 확인
docker compose logs temporal

# 흔한 원인: PostgreSQL이 아직 준비되지 않음
# 해결: 전체 재시작
docker compose down
docker compose up -d
```

#### 문제 3: 시작이 느림 (60초 이상)

**원인**: 첫 실행 시 이미지 다운로드 + DB 초기화에 시간이 걸림
**해결**: 첫 실행은 2-3분 대기. 두 번째부터는 30-40초 내에 시작

#### 문제 4: 볼륨 데이터를 초기화하고 싶을 때

```bash
# 전체 서비스 중지 + 볼륨 삭제 (완전 초기화)
docker compose down -v

# 다시 시작
docker compose up -d
```

---

## 4. Temporal Web UI 완전 가이드

### 4.1 접속

브라우저에서 **http://localhost:21088** 접속

### 4.2 메인 화면 구성

Temporal UI는 크게 5가지 주요 화면으로 구성됩니다.

```
+-----------------------------------------------------------------------+
|  Temporal Web UI (http://localhost:21088)                               |
+-----------------------------------------------------------------------+
|                                                                        |
|  [상단 네비게이션]                                                     |
|  +------------------------------------------------------------------+ |
|  | [Namespaces]  [Workflows]  [Schedules]  [Batch Operations]       | |
|  +------------------------------------------------------------------+ |
|                                                                        |
|  [메인 콘텐츠 영역]                                                    |
|  +------------------------------------------------------------------+ |
|  |                                                                    | |
|  |  현재 선택된 Namespace: default                                   | |
|  |                                                                    | |
|  |  (선택한 탭에 따라 다른 내용이 표시됨)                            | |
|  |                                                                    | |
|  +------------------------------------------------------------------+ |
+-----------------------------------------------------------------------+
```

### 4.3 (a) Namespace 페이지

#### Namespace란?

Namespace는 Temporal에서 **Workflow를 논리적으로 격리하는 단위**입니다.

```
  비유: 아파트 동

  아파트 단지 = Temporal Cluster
  101동 = "default" Namespace
  102동 = "staging" Namespace
  103동 = "team-a" Namespace

  각 동(Namespace)의 주민(Workflow)은 서로 독립적으로 관리됩니다.
  101동의 관리 규칙이 102동에 영향을 주지 않습니다.
```

**개발 환경에서는** `default` Namespace 하나만 사용합니다.
auto-setup 이미지가 자동으로 `default` Namespace를 생성해 줍니다.

```
+-----------------------------------------------------------------------+
|  Namespace 설정 화면                                                   |
+-----------------------------------------------------------------------+
|                                                                        |
|  Namespace: default                                                    |
|                                                                        |
|  +---------------------------+---------------------------------------+ |
|  | Retention Period          | 24h (Event History 보관 기간)         | |
|  | History Archival          | Disabled                              | |
|  | Visibility Archival       | Disabled                              | |
|  | Clusters                  | active                                | |
|  +---------------------------+---------------------------------------+ |
|                                                                        |
|  Retention Period = Event History를 얼마나 보관할지                     |
|  개발 환경에서는 24시간이 기본값입니다.                                 |
|  (24시간이 지난 완료/실패 Workflow의 히스토리는 자동 삭제)              |
|                                                                        |
+-----------------------------------------------------------------------+
```

### 4.4 (b) Workflow 목록 화면

Workflows 탭을 클릭하면 현재 Namespace의 모든 Workflow를 볼 수 있습니다.

```
+-----------------------------------------------------------------------+
|  Recent Workflows                                          [Filters]   |
+-----------------------------------------------------------------------+
|                                                                        |
|  [검색/필터 영역]                                                      |
|  +------------------------------------------------------------------+ |
|  | Status: [All v]  Type: [All v]  Search: [________________]       | |
|  +------------------------------------------------------------------+ |
|                                                                        |
|  [Workflow 목록]                                                       |
|  +------------------------------------------------------------------+ |
|  | Status | Workflow ID         | Type            | Start    | End   | |
|  +--------+---------------------+-----------------+----------+-------| |
|  | ✅     | order-12345         | OrderWorkflow   | 10:00:00 | 10:01 | |
|  | ✅     | order-12346         | OrderWorkflow   | 10:01:00 | 10:02 | |
|  | ❌     | order-12347         | OrderWorkflow   | 10:02:00 | 10:02 | |
|  | 🔄     | order-12348         | OrderWorkflow   | 10:03:00 |   -   | |
|  +------------------------------------------------------------------+ |
|                                                                        |
|  Status 아이콘:                                                        |
|  ✅ Completed (정상 완료)                                              |
|  ❌ Failed (실패)                                                      |
|  🔄 Running (실행 중)                                                  |
|  ⏱  Timed Out (타임아웃)                                              |
|  🚫 Cancelled (취소됨)                                                |
|  ⊘  Terminated (강제 종료)                                            |
|                                                                        |
+-----------------------------------------------------------------------+
```

**필터 활용법**:
- **Status**: Running / Completed / Failed / Cancelled / Terminated / Timed Out
- **Type**: Workflow 타입명 (예: OrderWorkflow)
- **Search**: Workflow ID로 직접 검색

### 4.5 (c) Workflow 상세 화면

목록에서 Workflow를 클릭하면 상세 화면으로 이동합니다.

```
+-----------------------------------------------------------------------+
|  Workflow: order-12345                                                  |
|  Type: OrderWorkflow | Status: Completed | Duration: 1.5s              |
+-----------------------------------------------------------------------+
|                                                                        |
|  [Summary] [History] [Stack Trace] [Query] [Workers]                   |
|                                                                        |
|  === Summary 탭 ===                                                    |
|  +------------------------------------------------------------------+ |
|  | Input:                                                             | |
|  | {                                                                  | |
|  |   "customerId": 1,                                                | |
|  |   "productId": 1,                                                 | |
|  |   "quantity": 2,                                                  | |
|  |   "amount": 20000                                                 | |
|  | }                                                                  | |
|  +------------------------------------------------------------------+ |
|  | Output (Result):                                                   | |
|  | {                                                                  | |
|  |   "success": true,                                                | |
|  |   "orderId": "123",                                               | |
|  |   "paymentId": "456"                                              | |
|  | }                                                                  | |
|  +------------------------------------------------------------------+ |
|  | Pending Activities: 0                                              | |
|  | Task Queue: order-task-queue                                      | |
|  +------------------------------------------------------------------+ |
|                                                                        |
+-----------------------------------------------------------------------+
```

**Summary 탭에서 확인할 수 있는 것**:
- **Input**: Workflow를 시작할 때 전달한 파라미터
- **Output**: Workflow가 반환한 결과값
- **Pending Activities**: 현재 실행 대기 중인 Activity 수
- **Task Queue**: 이 Workflow가 사용하는 Task Queue 이름

### 4.6 (d) Event History 뷰

가장 중요한 화면입니다. Workflow의 모든 실행 기록을 볼 수 있습니다.

```
+-----------------------------------------------------------------------+
|  Event History                        [Timeline v] [Download JSON]     |
+-----------------------------------------------------------------------+
|                                                                        |
|  === Timeline 뷰 (시각적) ===                                          |
|                                                                        |
|  10:00:00.000  WorkflowExecutionStarted ----+                         |
|  10:00:00.010  WorkflowTaskScheduled        |                         |
|  10:00:00.050  WorkflowTaskCompleted        |                         |
|  10:00:00.051  ActivityTaskScheduled -------+-- createOrder            |
|  10:00:00.100  ActivityTaskStarted          |                         |
|  10:00:01.500  ActivityTaskCompleted -------+  result: "ORDER-123"    |
|  10:00:01.501  ActivityTaskScheduled -------+-- reserveStock           |
|  10:00:01.550  ActivityTaskStarted          |                         |
|  10:00:02.000  ActivityTaskCompleted -------+  result: void           |
|  10:00:02.001  ActivityTaskScheduled -------+-- processPayment         |
|  10:00:02.100  ActivityTaskStarted          |                         |
|  10:00:02.800  ActivityTaskCompleted -------+  result: "PAY-456"      |
|  10:00:02.850  WorkflowExecutionCompleted ---  result: {success:true} |
|                                                                        |
+-----------------------------------------------------------------------+
```

**주요 Event 유형 설명**:

| Event | 의미 | 언제 발생하는가 |
|-------|------|-----------------|
| `WorkflowExecutionStarted` | Workflow 실행 시작 | Client가 StartWorkflow 호출 시 |
| `WorkflowTaskScheduled` | Workflow 코드 실행 예약 | Workflow 코드를 Worker에게 보내기 직전 |
| `WorkflowTaskCompleted` | Workflow 코드 실행 완료 | Worker가 다음 Activity를 결정한 후 |
| `ActivityTaskScheduled` | Activity 실행 예약 | Workflow에서 Activity 호출 시 |
| `ActivityTaskStarted` | Activity 실행 시작 | Worker가 Activity 코드를 실행 시작 |
| `ActivityTaskCompleted` | Activity 실행 완료 | Activity가 결과를 반환 |
| `ActivityTaskFailed` | Activity 실행 실패 | Activity에서 예외 발생 |
| `WorkflowExecutionCompleted` | Workflow 전체 완료 | Workflow 메서드가 return |
| `WorkflowExecutionFailed` | Workflow 전체 실패 | Workflow에서 미처리 예외 발생 |

**Timeline vs Compact 뷰**: UI 우측 상단의 토글로 전환 가능합니다.
- **Timeline**: 시간 축 기반의 시각적 뷰, 전체 흐름 파악에 유리
- **Compact**: 테이블 형태, 상세 데이터 확인에 유리

### 4.7 (e) Stack Trace / Query 탭

```
+-----------------------------------------------------------------------+
|  Stack Trace 탭 (Running 상태의 Workflow에서만 유효)                    |
+-----------------------------------------------------------------------+
|                                                                        |
|  현재 Workflow가 어디서 대기 중인지 보여줍니다.                         |
|                                                                        |
|  예시:                                                                 |
|  "coroutine root" ->                                                   |
|    OrderWorkflowImpl.processOrder(OrderRequest)                        |
|      at activities.processPayment(orderId)   <-- 여기서 대기 중         |
|                                                                        |
+-----------------------------------------------------------------------+
|  Query 탭                                                              |
+-----------------------------------------------------------------------+
|                                                                        |
|  Workflow에 @QueryMethod가 정의되어 있으면,                             |
|  UI에서 직접 Query를 실행하여 현재 상태를 조회할 수 있습니다.           |
|                                                                        |
|  Query Type: [getOrderStatus   v]                                      |
|  [Run Query]                                                           |
|                                                                        |
|  Result: { "status": "PAYMENT_PROCESSING", "orderId": "123" }         |
|                                                                        |
+-----------------------------------------------------------------------+
```

---

## 5. 핸즈온 실습: 첫 번째 Workflow 실행과 UI 탐색

### 5.1 실습 개요

```
  이 실습에서 할 것:

  Step 1: Docker Compose로 Temporal 시작
  Step 2: temporal CLI로 Hello World Workflow 실행
  Step 3: Web UI에서 Workflow 찾기
  Step 4: Event History 분석
  Step 5: Worker 없이 Workflow 시작하면 어떻게 되는지 확인
```

### Step 1: Docker Compose로 Temporal 시작

```bash
# 프로젝트 Docker 디렉토리로 이동
cd spring-temporal-exam-docker

# 서비스 시작
docker compose up -d

# 모든 서비스가 healthy 될 때까지 대기 (약 40-60초)
# 주기적으로 확인
docker compose ps
```

**확인 포인트**: 5개 서비스 모두 `running` 상태인지 확인하세요.
특히 `temporal-exam-server`가 `healthy`인지 확인합니다.

```bash
docker inspect --format='{{.State.Health.Status}}' temporal-exam-server
# 출력: healthy
```

### Step 2: Temporal CLI를 사용하여 Workflow 실행

auto-setup 이미지에는 `tctl` CLI가 내장되어 있습니다.
Worker 없이도 Workflow를 "시작"할 수 있습니다. (실행은 되지 않지만 시작 요청은 가능)

```bash
# Temporal Server 컨테이너 안에서 tctl 사용
docker exec temporal-exam-server tctl --address temporal:7233 \
  workflow start \
  --workflow_type HelloWorldWorkflow \
  --taskqueue hello-task-queue \
  --workflow_id hello-world-001 \
  --input '"Hello from tctl!"'
```

**기대 결과**:
```
Started Workflow Id: hello-world-001, run Id: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
```

이 명령어가 하는 일:
- `--workflow_type HelloWorldWorkflow`: 실행할 Workflow 타입 이름
- `--taskqueue hello-task-queue`: Task를 넣을 Task Queue 이름
- `--workflow_id hello-world-001`: 이 Workflow의 고유 ID
- `--input '"Hello from tctl!"'`: Workflow에 전달할 입력 데이터

**확인 포인트**: "Started Workflow Id" 메시지가 출력되어야 합니다.
에러 없이 시작되었다면, Temporal Server가 정상 동작하는 것입니다.

> **중요**: 이 Workflow를 실행할 Worker가 없으므로, Workflow는 시작되었지만 실제로 실행되지 않습니다.
> Task Queue에 Task가 쌓여서 Worker를 기다리는 상태가 됩니다. 이것이 Step 5에서 확인할 내용입니다.

### Step 3: Web UI에서 방금 실행한 Workflow 찾기

1. 브라우저에서 **http://localhost:21088** 접속
2. 좌측 메뉴 또는 상단에서 **Workflows** 클릭
3. Namespace가 `default`인지 확인

```
+-----------------------------------------------------------------------+
|  확인해야 할 것:                                                       |
+-----------------------------------------------------------------------+
|                                                                        |
|  1. Workflow 목록에 "hello-world-001"이 보이는가?                      |
|  2. Status가 "Running"인가? (Worker가 없으므로 계속 Running)           |
|  3. Workflow Type이 "HelloWorldWorkflow"인가?                          |
|  4. Task Queue가 "hello-task-queue"인가?                               |
|                                                                        |
+-----------------------------------------------------------------------+
```

**확인 포인트**: 목록에서 `hello-world-001`을 찾을 수 있어야 합니다.
Status는 `Running`이어야 합니다 (Worker가 없어서 진행이 안 되는 상태).

### Step 4: Event History 분석

`hello-world-001` Workflow를 클릭하여 상세 화면에 진입합니다.

```
  Event History에서 볼 수 있는 것:

  Event #1: WorkflowExecutionStarted
            +-- input: "Hello from tctl!"
            +-- workflowType: HelloWorldWorkflow
            +-- taskQueue: hello-task-queue

  Event #2: WorkflowTaskScheduled
            +-- taskQueue: hello-task-queue

  여기서 멈춰 있음! (Worker가 없으므로 Task를 가져갈 수 없음)
```

**각 Event가 의미하는 것**:

| Event | 의미 |
|-------|------|
| `WorkflowExecutionStarted` | "이 Workflow를 시작해 주세요"라는 요청이 접수됨 |
| `WorkflowTaskScheduled` | "Worker야, 이 Workflow 코드를 실행해 줘"라는 Task가 Queue에 추가됨 |

**WorkflowTaskStarted**가 없다는 것은 = Worker가 아직 이 Task를 가져가지 않았다는 뜻입니다.

**확인 포인트**:
- Event가 2개만 있어야 합니다 (Started, TaskScheduled)
- WorkflowTaskStarted가 없어야 합니다 (Worker가 없으므로)
- Summary 탭에서 Pending Activities를 확인해 봅니다

### Step 5: Worker 없이 Workflow를 시작하면 어떻게 되는지 확인

이 상황이 Temporal의 핵심 특성을 보여줍니다.

```
+-----------------------------------------------------------------------+
|  Worker가 없을 때 무슨 일이 일어나는가?                                |
+-----------------------------------------------------------------------+
|                                                                        |
|  1. Client가 Workflow 시작 요청 -----> Temporal Server: "OK, 시작!"  |
|                                                                        |
|  2. Temporal Server:                                                   |
|     - Event History에 WorkflowExecutionStarted 기록  (O)             |
|     - Task Queue에 WorkflowTask 추가                 (O)             |
|     - Worker에게 Task 전달                            (X) Worker 없음 |
|                                                                        |
|  3. Task Queue 상태:                                                   |
|     [hello-task-queue]                                                |
|     [WorkflowTask: hello-world-001]  <-- 아무도 안 가져감!           |
|                                                                        |
|  4. Workflow 상태: Running (영원히 대기)                               |
|                                                                        |
|  이것이 의미하는 것:                                                   |
|  - Temporal은 Workflow 시작과 실행을 분리합니다                       |
|  - Server는 "시작 요청 접수"만 담당                                   |
|  - 실제 코드 실행은 Worker가 담당                                     |
|  - Worker가 나중에 연결되면 그때 Task를 가져가서 실행 시작            |
|                                                                        |
|  즉, Worker를 먼저 띄울 필요가 없습니다!                              |
|  Workflow를 먼저 시작해 두고, Worker는 나중에 연결해도 됩니다.        |
+-----------------------------------------------------------------------+
```

이제 이 Workflow를 정리(종료)합니다:

```bash
# Workflow 강제 종료
docker exec temporal-exam-server tctl --address temporal:7233 \
  workflow terminate \
  --workflow_id hello-world-001 \
  --reason "실습 종료: Worker 없이 시작한 테스트"
```

**Web UI에서 확인**: 다시 Workflow 상세 화면을 보면 Status가 `Terminated`로 바뀌어 있습니다.
Event History에 `WorkflowExecutionTerminated` 이벤트가 추가되었습니다.

### 보너스: tctl로 추가 확인

```bash
# Workflow 상태 조회
docker exec temporal-exam-server tctl --address temporal:7233 \
  workflow describe \
  --workflow_id hello-world-001

# Namespace 정보 확인
docker exec temporal-exam-server tctl --address temporal:7233 \
  namespace describe --namespace default

# 서비스 정지 (실습 끝난 후)
# cd spring-temporal-exam-docker
# docker compose down
```

---

## 6. 실습 체크리스트

### 이 문서를 끝내고 할 수 있어야 하는 것

- [ ] Docker Compose로 Temporal 환경을 시작/중지할 수 있다
- [ ] 5개 서비스의 역할과 포트를 설명할 수 있다
- [ ] Temporal이 별도 PostgreSQL을 사용하는 이유를 설명할 수 있다
- [ ] Temporal Server 내부의 4가지 서비스를 나열할 수 있다
- [ ] Web UI에 접속하여 Workflow 목록을 조회할 수 있다
- [ ] Event History에서 각 이벤트의 의미를 설명할 수 있다
- [ ] Worker 없이 Workflow를 시작했을 때 어떤 일이 일어나는지 설명할 수 있다

### 핵심 Takeaway: 02-core-concepts.md와 연결

| 02-core-concepts에서 배운 개념 | 이 실습에서 직접 확인한 것 |
|-------------------------------|---------------------------|
| Temporal Server = 두뇌 | Docker Compose로 실제 Server를 띄우고 healthcheck로 확인 |
| Task Queue = 작업 대기열 | Worker 없이 시작한 Workflow가 Task Queue에서 대기하는 것을 확인 |
| Worker = 실행자 | Worker가 없으면 Workflow가 진행되지 않는 것을 직접 확인 |
| Event History = 모든 기록 | Web UI에서 WorkflowExecutionStarted, TaskScheduled 이벤트 확인 |
| Workflow 시작과 실행의 분리 | Client가 시작하고 Worker가 실행하는 구조를 직접 체험 |

### 포트 빠른 참조

```
  +--------------------------------------------+
  |  서비스                 | 접속 주소          |
  +------------------------+--------------------+
  | Temporal Web UI        | localhost:21088    |
  | Temporal gRPC          | localhost:21733    |
  | Temporal PostgreSQL    | localhost:21432    |
  | MySQL (애플리케이션)   | localhost:21306    |
  | Redis                  | localhost:21379    |
  +------------------------+--------------------+
```

---

> **선행 문서**: [02-core-concepts.md](./02-core-concepts.md) - Temporal 핵심 개념 5가지
> **다음 학습**: [03-durable-execution.md](./03-durable-execution.md) - Durable Execution 완전 이해 (Event History, Replay, 결정적 코드 규칙)
