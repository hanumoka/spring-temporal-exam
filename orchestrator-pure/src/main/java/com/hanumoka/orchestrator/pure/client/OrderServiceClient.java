package com.hanumoka.orchestrator.pure.client;

import com.hanumoka.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderServiceClient {

    private final RestClient restClient;
    private static final String BASE_URL = "http://localhost:8081/api/orders";

    /**
     * 주문 생성
     */
    public Long createOrder(Long customerId) {
        ApiResponse<Map<String, Object>> response = restClient.post()
                .uri(BASE_URL + "?customerId={customerId}", customerId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        Long orderId = ((Number) response.getData().get("id")).longValue();
        log.info("주문 생성 완료: orderId={}", orderId);
        return orderId;
    }

    /**
     * 주문 확정
     */
    public void confirmOrder(Long orderId) {
        restClient.post()
                .uri(BASE_URL + "/{orderId}/confirm", orderId)
                .retrieve()
                .body(ApiResponse.class);

        log.info("주문 확정 완료: orderId={}", orderId);
    }

    /**
     * 주문 취소 (보상)
     */
    public void cancelOrder(Long orderId) {
        restClient.post()
                .uri(BASE_URL + "/{orderId}/cancel", orderId)
                .retrieve()
                .body(ApiResponse.class);

        log.info("주문 취소 완료 (보상): orderId={}", orderId);
    }
}
