package com.hanumoka.notification.gateway;

/**
 * Email 발송 Gateway 인터페이스
 *
 * <p>실제 Email 발송 서비스와의 연동을 추상화합니다.</p>
 * <p>테스트/개발 환경에서는 FakeEmailGateway를 사용합니다.</p>
 */
public interface EmailGateway {

    /**
     * Email 발송
     *
     * @param to      수신자 이메일 주소
     * @param subject 제목
     * @param body    본문
     * @throws EmailGatewayException 발송 실패 시
     */
    void send(String to, String subject, String body);
}
