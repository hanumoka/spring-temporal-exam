package com.hanumoka.inventory.controller;

import com.hanumoka.common.dto.ApiResponse;
import com.hanumoka.inventory.entity.Inventory;
import com.hanumoka.inventory.entity.Product;
import com.hanumoka.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    /**
     * 상품 등록
     */
    @PostMapping("/products")
    public ApiResponse<ProductResponse> createProduct(
            @RequestParam String productCode,
            @RequestParam String name,
            @RequestParam BigDecimal price,
            @RequestParam(defaultValue = "0") int initialQuantity) {
        Product product = inventoryService.createProduct(productCode, name, price, initialQuantity);
        return ApiResponse.success(ProductResponse.from(product));
    }

    /**
     * 상품 조회
     */
    @GetMapping("/products/{productId}")
    public ApiResponse<ProductResponse> getProduct(@PathVariable Long productId) {
        Product product = inventoryService.getProduct(productId);
        return ApiResponse.success(ProductResponse.from(product));
    }

    /**
     * 재고 조회
     */
    @GetMapping("/{productId}")
    public ApiResponse<InventoryResponse> getInventory(@PathVariable Long productId) {
        Inventory inventory = inventoryService.getInventory(productId);
        return ApiResponse.success(InventoryResponse.from(inventory));
    }

    /**
     * 재고 예약 (Saga용)
     */
    @PostMapping("/{productId}/reserve")
    public ApiResponse<Void> reserveStock(
            @PathVariable Long productId,
            @RequestParam int quantity) {
        inventoryService.reserveStock(productId, quantity);
        return ApiResponse.success();
    }

    /**
     * 예약 확정 (Saga용)
     */
    @PostMapping("/{productId}/confirm")
    public ApiResponse<Void> confirmReservation(
            @PathVariable Long productId,
            @RequestParam int quantity) {
        inventoryService.confirmReservation(productId, quantity);
        return ApiResponse.success();
    }

    /**
     * 예약 취소 - 보상 트랜잭션 (Saga용)
     */
    @PostMapping("/{productId}/cancel")
    public ApiResponse<Void> cancelReservation(
            @PathVariable Long productId,
            @RequestParam int quantity) {
        inventoryService.cancelReservation(productId, quantity);
        return ApiResponse.success();
    }

    /**
     * 재고 추가 (입고)
     */
    @PostMapping("/{productId}/add")
    public ApiResponse<Void> addStock(
            @PathVariable Long productId,
            @RequestParam int quantity) {
        inventoryService.addStock(productId, quantity);
        return ApiResponse.success();
    }

    // 응답 DTO
    public record ProductResponse(
            Long id,
            String productCode,
            String name,
            String price
    ) {
        public static ProductResponse from(Product product) {
            return new ProductResponse(
                    product.getId(),
                    product.getProductCode(),
                    product.getName(),
                    product.getPrice().toString()
            );
        }
    }

    public record InventoryResponse(
            Long productId,
            String productCode,
            String productName,
            int quantity,
            int reservedQuantity,
            int availableQuantity
    ) {
        public static InventoryResponse from(Inventory inventory) {
            return new InventoryResponse(
                    inventory.getProduct().getId(),
                    inventory.getProduct().getProductCode(),
                    inventory.getProduct().getName(),
                    inventory.getQuantity(),
                    inventory.getReservedQuantity(),
                    inventory.getAvailableQuantity()
            );
        }
    }
}
