package com.hanumoka.notification.gateway;

/**
 * SMS 발송 Gateway 인터페이스
 *
 * <p>실제 SMS 발송 서비스와의 연동을 추상화합니다.</p>
 * <p>테스트/개발 환경에서는 FakeSmsGateway를 사용합니다.</p>
 */
public interface SmsGateway {

    /**
     * SMS 발송
     *
     * @param phoneNumber 수신자 전화번호
     * @param message     메시지 내용
     * @throws SmsGatewayException 발송 실패 시
     */
    void send(String phoneNumber, String message);
}
