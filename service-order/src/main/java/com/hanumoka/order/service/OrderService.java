package com.hanumoka.order.service;

import com.hanumoka.common.exception.BusinessException;
import com.hanumoka.common.exception.ErrorCode;
import com.hanumoka.order.entity.Order;
import com.hanumoka.order.entity.OrderStatus;
import com.hanumoka.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;

    /**
     * 주문 생성
     */
    @Transactional
    public Order createOrder(Long customerId) {
        String orderNumber = generateOrderNumber();

        Order order = Order.builder()
                .orderNumber(orderNumber)
                .customerId(customerId)
                .status(OrderStatus.PENDING)
                .build();

        Order savedOrder = orderRepository.save(order);
        log.info("주문 생성 완료: orderNumber={}, customerId={}", orderNumber, customerId);

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
