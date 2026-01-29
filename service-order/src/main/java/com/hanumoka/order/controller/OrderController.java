package com.hanumoka.order.controller;

import com.hanumoka.common.dto.ApiResponse;
import com.hanumoka.order.entity.Order;
import com.hanumoka.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * 주문 생성
     */
    @PostMapping
    public ApiResponse<OrderResponse> createOrder(@RequestParam Long customerId) {
        Order order = orderService.createOrder(customerId);
        return ApiResponse.success(OrderResponse.from(order));
    }

    /**
     * 주문 조회 (ID)
     */
    @GetMapping("/{orderId}")
    public ApiResponse<OrderResponse> getOrder(@PathVariable Long orderId) {
        Order order = orderService.getOrder(orderId);
        return ApiResponse.success(OrderResponse.from(order));
    }

    /**
     * 주문 조회 (주문번호)
     */
    @GetMapping("/by-order-number/{orderNumber}")
    public ApiResponse<OrderResponse> getOrderByOrderNumber(@PathVariable String orderNumber) {
        Order order = orderService.getOrderByOrderNumber(orderNumber);
        return ApiResponse.success(OrderResponse.from(order));
    }

    /**
     * 고객별 주문 목록 조회
     */
    @GetMapping("/customer/{customerId}")
    public ApiResponse<List<OrderResponse>> getOrdersByCustomerId(@PathVariable Long customerId) {
        List<Order> orders = orderService.getOrdersByCustomerId(customerId);
        List<OrderResponse> responses = orders.stream()
                .map(OrderResponse::from)
                .toList();
        return ApiResponse.success(responses);
    }

    /**
     * 주문 확정
     */
    @PostMapping("/{orderId}/confirm")
    public ApiResponse<OrderResponse> confirmOrder(@PathVariable Long orderId) {
        Order order = orderService.confirmOrder(orderId);
        return ApiResponse.success(OrderResponse.from(order));
    }

    /**
     * 주문 취소
     */
    @PostMapping("/{orderId}/cancel")
    public ApiResponse<OrderResponse> cancelOrder(@PathVariable Long orderId) {
        Order order = orderService.cancelOrder(orderId);
        return ApiResponse.success(OrderResponse.from(order));
    }

    // 응답 DTO (내부 클래스)
    public record OrderResponse(
            Long id,
            String orderNumber,
            Long customerId,
            String status,
            String totalAmount
    ) {
        public static OrderResponse from(Order order) {
            return new OrderResponse(
                    order.getId(),
                    order.getOrderNumber(),
                    order.getCustomerId(),
                    order.getStatus().name(),
                    order.getTotalAmount().toString()
            );
        }
    }
}
