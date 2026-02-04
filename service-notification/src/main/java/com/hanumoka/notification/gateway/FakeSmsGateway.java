package com.hanumoka.notification.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * Fake SMS Gateway - 학습/테스트용
 *
 * <h3>동작 방식</h3>
 * <ul>
 *   <li>설정된 지연 시간만큼 대기 (외부 API 호출 시뮬레이션)</li>
 *   <li>설정된 실패율에 따라 랜덤 실패 발생</li>
 *   <li>실제 SMS는 발송하지 않고 로그만 출력</li>
 * </ul>
 *
 * <h3>설정</h3>
 * <pre>
 * notification:
 *   gateway:
 *     sms:
 *       delay-ms: 100
 *       failure-rate: 0.1  # 10% 실패
 * </pre>
 */
@Component
@Slf4j
public class FakeSmsGateway implements SmsGateway {

    @Value("${notification.gateway.sms.delay-ms:100}")
    private long delayMs;

    @Value("${notification.gateway.sms.failure-rate:0.0}")
    private double failureRate;

    private final Random random = new Random();

    @Override
    public void send(String phoneNumber, String message) {
        log.debug("[Fake SMS] 발송 시작: phone={}", phoneNumber);

        // 지연 시뮬레이션
        simulateDelay();

        // 실패 시뮬레이션
        if (shouldFail()) {
            log.warn("[Fake SMS] 발송 실패 (시뮬레이션): phone={}", phoneNumber);
            throw new RuntimeException("SMS 발송 실패 (Fake 시뮬레이션)");
        }

        // 성공 로그
        log.info("[Fake SMS] 발송 성공: phone={}, message={}",
                phoneNumber, truncate(message, 50));
    }

    private void simulateDelay() {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean shouldFail() {
        return failureRate > 0 && random.nextDouble() < failureRate;
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return null;
        return str.length() > maxLength ? str.substring(0, maxLength) + "..." : str;
    }
}
