package com.hanumoka.order.service;

import com.hanumoka.common.event.OrderCreatedEvent;
import com.hanumoka.common.exception.BusinessException;
import com.hanumoka.common.exception.ErrorCode;
import com.hanumoka.order.entity.Order;
import com.hanumoka.order.entity.OrderStatus;
import com.hanumoka.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final OutboxService outboxService;

    /**
     * 주문 생성
     *
     * <h3>Outbox 패턴 적용</h3>
     * <ol>
     *   <li>주문 저장 (DB)</li>
     *   <li>Outbox 이벤트 저장 (같은 트랜잭션)</li>
     *   <li>별도 Publisher가 Outbox → Redis Stream으로 발행</li>
     * </ol>
     *
     * <p>트랜잭션 원자성으로 이중 쓰기 문제 해결</p>
     */
    @Transactional
    public Order createOrder(Long customerId) {
        String orderNumber = generateOrderNumber();

        Order order = Order.builder()
                .orderNumber(orderNumber)
                .customerId(customerId)
                .status(OrderStatus.PENDING)
                .build();

        // 1. 주문 저장
        Order savedOrder = orderRepository.save(order);

        // 2. Outbox 이벤트 저장 (같은 트랜잭션!)
        OrderCreatedEvent event = OrderCreatedEvent.builder()
                .orderId(savedOrder.getId())
                .orderNumber(savedOrder.getOrderNumber())
                .customerId(savedOrder.getCustomerId())
                .totalAmount(savedOrder.getTotalAmount())
                .status(savedOrder.getStatus().name())
                .occurredAt(LocalDateTime.now())
                .build();

        outboxService.save(
                "Order",
                savedOrder.getOrderNumber(),
                "OrderCreated",
                event
        );

        log.info("주문 생성 완료 (Outbox 이벤트 포함): orderNumber={}, customerId={}",
                orderNumber, customerId);

        return savedOrder;
    }

    /**
     * 주문 조회 (ID)
     */
    public Order getOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND.toErrorInfo()));
    }

    /**
     * 주문 조회 (주문번호)
     */
    public Order getOrderByOrderNumber(String orderNumber) {
        return orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND.toErrorInfo()));
    }

    /**
     * 고객별 주문 목록 조회
     */
    public List<Order> getOrdersByCustomerId(Long customerId) {
        return orderRepository.findByCustomerId(customerId);
    }

    /**
     * 주문 상태 변경
     */
    @Transactional
    public Order updateOrderStatus(Long orderId, OrderStatus newStatus) {
        Order order = getOrder(orderId);
        order.updateStatus(newStatus);

        log.info("주문 상태 변경: orderId={}, newStatus={}", orderId, newStatus);
        return order;
    }

    /**
     * 주문 확정 (결제 완료 후)
     */
    @Transactional
    public Order confirmOrder(Long orderId) {
        return updateOrderStatus(orderId, OrderStatus.CONFIRMED);
    }

    /**
     * 주문 취소
     */
    @Transactional
    public Order cancelOrder(Long orderId) {
        return updateOrderStatus(orderId, OrderStatus.CANCELLED);
    }

    private String generateOrderNumber() {
        return "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
