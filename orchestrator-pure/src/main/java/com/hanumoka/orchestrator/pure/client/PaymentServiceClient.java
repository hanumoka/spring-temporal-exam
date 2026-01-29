package com.hanumoka.orchestrator.pure.client;

import com.hanumoka.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentServiceClient {

    private final RestClient restClient;
    private static final String BASE_URL = "http://localhost:8083/api/payments";

    /**
     * 결제 생성
     */
    public Long createPayment(Long orderId, BigDecimal amount, String paymentMethod) {
        ApiResponse<Map<String, Object>> response = restClient.post()
                .uri(BASE_URL + "?orderId={orderId}&amount={amount}&paymentMethod={paymentMethod}",
                        orderId, amount, paymentMethod)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        Long paymentId = ((Number) response.getData().get("id")).longValue();
        log.info("결제 생성 완료: paymentId={}", paymentId);
        return paymentId;
    }

    /**
     * 결제 승인
     */
    public void approvePayment(Long paymentId) {
        restClient.post()
                .uri(BASE_URL + "/{paymentId}/approve", paymentId)
                .retrieve()
                .body(ApiResponse.class);

        log.info("결제 승인 완료: paymentId={}", paymentId);
    }

    /**
     * 결제 확정
     */
    public void confirmPayment(Long paymentId) {
        restClient.post()
                .uri(BASE_URL + "/{paymentId}/confirm", paymentId)
                .retrieve()
                .body(ApiResponse.class);

        log.info("결제 확정 완료: paymentId={}", paymentId);
    }

    /**
     * 환불 (보상)
     */
    public void refundPayment(Long paymentId) {
        restClient.post()
                .uri(BASE_URL + "/{paymentId}/refund", paymentId)
                .retrieve()
                .body(ApiResponse.class);

        log.info("결제 환불 완료 (보상): paymentId={}", paymentId);
    }
}
