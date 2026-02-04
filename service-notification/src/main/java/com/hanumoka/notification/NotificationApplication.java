package com.hanumoka.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "com.hanumoka.notification",
        "com.hanumoka.common.exception"  // GlobalExceptionHandler 스캔
})
@EnableScheduling  // Redis Stream Polling Consumer 활성화
public class NotificationApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
