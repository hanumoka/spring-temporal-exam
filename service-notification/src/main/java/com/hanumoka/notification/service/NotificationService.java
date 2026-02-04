package com.hanumoka.notification.service;

import com.hanumoka.common.event.OrderCreatedEvent;
import com.hanumoka.notification.gateway.EmailGateway;
import com.hanumoka.notification.gateway.SmsGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 알림 발송 서비스
 *
 * <h3>역할</h3>
 * <ul>
 *   <li>주문 확인 알림 발송 (SMS + Email)</li>
 *   <li>Gateway 추상화를 통한 실제/Fake 구현 교체</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final SmsGateway smsGateway;
    private final EmailGateway emailGateway;

    /**
     * 주문 확인 알림 발송
     *
     * @param event 주문 생성 이벤트
     */
    public void sendOrderConfirmation(OrderCreatedEvent event) {
        String orderNumber = event.getOrderNumber();
        Long customerId = event.getCustomerId();

        log.info("주문 확인 알림 발송 시작: orderNumber={}, customerId={}",
                orderNumber, customerId);

        // SMS 발송
        try {
            String phoneNumber = getPhoneNumber(customerId);
            String smsMessage = buildSmsMessage(event);
            smsGateway.send(phoneNumber, smsMessage);
            log.info("SMS 발송 완료: orderNumber={}, phone={}", orderNumber, maskPhone(phoneNumber));
        } catch (Exception e) {
            log.error("SMS 발송 실패: orderNumber={}", orderNumber, e);
            // SMS 실패해도 Email은 시도
        }

        // Email 발송
        try {
            String email = getEmail(customerId);
            String subject = buildEmailSubject(event);
            String body = buildEmailBody(event);
            emailGateway.send(email, subject, body);
            log.info("Email 발송 완료: orderNumber={}, email={}", orderNumber, maskEmail(email));
        } catch (Exception e) {
            log.error("Email 발송 실패: orderNumber={}", orderNumber, e);
        }

        log.info("주문 확인 알림 발송 완료: orderNumber={}", orderNumber);
    }

    /**
     * 고객 전화번호 조회 (Fake)
     * TODO: 실제 구현 시 Customer Service 호출
     */
    private String getPhoneNumber(Long customerId) {
        return "010-1234-" + String.format("%04d", customerId % 10000);
    }

    /**
     * 고객 이메일 조회 (Fake)
     * TODO: 실제 구현 시 Customer Service 호출
     */
    private String getEmail(Long customerId) {
        return "customer" + customerId + "@example.com";
    }

    private String buildSmsMessage(OrderCreatedEvent event) {
        return String.format("[주문확인] 주문번호 %s가 접수되었습니다. 감사합니다!",
                event.getOrderNumber());
    }

    private String buildEmailSubject(OrderCreatedEvent event) {
        return String.format("[주문확인] 주문번호 %s", event.getOrderNumber());
    }

    private String buildEmailBody(OrderCreatedEvent event) {
        return String.format("""
                안녕하세요, 고객님!

                주문이 정상적으로 접수되었습니다.

                - 주문번호: %s
                - 주문금액: %s원
                - 주문일시: %s

                감사합니다.
                """,
                event.getOrderNumber(),
                event.getTotalAmount(),
                event.getOccurredAt()
        );
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 8) return "***";
        return phone.substring(0, 3) + "-****-" + phone.substring(phone.length() - 4);
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        int atIndex = email.indexOf("@");
        return email.substring(0, Math.min(3, atIndex)) + "***" + email.substring(atIndex);
    }
}
