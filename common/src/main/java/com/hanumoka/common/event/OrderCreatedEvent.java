package com.hanumoka.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 주문 생성 이벤트
 *
 * <p>Outbox 패턴에서 Redis Stream으로 발행되는 이벤트</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreatedEvent {

    /**
     * 주문 ID
     */
    private Long orderId;

    /**
     * 주문 번호
     */
    private String orderNumber;

    /**
     * 고객 ID
     */
    private Long customerId;

    /**
     * 총 주문 금액
     */
    private BigDecimal totalAmount;

    /**
     * 주문 상태
     */
    private String status;

    /**
     * 이벤트 발생 시간
     */
    private LocalDateTime occurredAt;
}
