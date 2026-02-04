package com.hanumoka.inventory.service;

import com.hanumoka.common.exception.BusinessException;
import com.hanumoka.common.exception.ErrorCode;
import com.hanumoka.inventory.entity.Inventory;
import com.hanumoka.inventory.entity.Product;
import com.hanumoka.inventory.repository.InventoryRepository;
import com.hanumoka.inventory.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class InventoryService {

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;

    private final RedissonClient redissonClient;

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
     * 재고 예약 (Saga Step) - Semantic Lock 적용
     *
     * @param productId 상품 ID
     * @param quantity  예약 수량
     * @param sagaId    Saga 식별자 (Semantic Lock용)
     */
    @Transactional(timeout = 30)
    public void reserveStock(Long productId, int quantity, String sagaId) {

        executeWithLock(productId, () -> {
            Inventory inventory = getInventory(productId);

            // 1. Semantic Lock 획득 (다른 Saga 작업 중이면 예외)
            inventory.acquireSemanticLock(sagaId);
            log.debug("[SemanticLock] 획득: productId={}, sagaId={}", productId, sagaId);

            // 2. 재고 예약
            inventory.reserve(quantity);

            // 3. Semantic Lock 상태 변경 (RESERVING → RESERVED)
            inventory.releaseSemanticLockOnSuccess(sagaId);

            log.info("재고 예약 완료: productId={}, quantity={}, sagaId={}, available={}",
                    productId, quantity, sagaId, inventory.getAvailableQuantity());
        });
    }

    /**
     * 예약 확정 (Saga Step) - Semantic Lock 검증
     *
     * @param productId 상품 ID
     * @param quantity  확정 수량
     * @param sagaId    Saga 식별자
     */
    @Transactional(timeout = 30)
    public void confirmReservation(Long productId, int quantity, String sagaId) {
        executeWithLock(productId, () -> {
            Inventory inventory = getInventory(productId);

            // 1. Saga 소유권 검증
            inventory.validateSagaOwnership(sagaId);

            // 2. 예약 확정
            inventory.confirmReservation(quantity);

            // 3. Semantic Lock 완전 해제
            inventory.clearSemanticLock();

            log.info("재고 예약 확정: productId={}, quantity={}, sagaId={}", productId, quantity, sagaId);
        });
    }

    /**
     * 예약 취소 - 보상 트랜잭션 (Saga Compensation) - Semantic Lock 해제
     *
     * @param productId 상품 ID
     * @param quantity  취소 수량
     * @param sagaId    Saga 식별자
     */
    @Transactional(timeout = 30)
    public void cancelReservation(Long productId, int quantity, String sagaId) {
        executeWithLock(productId, () -> {
            Inventory inventory = getInventory(productId);

            // 1. Saga 소유권 검증 (다른 Saga의 예약을 취소하지 않도록)
            inventory.validateSagaOwnership(sagaId);

            // 2. 예약 취소
            inventory.cancelReservation(quantity);

            // 3. Semantic Lock 해제 (AVAILABLE로 복귀)
            inventory.releaseSemanticLockOnFailure(sagaId);

            log.info("재고 예약 취소 (보상): productId={}, quantity={}, sagaId={}", productId, quantity, sagaId);
        });
    }

    /**
     * 재고 추가 (입고)
     */
    @Transactional(timeout = 30)
    public void addStock(Long productId, int quantity) {
        executeWithLock(productId, () -> {
            Inventory inventory = getInventory(productId);
            inventory.addStock(quantity);
            log.info("재고 추가: productId={}, quantity={}, total={}",
                    productId, quantity, inventory.getQuantity());
        });
    }

    /**
     * 분산 락을 적용하여 재고 학습 실행
     *
     * @param productId 상품 락 (락 키로 사용)
     * @param action    상품 락 (락 키로 사용)
     */
    private void executeWithLock(Long productId, Runnable action) {
        String lockKey = "lock:inventory:" + productId;
        RLock lock = redissonClient.getLock(lockKey);

        boolean isLocked = false;

        try {
            isLocked = lock.tryLock(5, TimeUnit.SECONDS);

            if (!isLocked) {
                throw new BusinessException(ErrorCode.LOCK_ACQUISITION_FAILED.toErrorInfo());
            }//if

            log.debug("[DistributedLock] 락 획득 : {}", lockKey);

            action.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.LOCK_INTERRUPTED.toErrorInfo());
        } finally {
            if (isLocked && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("[DistributedLock] 락 해제: {}", lockKey);
            }//if
        }// finally
    } // executeWithLock
}
