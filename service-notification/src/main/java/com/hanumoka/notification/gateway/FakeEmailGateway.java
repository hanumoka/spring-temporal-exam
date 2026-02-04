package com.hanumoka.notification.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * Fake Email Gateway - 학습/테스트용
 *
 * <h3>동작 방식</h3>
 * <ul>
 *   <li>설정된 지연 시간만큼 대기 (외부 API 호출 시뮬레이션)</li>
 *   <li>설정된 실패율에 따라 랜덤 실패 발생</li>
 *   <li>실제 Email은 발송하지 않고 로그만 출력</li>
 * </ul>
 *
 * <h3>설정</h3>
 * <pre>
 * notification:
 *   gateway:
 *     email:
 *       delay-ms: 200
 *       failure-rate: 0.05  # 5% 실패
 * </pre>
 */
@Component
@Slf4j
public class FakeEmailGateway implements EmailGateway {

    @Value("${notification.gateway.email.delay-ms:200}")
    private long delayMs;

    @Value("${notification.gateway.email.failure-rate:0.0}")
    private double failureRate;

    private final Random random = new Random();

    @Override
    public void send(String to, String subject, String body) {
        log.debug("[Fake Email] 발송 시작: to={}, subject={}", to, subject);

        // 지연 시뮬레이션
        simulateDelay();

        // 실패 시뮬레이션
        if (shouldFail()) {
            log.warn("[Fake Email] 발송 실패 (시뮬레이션): to={}", to);
            throw new RuntimeException("Email 발송 실패 (Fake 시뮬레이션)");
        }

        // 성공 로그
        log.info("[Fake Email] 발송 성공: to={}, subject={}", to, subject);
        log.debug("[Fake Email] 본문:\n{}", body);
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
}
