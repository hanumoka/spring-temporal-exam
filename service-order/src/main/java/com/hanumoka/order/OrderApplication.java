package com.hanumoka.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "com.hanumoka.order",
        "com.hanumoka.common.exception"  // GlobalExceptionHandler 스캔
})
@EnableScheduling  // Outbox Polling Publisher 및 스케줄러 활성화
public class OrderApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
