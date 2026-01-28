# Temporal + Spring Boot 연동

## 이 문서에서 배우는 것

- Spring Boot 프로젝트에 Temporal SDK 통합하기
- Temporal Client, Worker 설정 방법
- Workflow와 Activity를 Spring Bean으로 구성하기
- Phase 2-A의 Saga 오케스트레이터를 Temporal로 전환하기
- 테스트 작성 방법

---

## 1. Spring Boot + Temporal 연동의 이점

### Phase 2-A 코드 vs Temporal 코드

```
┌─────────────────────────────────────────────────────────────────┐
│              Phase 2-A vs Temporal 코드 비교                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  [Phase 2-A - 약 150줄]                [Temporal - 약 50줄]      │
│                                                                  │
│  - Saga 상태 관리 코드                   - 비즈니스 로직만 작성    │
│  - Resilience4j 설정                     - 선언적 재시도 설정     │
│  - 보상 트랜잭션 로직                    - 내장 Saga 지원         │
│  - 예외 처리 코드                        - 자동 예외 처리         │
│  - 상태 저장/복구 코드                    - 자동 상태 관리        │
│                                                                  │
│  코드량: 150줄 + 설정 파일               코드량: 50줄            │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Spring + Temporal 연동의 장점

```
┌─────────────────────────────────────────────────────────────────┐
│                    Spring + Temporal 시너지                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. 의존성 주입 (DI)                                             │
│     - Activity 구현체에 Spring Bean 주입 가능                    │
│     - Repository, Service 등 기존 컴포넌트 재사용                │
│                                                                  │
│  2. Spring Boot 자동 설정                                        │
│     - @Configuration으로 깔끔한 설정                             │
│     - application.yml로 설정 외부화                              │
│                                                                  │
│  3. 기존 인프라 활용                                              │
│     - Spring Security, Actuator 등 통합                          │
│     - 기존 서비스 로직 재사용                                     │
│                                                                  │
│  4. 테스트 용이성                                                 │
│     - @SpringBootTest와 Temporal TestWorkflowEnvironment 통합    │
│     - Mockito 등 기존 테스트 도구 활용                            │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. 프로젝트 설정

### 2.1 의존성 추가

> ⚠️ **버전 참고**: 최신 버전은 [TECH-STACK.md](../../architecture/TECH-STACK.md) 참조
> - Temporal SDK: 1.32.1 (2026년 1월 기준)

```groovy
// build.gradle (orchestrator-temporal 모듈)
dependencies {
    // Temporal Java SDK
    implementation 'io.temporal:temporal-sdk:1.32.1'

    // Spring Boot
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'

    // 기존 common 모듈 (DTO, 인터페이스 등)
    implementation project(':common')

    // 테스트
    testImplementation 'io.temporal:temporal-testing:1.32.1'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
}
```

### 2.2 Temporal 서버 설정

> ⚠️ **주의**: `temporalio/auto-setup` 이미지는 **Deprecated** 되었습니다.

#### 방법 1: Temporal CLI (개발 환경 권장)

```bash
# 가장 간단한 방법 - SQLite 내장
docker run --rm -p 7233:7233 -p 8233:8233 \
  temporalio/temporal:latest \
  server start-dev --ip 0.0.0.0
```

#### 방법 2: Docker Compose (외부 DB 사용)

> **주의**: `temporalio/docker-compose` 저장소는 2026-01-05 아카이브되었습니다.
> 새로운 공식 예제는 [samples-server/compose](https://github.com/temporalio/samples-server/tree/main/compose)를 참조하세요.

```bash
# 새로운 공식 예제 저장소 사용
git clone https://github.com/temporalio/samples-server.git
cd samples-server/compose
docker-compose -f docker-compose-mysql.yml up -d
```

**직접 설정 시 예시**:
```yaml
# docker-compose.yml
services:
  # 기존 서비스들...
  mysql:
    image: mysql:8.0
    # ...

  # Temporal 서버
  temporal:
    image: temporalio/server:latest
    container_name: temporal
    ports:
      - "7233:7233"
    environment:
      - DB=mysql
      - DB_PORT=3306
      - MYSQL_USER=temporal
      - MYSQL_PWD=temporal
      - MYSQL_SEEDS=mysql
    depends_on:
      - mysql

  # Temporal Web UI
  temporal-ui:
    image: temporalio/ui:latest
    container_name: temporal-ui
    ports:
      - "8088:8080"
    environment:
      - TEMPORAL_ADDRESS=temporal:7233
      - TEMPORAL_CORS_ORIGINS=http://localhost:3000
    depends_on:
      - temporal

  # Temporal Admin Tools (선택)
  temporal-admin-tools:
    image: temporalio/admin-tools:latest
    container_name: temporal-admin-tools
    stdin_open: true
    tty: true
    environment:
      - TEMPORAL_ADDRESS=temporal:7233
    depends_on:
      - temporal
```

> **참고**: 프로덕션 환경은 [Temporal Deployment Guide](https://docs.temporal.io/self-hosted-guide/deployment) 참조

### 2.3 application.yml 설정

```yaml
# orchestrator-temporal/src/main/resources/application.yml
spring:
  application:
    name: orchestrator-temporal

server:
  port: 8090

# Temporal 설정
temporal:
  service-address: localhost:7233
  namespace: default
  task-queue: order-processing-queue

# 각 서비스 URL (Activity에서 사용)
services:
  order-url: http://localhost:8081
  inventory-url: http://localhost:8082
  payment-url: http://localhost:8083
  notification-url: http://localhost:8084
```

---

## 3. Temporal 클라이언트 설정

### 3.1 WorkflowClient 빈 설정

```java
package com.example.orchestrator.temporal.config;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TemporalClientConfig {

    @Value("${temporal.service-address}")
    private String temporalServiceAddress;

    @Value("${temporal.namespace}")
    private String namespace;

    /**
     * Temporal 서버와의 gRPC 연결을 관리하는 스텁
     */
    @Bean
    public WorkflowServiceStubs workflowServiceStubs() {
        return WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget(temporalServiceAddress)
                .build()
        );
    }

    /**
     * Workflow 시작, 조회, 시그널 전송 등을 담당하는 클라이언트
     */
    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs serviceStubs) {
        return WorkflowClient.newInstance(
            serviceStubs,
            WorkflowClientOptions.newBuilder()
                .setNamespace(namespace)
                .build()
        );
    }
}
```

### 3.2 연결 구조

```
┌─────────────────────────────────────────────────────────────────┐
│                     Temporal Client 구조                         │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                    Spring Boot Application                  │ │
│  │                                                             │ │
│  │   ┌─────────────────┐      ┌─────────────────────┐        │ │
│  │   │ WorkflowClient  │─────▶│WorkflowServiceStubs │        │ │
│  │   │                 │      │   (gRPC 연결)        │        │ │
│  │   └─────────────────┘      └──────────┬──────────┘        │ │
│  │                                        │                   │ │
│  └────────────────────────────────────────┼───────────────────┘ │
│                                           │                     │
│                                           ▼                     │
│                                 ┌─────────────────┐             │
│                                 │ Temporal Server │             │
│                                 │  localhost:7233 │             │
│                                 └─────────────────┘             │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. Worker 설정

### 4.1 Worker 팩토리 설정

```java
package com.example.orchestrator.temporal.config;

import com.example.orchestrator.temporal.activity.OrderActivitiesImpl;
import com.example.orchestrator.temporal.workflow.OrderWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class TemporalWorkerConfig {

    private final WorkflowClient workflowClient;
    private final OrderActivitiesImpl orderActivities;  // Spring Bean으로 주입!

    @Value("${temporal.task-queue}")
    private String taskQueue;

    private WorkerFactory workerFactory;

    @Bean
    public WorkerFactory workerFactory() {
        this.workerFactory = WorkerFactory.newInstance(workflowClient);
        return workerFactory;
    }

    @Bean
    public Worker orderWorker(WorkerFactory workerFactory) {
        // Worker 옵션 설정
        WorkerOptions options = WorkerOptions.newBuilder()
            .setMaxConcurrentActivityExecutionSize(100)  // 동시 Activity 실행 수
            .setMaxConcurrentWorkflowTaskExecutionSize(100)  // 동시 Workflow 태스크 수
            .build();

        Worker worker = workerFactory.newWorker(taskQueue, options);

        // Workflow 구현체 등록 (클래스 타입)
        worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);

        // Activity 구현체 등록 (인스턴스 - Spring Bean 사용!)
        worker.registerActivitiesImplementations(orderActivities);

        log.info("Worker 등록 완료 - Task Queue: {}", taskQueue);
        return worker;
    }

    @PostConstruct
    public void startWorker() {
        log.info("Temporal Worker 시작...");
        workerFactory().start();
    }

    @PreDestroy
    public void stopWorker() {
        log.info("Temporal Worker 종료...");
        if (workerFactory != null) {
            workerFactory.shutdown();
        }
    }
}
```

### 4.2 Worker 동작 다이어그램

```
┌─────────────────────────────────────────────────────────────────┐
│                      Worker 등록 및 실행                         │
│                                                                  │
│  Spring Boot 시작                                                │
│         │                                                        │
│         ▼                                                        │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  @PostConstruct                                           │   │
│  │                                                           │   │
│  │  1. WorkerFactory 생성                                    │   │
│  │         │                                                 │   │
│  │         ▼                                                 │   │
│  │  2. Worker 생성 (Task Queue 지정)                         │   │
│  │         │                                                 │   │
│  │         ▼                                                 │   │
│  │  3. Workflow 구현체 등록                                   │   │
│  │     - OrderWorkflowImpl.class                            │   │
│  │         │                                                 │   │
│  │         ▼                                                 │   │
│  │  4. Activity 구현체 등록                                   │   │
│  │     - orderActivities (Spring Bean)                      │   │
│  │         │                                                 │   │
│  │         ▼                                                 │   │
│  │  5. WorkerFactory.start()                                │   │
│  │     - Temporal Server에 연결                              │   │
│  │     - Task Queue 폴링 시작                                │   │
│  │                                                           │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 5. Workflow 구현

### 5.1 Workflow 인터페이스 정의

```java
package com.example.orchestrator.temporal.workflow;

import com.example.common.dto.OrderRequest;
import com.example.common.dto.OrderResult;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 주문 처리 Workflow 인터페이스
 *
 * Temporal의 Workflow는 인터페이스로 정의합니다.
 * - @WorkflowMethod: Workflow의 진입점 (메인 메서드)
 * - @SignalMethod: 외부에서 Workflow에 이벤트 전달
 * - @QueryMethod: Workflow 상태 조회 (읽기 전용)
 */
@WorkflowInterface
public interface OrderWorkflow {

    /**
     * 주문 처리 메인 메서드
     *
     * @param request 주문 요청
     * @return 주문 처리 결과
     */
    @WorkflowMethod
    OrderResult processOrder(OrderRequest request);

    /**
     * 주문 취소 시그널
     * 실행 중인 Workflow에 취소 요청을 보낼 수 있습니다.
     *
     * @param reason 취소 사유
     */
    @SignalMethod
    void cancelOrder(String reason);

    /**
     * 현재 주문 상태 조회
     * Workflow 실행 중에도 상태를 조회할 수 있습니다.
     *
     * @return 현재 주문 상태
     */
    @QueryMethod
    String getOrderStatus();
}
```

### 5.2 Workflow 구현체

```java
package com.example.orchestrator.temporal.workflow;

import com.example.common.dto.OrderRequest;
import com.example.common.dto.OrderResult;
import com.example.orchestrator.temporal.activity.OrderActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * 주문 처리 Workflow 구현체
 *
 * Phase 2-A에서 PureSagaOrchestrator로 작성했던 로직을
 * Temporal Workflow로 전환한 버전입니다.
 *
 * 변경점:
 * - 상태 관리 코드 제거 (Temporal이 자동 관리)
 * - Resilience4j 설정 제거 (Temporal RetryOptions로 대체)
 * - 보상 트랜잭션 코드 단순화 (Saga 객체 사용)
 */
@Slf4j
public class OrderWorkflowImpl implements OrderWorkflow {

    // Activity stub 생성 (Workflow에서 Activity 호출 시 사용)
    private final OrderActivities activities;

    // 현재 상태 (Query로 조회 가능)
    private String currentStatus = "INITIALIZED";
    private boolean cancelRequested = false;
    private String cancelReason = null;

    // 처리 결과 저장
    private String orderId;
    private String reservationId;
    private String paymentId;

    public OrderWorkflowImpl() {
        // Activity 옵션 설정
        ActivityOptions activityOptions = ActivityOptions.newBuilder()
            // 타임아웃 설정
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .setScheduleToCloseTimeout(Duration.ofMinutes(10))

            // 재시도 설정 (Resilience4j 대체)
            .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setBackoffCoefficient(2.0)
                .setMaximumInterval(Duration.ofMinutes(1))
                .setMaximumAttempts(5)
                // 재시도하지 않을 예외 타입
                .setDoNotRetry(
                    IllegalArgumentException.class,
                    NullPointerException.class
                )
                .build())
            .build();

        // Activity stub 생성
        this.activities = Workflow.newActivityStub(OrderActivities.class, activityOptions);
    }

    @Override
    public OrderResult processOrder(OrderRequest request) {
        log.info("주문 Workflow 시작: {}", request);

        // Saga 객체 생성 (보상 트랜잭션 관리)
        Saga saga = new Saga(new Saga.Options.Builder()
            .setParallelCompensation(false)  // 보상 순차 실행
            .setContinueWithError(true)      // 보상 실패해도 계속
            .build());

        try {
            // ========== Step 1: 주문 생성 ==========
            currentStatus = "CREATING_ORDER";
            log.info("Step 1: 주문 생성");

            orderId = activities.createOrder(request);
            log.info("주문 생성 완료: {}", orderId);

            // 보상 트랜잭션 등록 (주문 취소)
            saga.addCompensation(() -> {
                log.info("보상: 주문 취소 - {}", orderId);
                activities.cancelOrder(orderId);
            });

            // 취소 요청 확인
            if (cancelRequested) {
                throw new RuntimeException("주문 취소 요청됨: " + cancelReason);
            }

            // ========== Step 2: 재고 예약 ==========
            currentStatus = "RESERVING_STOCK";
            log.info("Step 2: 재고 예약");

            reservationId = activities.reserveStock(orderId, request.getProductId(), request.getQuantity());
            log.info("재고 예약 완료: {}", reservationId);

            // 보상 트랜잭션 등록 (재고 복구)
            saga.addCompensation(() -> {
                log.info("보상: 재고 복구 - {}", reservationId);
                activities.releaseStock(reservationId);
            });

            // 취소 요청 확인
            if (cancelRequested) {
                throw new RuntimeException("주문 취소 요청됨: " + cancelReason);
            }

            // ========== Step 3: 결제 처리 ==========
            currentStatus = "PROCESSING_PAYMENT";
            log.info("Step 3: 결제 처리");

            paymentId = activities.processPayment(orderId, request.getAmount());
            log.info("결제 완료: {}", paymentId);

            // 보상 트랜잭션 등록 (결제 환불)
            saga.addCompensation(() -> {
                log.info("보상: 결제 환불 - {}", paymentId);
                activities.refundPayment(paymentId);
            });

            // ========== Step 4: 주문 확정 ==========
            currentStatus = "CONFIRMING_ORDER";
            log.info("Step 4: 주문 확정");

            activities.confirmOrder(orderId);
            log.info("주문 확정 완료");

            // ========== Step 5: 알림 발송 (실패해도 무시) ==========
            currentStatus = "SENDING_NOTIFICATION";
            try {
                activities.sendNotification(orderId, request.getCustomerId());
            } catch (Exception e) {
                log.warn("알림 발송 실패 (무시됨): {}", e.getMessage());
            }

            // ========== 완료 ==========
            currentStatus = "COMPLETED";
            log.info("주문 Workflow 완료: {}", orderId);

            return OrderResult.success(orderId, paymentId);

        } catch (ActivityFailure e) {
            // Activity 실패 시 보상 트랜잭션 실행
            currentStatus = "COMPENSATING";
            log.error("주문 처리 실패, 보상 트랜잭션 실행: {}", e.getMessage());

            saga.compensate();

            currentStatus = "FAILED";
            return OrderResult.failure(e.getCause().getMessage());

        } catch (Exception e) {
            // 기타 예외 시에도 보상 트랜잭션 실행
            currentStatus = "COMPENSATING";
            log.error("주문 처리 실패, 보상 트랜잭션 실행: {}", e.getMessage());

            saga.compensate();

            currentStatus = "FAILED";
            return OrderResult.failure(e.getMessage());
        }
    }

    @Override
    public void cancelOrder(String reason) {
        log.info("주문 취소 시그널 수신: {}", reason);
        this.cancelRequested = true;
        this.cancelReason = reason;
    }

    @Override
    public String getOrderStatus() {
        return String.format("Status: %s, OrderId: %s, Cancelled: %s",
            currentStatus, orderId, cancelRequested);
    }
}
```

### 5.3 Phase 2-A 코드와 비교

```
┌─────────────────────────────────────────────────────────────────┐
│              Phase 2-A vs Temporal Workflow 비교                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  [Phase 2-A - PureSagaOrchestrator]                             │
│                                                                  │
│  public OrderSagaResult execute(OrderSagaRequest request) {     │
│      String orderId = null;                                     │
│      String reservationId = null;                               │
│      String paymentId = null;                                   │
│                                                                  │
│      try {                                                       │
│          // 각 단계마다 상태 저장 필요 (직접 구현)                  │
│          sagaState.setStatus(STEP_1);                           │
│          sagaRepository.save(sagaState);                        │
│                                                                  │
│          orderId = orderClient.createOrder(request);            │
│          // ... 반복                                             │
│                                                                  │
│      } catch (Exception e) {                                     │
│          // 보상 로직 직접 구현                                   │
│          if (paymentId != null) paymentClient.refund(paymentId);│
│          if (reservationId != null) inventoryClient.cancel(...);│
│          if (orderId != null) orderClient.cancel(orderId);      │
│      }                                                           │
│  }                                                               │
│                                                                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  [Temporal - OrderWorkflowImpl]                                  │
│                                                                  │
│  public OrderResult processOrder(OrderRequest request) {        │
│      Saga saga = new Saga(...);                                 │
│                                                                  │
│      orderId = activities.createOrder(request);                 │
│      saga.addCompensation(() -> activities.cancelOrder(orderId));│
│                                                                  │
│      // 상태 관리? 필요 없음! (Temporal이 자동 관리)               │
│      // 재시도? 필요 없음! (ActivityOptions에서 설정)             │
│      // 보상 실행? saga.compensate() 한 줄!                      │
│  }                                                               │
│                                                                  │
│  제거된 것:                                                       │
│  - SagaState 엔티티                                              │
│  - SagaRepository                                                │
│  - 상태 저장/복구 로직                                            │
│  - Resilience4j 설정 파일                                        │
│  - 수동 보상 트랜잭션 로직                                        │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 6. Activity 구현

### 6.1 Activity 인터페이스 정의

```java
package com.example.orchestrator.temporal.activity;

import com.example.common.dto.OrderRequest;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * 주문 처리 Activity 인터페이스
 *
 * Activity는 실제 외부 시스템과 통신하는 로직을 담당합니다.
 * - REST API 호출
 * - 데이터베이스 접근
 * - 메시지 발송
 */
@ActivityInterface
public interface OrderActivities {

    // ========== 정상 처리 Activity ==========

    @ActivityMethod
    String createOrder(OrderRequest request);

    @ActivityMethod
    String reserveStock(String orderId, Long productId, int quantity);

    @ActivityMethod
    String processPayment(String orderId, Long amount);

    @ActivityMethod
    void confirmOrder(String orderId);

    @ActivityMethod
    void sendNotification(String orderId, Long customerId);

    // ========== 보상 트랜잭션 Activity ==========

    @ActivityMethod
    void cancelOrder(String orderId);

    @ActivityMethod
    void releaseStock(String reservationId);

    @ActivityMethod
    void refundPayment(String paymentId);
}
```

### 6.2 Activity 구현체 (Spring Bean)

```java
package com.example.orchestrator.temporal.activity;

import com.example.common.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 주문 처리 Activity 구현체
 *
 * Spring Bean으로 등록하여 DI를 활용합니다.
 * - RestTemplate, WebClient 등 HTTP 클라이언트 주입
 * - Repository 주입 가능
 * - 다른 Service 주입 가능
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderActivitiesImpl implements OrderActivities {

    private final RestTemplate restTemplate;

    @Value("${services.order-url}")
    private String orderServiceUrl;

    @Value("${services.inventory-url}")
    private String inventoryServiceUrl;

    @Value("${services.payment-url}")
    private String paymentServiceUrl;

    @Value("${services.notification-url}")
    private String notificationServiceUrl;

    // ========== 정상 처리 Activity ==========

    @Override
    public String createOrder(OrderRequest request) {
        log.info("Activity: 주문 생성 - {}", request);

        OrderResponse response = restTemplate.postForObject(
            orderServiceUrl + "/orders",
            request,
            OrderResponse.class
        );

        if (response == null) {
            throw new RuntimeException("주문 생성 실패: 응답이 없습니다");
        }

        log.info("Activity: 주문 생성 완료 - orderId: {}", response.getOrderId());
        return response.getOrderId();
    }

    @Override
    public String reserveStock(String orderId, Long productId, int quantity) {
        log.info("Activity: 재고 예약 - orderId: {}, productId: {}, quantity: {}",
            orderId, productId, quantity);

        ReservationRequest request = new ReservationRequest(orderId, productId, quantity);
        ReservationResponse response = restTemplate.postForObject(
            inventoryServiceUrl + "/reservations",
            request,
            ReservationResponse.class
        );

        if (response == null) {
            throw new RuntimeException("재고 예약 실패: 응답이 없습니다");
        }

        log.info("Activity: 재고 예약 완료 - reservationId: {}", response.getReservationId());
        return response.getReservationId();
    }

    @Override
    public String processPayment(String orderId, Long amount) {
        log.info("Activity: 결제 처리 - orderId: {}, amount: {}", orderId, amount);

        PaymentRequest request = new PaymentRequest(orderId, amount);
        PaymentResponse response = restTemplate.postForObject(
            paymentServiceUrl + "/payments",
            request,
            PaymentResponse.class
        );

        if (response == null) {
            throw new RuntimeException("결제 처리 실패: 응답이 없습니다");
        }

        log.info("Activity: 결제 처리 완료 - paymentId: {}", response.getPaymentId());
        return response.getPaymentId();
    }

    @Override
    public void confirmOrder(String orderId) {
        log.info("Activity: 주문 확정 - orderId: {}", orderId);

        restTemplate.put(
            orderServiceUrl + "/orders/{orderId}/confirm",
            null,
            orderId
        );

        log.info("Activity: 주문 확정 완료");
    }

    @Override
    public void sendNotification(String orderId, Long customerId) {
        log.info("Activity: 알림 발송 - orderId: {}, customerId: {}", orderId, customerId);

        NotificationRequest request = new NotificationRequest(orderId, customerId, "ORDER_CONFIRMED");
        restTemplate.postForObject(
            notificationServiceUrl + "/notifications",
            request,
            Void.class
        );

        log.info("Activity: 알림 발송 완료");
    }

    // ========== 보상 트랜잭션 Activity ==========

    @Override
    public void cancelOrder(String orderId) {
        log.info("Activity: 주문 취소 - orderId: {}", orderId);

        restTemplate.put(
            orderServiceUrl + "/orders/{orderId}/cancel",
            null,
            orderId
        );

        log.info("Activity: 주문 취소 완료");
    }

    @Override
    public void releaseStock(String reservationId) {
        log.info("Activity: 재고 복구 - reservationId: {}", reservationId);

        restTemplate.delete(
            inventoryServiceUrl + "/reservations/{reservationId}",
            reservationId
        );

        log.info("Activity: 재고 복구 완료");
    }

    @Override
    public void refundPayment(String paymentId) {
        log.info("Activity: 결제 환불 - paymentId: {}", paymentId);

        restTemplate.postForObject(
            paymentServiceUrl + "/payments/{paymentId}/refund",
            null,
            Void.class,
            paymentId
        );

        log.info("Activity: 결제 환불 완료");
    }
}
```

### 6.3 RestTemplate 설정

```java
package com.example.orchestrator.temporal.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(10))
            .build();
    }
}
```

---

## 7. API Controller (Workflow 시작)

### 7.1 Controller 구현

```java
package com.example.orchestrator.temporal.controller;

import com.example.common.dto.OrderRequest;
import com.example.common.dto.OrderResult;
import com.example.orchestrator.temporal.workflow.OrderWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final WorkflowClient workflowClient;

    @Value("${temporal.task-queue}")
    private String taskQueue;

    /**
     * 주문 생성 (동기 - 완료까지 대기)
     */
    @PostMapping
    public ResponseEntity<OrderResult> createOrder(@RequestBody OrderRequest request) {
        log.info("주문 요청 수신: {}", request);

        // Workflow ID 생성 (고유해야 함)
        String workflowId = "order-" + UUID.randomUUID().toString();

        // Workflow 옵션 설정
        WorkflowOptions options = WorkflowOptions.newBuilder()
            .setTaskQueue(taskQueue)
            .setWorkflowId(workflowId)
            .setWorkflowExecutionTimeout(Duration.ofMinutes(30))  // 전체 타임아웃
            .build();

        // Workflow stub 생성
        OrderWorkflow workflow = workflowClient.newWorkflowStub(
            OrderWorkflow.class,
            options
        );

        // Workflow 동기 실행 (완료까지 대기)
        OrderResult result = workflow.processOrder(request);

        log.info("주문 처리 완료: {}", result);
        return ResponseEntity.ok(result);
    }

    /**
     * 주문 생성 (비동기 - 즉시 반환)
     */
    @PostMapping("/async")
    public ResponseEntity<WorkflowStartResponse> createOrderAsync(@RequestBody OrderRequest request) {
        log.info("주문 요청 수신 (비동기): {}", request);

        String workflowId = "order-" + UUID.randomUUID().toString();

        WorkflowOptions options = WorkflowOptions.newBuilder()
            .setTaskQueue(taskQueue)
            .setWorkflowId(workflowId)
            .build();

        OrderWorkflow workflow = workflowClient.newWorkflowStub(
            OrderWorkflow.class,
            options
        );

        // Workflow 비동기 실행 (즉시 반환)
        WorkflowClient.start(workflow::processOrder, request);

        log.info("주문 Workflow 시작됨: {}", workflowId);
        return ResponseEntity.accepted()
            .body(new WorkflowStartResponse(workflowId, "STARTED"));
    }

    /**
     * 주문 상태 조회 (Query)
     */
    @GetMapping("/{workflowId}/status")
    public ResponseEntity<String> getOrderStatus(@PathVariable String workflowId) {
        log.info("주문 상태 조회: {}", workflowId);

        // 기존 Workflow에 연결
        OrderWorkflow workflow = workflowClient.newWorkflowStub(
            OrderWorkflow.class,
            workflowId
        );

        // Query 실행
        String status = workflow.getOrderStatus();

        return ResponseEntity.ok(status);
    }

    /**
     * 주문 취소 요청 (Signal)
     */
    @PostMapping("/{workflowId}/cancel")
    public ResponseEntity<String> cancelOrder(
            @PathVariable String workflowId,
            @RequestParam(defaultValue = "고객 요청") String reason) {
        log.info("주문 취소 요청: workflowId={}, reason={}", workflowId, reason);

        // 기존 Workflow에 연결
        OrderWorkflow workflow = workflowClient.newWorkflowStub(
            OrderWorkflow.class,
            workflowId
        );

        // Signal 전송
        workflow.cancelOrder(reason);

        return ResponseEntity.ok("취소 요청이 전송되었습니다");
    }

    /**
     * Workflow 결과 조회 (완료된 경우)
     */
    @GetMapping("/{workflowId}/result")
    public ResponseEntity<OrderResult> getOrderResult(@PathVariable String workflowId) {
        log.info("주문 결과 조회: {}", workflowId);

        // Untyped stub으로 결과 조회
        WorkflowStub workflowStub = workflowClient.newUntypedWorkflowStub(workflowId);

        // 결과 조회 (완료될 때까지 대기)
        OrderResult result = workflowStub.getResult(OrderResult.class);

        return ResponseEntity.ok(result);
    }

    // Response DTO
    record WorkflowStartResponse(String workflowId, String status) {}
}
```

### 7.2 API 사용 예시

```bash
# 1. 주문 생성 (동기)
curl -X POST http://localhost:8090/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": 1,
    "productId": 100,
    "quantity": 2,
    "amount": 50000
  }'

# 2. 주문 생성 (비동기)
curl -X POST http://localhost:8090/api/orders/async \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": 1,
    "productId": 100,
    "quantity": 2,
    "amount": 50000
  }'
# Response: { "workflowId": "order-xxxx", "status": "STARTED" }

# 3. 주문 상태 조회
curl http://localhost:8090/api/orders/order-xxxx/status

# 4. 주문 취소
curl -X POST "http://localhost:8090/api/orders/order-xxxx/cancel?reason=변심"

# 5. 주문 결과 조회
curl http://localhost:8090/api/orders/order-xxxx/result
```

---

## 8. 테스트 작성

### 8.1 Workflow 단위 테스트

```java
package com.example.orchestrator.temporal.workflow;

import com.example.common.dto.OrderRequest;
import com.example.common.dto.OrderResult;
import com.example.orchestrator.temporal.activity.OrderActivities;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class OrderWorkflowTest {

    @RegisterExtension
    public static final TestWorkflowExtension testWorkflowExtension =
        TestWorkflowExtension.newBuilder()
            .setWorkflowTypes(OrderWorkflowImpl.class)
            .setDoNotStart(true)
            .build();

    @Mock
    private OrderActivities mockActivities;

    @Test
    void 정상_주문_처리_테스트() {
        // Given
        Worker worker = testWorkflowExtension.getWorker();
        worker.registerActivitiesImplementations(mockActivities);
        testWorkflowExtension.getTestEnvironment().start();

        // Mock 설정
        when(mockActivities.createOrder(any())).thenReturn("order-123");
        when(mockActivities.reserveStock(anyString(), anyLong(), anyInt()))
            .thenReturn("reservation-456");
        when(mockActivities.processPayment(anyString(), anyLong()))
            .thenReturn("payment-789");

        // When
        OrderWorkflow workflow = testWorkflowExtension.getWorkflowClient()
            .newWorkflowStub(
                OrderWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowExtension.getTaskQueue())
                    .build()
            );

        OrderRequest request = new OrderRequest(1L, 100L, 2, 50000L);
        OrderResult result = workflow.processOrder(request);

        // Then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOrderId()).isEqualTo("order-123");

        // Activity 호출 검증
        verify(mockActivities).createOrder(any());
        verify(mockActivities).reserveStock("order-123", 100L, 2);
        verify(mockActivities).processPayment("order-123", 50000L);
        verify(mockActivities).confirmOrder("order-123");

        // 보상 트랜잭션은 호출되지 않아야 함
        verify(mockActivities, never()).cancelOrder(anyString());
        verify(mockActivities, never()).releaseStock(anyString());
        verify(mockActivities, never()).refundPayment(anyString());
    }

    @Test
    void 결제_실패시_보상_트랜잭션_테스트() {
        // Given
        Worker worker = testWorkflowExtension.getWorker();
        worker.registerActivitiesImplementations(mockActivities);
        testWorkflowExtension.getTestEnvironment().start();

        // Mock 설정 - 결제에서 실패
        when(mockActivities.createOrder(any())).thenReturn("order-123");
        when(mockActivities.reserveStock(anyString(), anyLong(), anyInt()))
            .thenReturn("reservation-456");
        when(mockActivities.processPayment(anyString(), anyLong()))
            .thenThrow(new RuntimeException("결제 실패"));

        // When
        OrderWorkflow workflow = testWorkflowExtension.getWorkflowClient()
            .newWorkflowStub(
                OrderWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowExtension.getTaskQueue())
                    .build()
            );

        OrderRequest request = new OrderRequest(1L, 100L, 2, 50000L);
        OrderResult result = workflow.processOrder(request);

        // Then
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("결제 실패");

        // 보상 트랜잭션 검증 (역순으로 호출)
        verify(mockActivities).releaseStock("reservation-456");
        verify(mockActivities).cancelOrder("order-123");

        // 환불은 결제 전이므로 호출되지 않음
        verify(mockActivities, never()).refundPayment(anyString());
    }

    @Test
    void 취소_시그널_테스트() {
        // Given
        Worker worker = testWorkflowExtension.getWorker();
        worker.registerActivitiesImplementations(mockActivities);
        testWorkflowExtension.getTestEnvironment().start();

        when(mockActivities.createOrder(any())).thenReturn("order-123");

        // When
        OrderWorkflow workflow = testWorkflowExtension.getWorkflowClient()
            .newWorkflowStub(
                OrderWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowExtension.getTaskQueue())
                    .build()
            );

        // 비동기로 시작
        WorkflowClient.start(workflow::processOrder, new OrderRequest(1L, 100L, 2, 50000L));

        // 취소 시그널 전송
        workflow.cancelOrder("테스트 취소");

        // 결과 대기
        // ... (실제로는 결과를 기다리는 로직 필요)
    }
}
```

### 8.2 통합 테스트 (Spring Boot)

```java
package com.example.orchestrator.temporal;

import com.example.common.dto.OrderRequest;
import com.example.common.dto.OrderResult;
import com.example.orchestrator.temporal.controller.OrderController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrderWorkflowIntegrationTest {

    // Temporal CLI로 dev server 실행 (권장)
    @Container
    static GenericContainer<?> temporal = new GenericContainer<>("temporalio/temporal:latest")
        .withExposedPorts(7233)
        .withCommand("server", "start-dev", "--ip", "0.0.0.0");

    @DynamicPropertySource
    static void temporalProperties(DynamicPropertyRegistry registry) {
        registry.add("temporal.service-address",
            () -> temporal.getHost() + ":" + temporal.getMappedPort(7233));
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void 주문_API_통합_테스트() {
        // Given
        OrderRequest request = new OrderRequest(1L, 100L, 2, 50000L);

        // When
        ResponseEntity<OrderController.WorkflowStartResponse> response = restTemplate.postForEntity(
            "/api/orders/async",
            request,
            OrderController.WorkflowStartResponse.class
        );

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().workflowId()).startsWith("order-");
        assertThat(response.getBody().status()).isEqualTo("STARTED");
    }
}
```

---

## 9. Temporal Web UI 활용

### 9.1 Web UI 접속

브라우저에서 `http://localhost:8088` 접속

### 9.2 주요 기능

```
┌─────────────────────────────────────────────────────────────────┐
│                    Temporal Web UI 기능                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. Workflow 목록 조회                                           │
│     - 실행 중/완료/실패 Workflow 목록                             │
│     - 필터링 (상태, 시간, Workflow Type 등)                       │
│                                                                  │
│  2. Workflow 상세 조회                                           │
│     - Event History (모든 이벤트 시간순 나열)                     │
│     - Input/Output 데이터 확인                                   │
│     - 실패 원인 상세 정보                                         │
│                                                                  │
│  3. Workflow 제어                                                │
│     - Signal 전송                                                │
│     - Terminate (강제 종료)                                      │
│     - Reset (특정 시점으로 되돌리기)                               │
│                                                                  │
│  4. Query 실행                                                   │
│     - 실행 중인 Workflow 상태 조회                                │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 9.3 디버깅 예시

```
┌─────────────────────────────────────────────────────────────────┐
│                  Event History 예시                              │
│                                                                  │
│  Time          Event                    Details                  │
│  ─────────────────────────────────────────────────────────────  │
│  10:00:00.000  WorkflowExecutionStarted  input: {...}           │
│  10:00:00.010  WorkflowTaskScheduled                            │
│  10:00:00.050  WorkflowTaskCompleted                            │
│  10:00:00.051  ActivityTaskScheduled     createOrder            │
│  10:00:00.100  ActivityTaskStarted       workerIdentity: w1     │
│  10:00:01.500  ActivityTaskCompleted     result: "order-123"    │
│  10:00:01.501  ActivityTaskScheduled     reserveStock           │
│  10:00:01.550  ActivityTaskStarted                              │
│  10:00:02.000  ActivityTaskFailed        ❌ 재고 부족            │
│  10:00:02.001  ActivityTaskScheduled     reserveStock (재시도)  │
│  10:00:03.000  ActivityTaskStarted                              │
│  10:00:03.500  ActivityTaskCompleted     result: "res-456"      │
│  ... (계속)                                                      │
│                                                                  │
│  → 10:00:02에 재고 부족으로 실패했지만, 자동 재시도로 성공!        │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 10. 운영 고려사항

### 10.1 Workflow ID 전략

```java
// 좋은 예: 의미 있고 고유한 ID
String workflowId = "order-" + orderId;
String workflowId = "payment-" + paymentId + "-" + customerId;

// 나쁜 예: 중복 가능성 있는 ID
String workflowId = "order";  // 중복!
String workflowId = String.valueOf(System.currentTimeMillis());  // 충돌 가능
```

### 10.2 타임아웃 설정 가이드

```java
WorkflowOptions.newBuilder()
    // Workflow 전체 실행 시간 (비즈니스 요구사항에 따라)
    .setWorkflowExecutionTimeout(Duration.ofHours(24))

    // Workflow 단일 실행 시간 (재시도 제외)
    .setWorkflowRunTimeout(Duration.ofHours(1))

    // Workflow Task 처리 시간 (보통 짧게)
    .setWorkflowTaskTimeout(Duration.ofSeconds(10))
    .build();

ActivityOptions.newBuilder()
    // Activity 전체 시간 (재시도 포함)
    .setScheduleToCloseTimeout(Duration.ofMinutes(10))

    // Activity 단일 실행 시간
    .setStartToCloseTimeout(Duration.ofMinutes(1))

    // Heartbeat 주기 (긴 작업에서 활성 상태 보고)
    .setHeartbeatTimeout(Duration.ofSeconds(30))
    .build();
```

### 10.3 버전 관리 (Versioning)

Workflow 코드 변경 시 기존 실행 중인 Workflow와의 호환성:

```java
public OrderResult processOrder(OrderRequest request) {
    // 버전 확인
    int version = Workflow.getVersion("AddNotification", Workflow.DEFAULT_VERSION, 1);

    orderId = activities.createOrder(request);
    reservationId = activities.reserveStock(orderId);
    paymentId = activities.processPayment(orderId);
    activities.confirmOrder(orderId);

    // 버전 1에서 추가된 기능
    if (version >= 1) {
        activities.sendNotification(orderId);
    }

    return OrderResult.success(orderId);
}
```

### 10.4 모니터링 설정

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  metrics:
    tags:
      application: orchestrator-temporal

# Temporal 메트릭도 Prometheus로 수집 가능
```

---

## 11. 실습 과제

### 과제 1: 기본 Workflow 구현

1. `OrderWorkflow` 인터페이스와 구현체 작성
2. `OrderActivities` 인터페이스와 구현체 작성
3. Worker 설정 및 실행 확인

### 과제 2: API 연동

1. `OrderController` 구현
2. 동기/비동기 주문 API 테스트
3. Query/Signal API 테스트

### 과제 3: 장애 시나리오 테스트

1. Activity에서 의도적으로 예외 발생
2. 자동 재시도 확인
3. 보상 트랜잭션 실행 확인
4. Temporal Web UI에서 Event History 확인

### 과제 4: Phase 2-A 코드와 비교

1. Phase 2-A의 `PureSagaOrchestrator` 코드량 확인
2. Temporal 버전의 코드량 확인
3. 제거된 코드 목록 정리
4. 개선된 점 문서화

---

## 12. 문제 해결 (Troubleshooting)

### 자주 발생하는 문제들

```
┌─────────────────────────────────────────────────────────────────┐
│                    자주 발생하는 문제                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. "Workflow execution already started"                        │
│     - 같은 Workflow ID로 이미 실행 중                            │
│     - 해결: 고유한 Workflow ID 사용                              │
│                                                                  │
│  2. "Activity task timed out"                                   │
│     - Activity 실행 시간 초과                                    │
│     - 해결: 타임아웃 값 늘리기 또는 로직 최적화                   │
│                                                                  │
│  3. "Worker is not polling task queue"                          │
│     - Worker가 Task Queue를 폴링하지 않음                        │
│     - 해결: Worker 시작 확인, Task Queue 이름 확인               │
│                                                                  │
│  4. "Non-deterministic workflow"                                │
│     - Workflow에서 비결정적 코드 사용                            │
│     - 해결: Random, 시간, I/O를 Activity로 이동                  │
│                                                                  │
│  5. Spring Bean 주입 안 됨 (Activity에서)                        │
│     - Activity 구현체를 new로 생성한 경우                         │
│     - 해결: Spring Bean으로 등록 후 Worker에 등록                │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 참고 자료

- [Temporal Java SDK 문서](https://docs.temporal.io/dev-guide/java)
- [Temporal Spring Boot 예제](https://github.com/temporalio/samples-java)
- [Temporal Best Practices](https://docs.temporal.io/dev-guide/java/observability)
- [Temporal 테스트 가이드](https://docs.temporal.io/dev-guide/java/testing)
- [Temporal 운영 가이드](https://docs.temporal.io/cloud/operate-temporal-cloud)

---

## 마무리

Phase 3을 통해 다음을 학습했습니다:

1. **Temporal의 핵심 개념**: Workflow, Activity, Worker, Task Queue
2. **Spring Boot 연동**: 설정, DI 활용, 테스트
3. **Phase 2-A 대비 개선점**: 코드량 감소, 자동 상태 관리, 내장 재시도/보상

Phase 2-A에서 직접 구현했던 복잡한 로직들이 Temporal을 통해 얼마나 단순해지는지 체감하셨을 것입니다.

---

## 전체 학습 완료

축하합니다! Spring Boot + Temporal 학습 과정을 모두 완료했습니다.

### 학습 경로 요약

```
┌─────────────────────────────────────────────────────────────────────┐
│                    전체 학습 경로 완료                                │
│                                                                      │
│   Phase 1: 프로젝트 기초                                             │
│   ├── Gradle 멀티모듈                                                │
│   ├── Flyway 마이그레이션                                            │
│   ├── Spring Profiles                                                │
│   └── Docker Compose                                                 │
│                                                                      │
│   Phase 2-A: 분산 트랜잭션 직접 구현                                  │
│   ├── Saga 패턴 (Orchestration)                                      │
│   ├── Resilience4j (재시도, 서킷브레이커)                            │
│   ├── 분산 락 / 낙관적 락                                            │
│   ├── 멱등성, 유효성 검증, 예외 처리                                 │
│   └── MDC 로깅                                                       │
│                                                                      │
│   Phase 2-B: 인프라 및 모니터링                                       │
│   ├── Redis (캐싱, Stream, Redisson)                                 │
│   ├── Outbox 패턴                                                    │
│   ├── OpenTelemetry + Zipkin                                         │
│   └── Prometheus, Grafana, Loki, Alertmanager                        │
│                                                                      │
│   Phase 3: Temporal 워크플로우 엔진                                   │
│   ├── Temporal 핵심 개념                                             │
│   └── Spring Boot + Temporal 연동 ← 현재 위치                        │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 다음 단계: 실습 프로젝트

이제 학습한 내용을 바탕으로 실제 프로젝트를 구현해 보세요!

**권장 실습 순서:**

1. **Phase 1 적용**: 멀티모듈 프로젝트 구조 생성
2. **Phase 2-A 경험**: Saga 패턴을 직접 구현하여 복잡성 체감
3. **Phase 3 전환**: 동일한 로직을 Temporal로 재구현하여 차이 비교
4. **Phase 2-B 통합**: 모니터링 및 관측성(Observability) 구성

**실습 시나리오 예시:**
- 주문 → 재고 확인 → 결제 → 배송 요청
- 각 단계의 실패/보상 처리
- 분산 추적 및 메트릭 모니터링

### 학습 자료 다시 보기

- [학습 개요로 돌아가기](../00-overview.md)
- [Phase 1: 프로젝트 기초](../phase1/01-gradle-multimodule.md)
- [Phase 2-A: Saga 패턴](../phase2a/01-saga-pattern.md)
- [Phase 2-B: Redis 기초](../phase2b/01-redis-basics.md)

---

## 미래 프로젝트 아이디어 (우선순위: 최하위)

### Temporal Lite - 경량 워크플로우 엔진

Temporal과 Camunda의 장점을 결합한 한국형 경량 워크플로우 엔진 개발 아이디어입니다.

```
┌─────────────────────────────────────────────────────────────────┐
│                    Temporal Lite 구상                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  목표:                                                           │
│  - "Spring 환경의 간단한 워크플로우 엔진"                         │
│  - 분산 시스템 복잡도를 제거한 경량 버전                          │
│  - 단일 인스턴스, DB 기반 상태 저장                               │
│                                                                  │
│  특징:                                                           │
│  ├── Spring Boot 네이티브 통합                                   │
│  ├── 단일 DB로 동작 (PostgreSQL/MySQL)                           │
│  ├── Temporal SDK 스타일 API                                     │
│  ├── Camunda BPMN 영감의 DSL                                     │
│  ├── 웹 모니터링 UI                                              │
│  └── 중소규모 서비스에 최적화                                    │
│                                                                  │
│  차별점:                                                         │
│  ├── Temporal: 분산 시스템 복잡도 → 단순화                       │
│  ├── Camunda: 무거운 BPMN 엔진 → 경량화                          │
│  └── 진입 장벽을 낮춘 "Good Enough" 솔루션                       │
│                                                                  │
│  난이도: ★★★☆☆                                                  │
│  예상 기간: 2-3개월                                              │
│  Claude Code 활용도: 높음 (70%)                                  │
│                                                                  │
│  ⚠️ 이 아이디어는 미래에 기회가 되면 진행                         │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**핵심 기능 (MVP):**
1. Workflow/Activity 정의 및 실행
2. DB 기반 상태 영속화
3. 재시도, 타임아웃, 보상 트랜잭션
4. 기본 웹 UI (상태 조회)

**제외 범위 (Temporal과의 차이):**
- 분산 클러스터링
- 멀티 리전
- 복잡한 샤딩
- Exactly-once 보장 (대신 At-least-once + 멱등성)
