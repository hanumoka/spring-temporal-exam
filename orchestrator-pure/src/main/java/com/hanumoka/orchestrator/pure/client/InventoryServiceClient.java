package com.hanumoka.orchestrator.pure.client;

import com.hanumoka.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryServiceClient {

    private final RestClient restClient;
    private static final String BASE_URL = "http://localhost:8082/api/inventory";

    /**
     * 재고 예약
     */
    public void reserveStock(Long productId, int quantity) {
        restClient.post()
                .uri(BASE_URL + "/{productId}/reserve?quantity={quantity}", productId, quantity)
                .retrieve()
                .body(ApiResponse.class);

        log.info("재고 예약 완료: productId={}, quantity={}", productId, quantity);
    }

    /**
     * 예약 확정
     */
    public void confirmReservation(Long productId, int quantity) {
        restClient.post()
                .uri(BASE_URL + "/{productId}/confirm?quantity={quantity}", productId, quantity)
                .retrieve()
                .body(ApiResponse.class);

        log.info("재고 확정 완료: productId={}, quantity={}", productId, quantity);
    }

    /**
     * 예약 취소 (보상)
     */
    public void cancelReservation(Long productId, int quantity) {
        restClient.post()
                .uri(BASE_URL + "/{productId}/cancel?quantity={quantity}", productId, quantity)
                .retrieve()
                .body(ApiResponse.class);

        log.info("재고 예약 취소 완료 (보상): productId={}, quantity={}", productId, quantity);
    }
}
