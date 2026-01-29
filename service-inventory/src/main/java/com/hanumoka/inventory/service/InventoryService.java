package com.hanumoka.inventory.service;

import com.hanumoka.common.exception.BusinessException;
import com.hanumoka.common.exception.ErrorCode;
import com.hanumoka.inventory.entity.Inventory;
import com.hanumoka.inventory.entity.Product;
import com.hanumoka.inventory.repository.InventoryRepository;
import com.hanumoka.inventory.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class InventoryService {

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;

    /**
     * 상품 등록 (재고 포함)
     */
    @Transactional
    public Product createProduct(String productCode, String name, BigDecimal price, int initialQuantity) {
        if (productRepository.existsByProductCode(productCode)) {
            throw new IllegalArgumentException("이미 존재하는 상품 코드입니다: " + productCode);
        }

        Product product = Product.builder()
                .productCode(productCode)
                .name(name)
                .price(price)
                .build();
        productRepository.save(product);

        Inventory inventory = Inventory.builder()
                .product(product)
                .quantity(initialQuantity)
                .build();
        inventoryRepository.save(inventory);

        log.info("상품 등록 완료: productCode={}, initialQuantity={}", productCode, initialQuantity);
        return product;
    }

    /**
     * 상품 조회
     */
    public Product getProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT.toErrorInfo()));
    }

    /**
     * 재고 조회
     */
    public Inventory getInventory(Long productId) {
        return inventoryRepository.findByProductIdWithProduct(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSUFFICIENT_STOCK.toErrorInfo()));
    }

    /**
     * 가용 재고 확인
     */
    public int getAvailableQuantity(Long productId) {
        Inventory inventory = getInventory(productId);
        return inventory.getAvailableQuantity();
    }

    /**
     * 재고 예약 (Saga Step)
     */
    @Transactional
    public void reserveStock(Long productId, int quantity) {
        Inventory inventory = getInventory(productId);
        inventory.reserve(quantity);
        log.info("재고 예약 완료: productId={}, quantity={}, available={}",
                productId, quantity, inventory.getAvailableQuantity());
    }

    /**
     * 예약 확정 (Saga Step)
     */
    @Transactional
    public void confirmReservation(Long productId, int quantity) {
        Inventory inventory = getInventory(productId);
        inventory.confirmReservation(quantity);
        log.info("재고 예약 확정: productId={}, quantity={}", productId, quantity);
    }

    /**
     * 예약 취소 - 보상 트랜잭션 (Saga Compensation)
     */
    @Transactional
    public void cancelReservation(Long productId, int quantity) {
        Inventory inventory = getInventory(productId);
        inventory.cancelReservation(quantity);
        log.info("재고 예약 취소 (보상): productId={}, quantity={}", productId, quantity);
    }

    /**
     * 재고 추가 (입고)
     */
    @Transactional
    public void addStock(Long productId, int quantity) {
        Inventory inventory = getInventory(productId);
        inventory.addStock(quantity);
        log.info("재고 추가: productId={}, quantity={}, total={}",
                productId, quantity, inventory.getQuantity());
    }
}
