package com.hanumoka.orchestrator.temporal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Temporal Orchestrator Application
 *
 * <h3>실행 방법</h3>
 * <pre>
 * 1. Docker 인프라 시작 (Temporal Server 포함)
 *    cd spring-temporal-exam-docker
 *    docker-compose up -d
 *
 * 2. 하위 서비스 시작
 *    ./gradlew :service-order:bootRun
 *    ./gradlew :service-inventory:bootRun
 *    ./gradlew :service-payment:bootRun
 *
 * 3. Temporal Orchestrator 시작
 *    ./gradlew :orchestrator-temporal:bootRun
 *
 * 4. 테스트
 *    POST http://localhost:8081/api/temporal/orders
 *    {
 *      "customerId": 1,
 *      "productId": 1,
 *      "quantity": 2,
 *      "amount": 20000,
 *      "paymentMethod": "CARD"
 *    }
 *
 * 5. Temporal UI에서 Workflow 확인
 *    http://localhost:8088
 * </pre>
 *
 * <h3>orchestrator-pure와 비교</h3>
 * <pre>
 * orchestrator-pure    : 포트 8080, POST /api/saga/orders
 * orchestrator-temporal: 포트 8081, POST /api/temporal/orders
 * </pre>
 */
@SpringBootApplication
public class TemporalOrchestratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(TemporalOrchestratorApplication.class, args);
    }
}
