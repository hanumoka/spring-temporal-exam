package com.hanumoka.orchestrator.pure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "com.hanumoka.orchestrator.pure",
        "com.hanumoka.common"  // IdempotencyAspect, IdempotencyService 스캔
})
public class PureOrchestratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(PureOrchestratorApplication.class, args);
    }
}
