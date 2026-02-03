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
     * 재고 예약 (Saga Step)
     */
    @Transactional(timeout = 30)
    public void reserveStock(Long productId, int quantity) {

        executeWithLock(productId, () -> {
            Inventory inventory = getInventory(productId);
            inventory.reserve(quantity);
            log.info("재고 예약 완료: productId={}, quantity={}, available={}", productId, quantity, inventory.getAvailableQuantity());
        });

//        String lockKey = "lock:inventory:" + productId;
//        RLock lock = redissonClient.getLock(lockKey);
//
//        boolean isLocked = false;
//
//        try {
//
////            isLocked = lock.tryLock(5, 10, TimeUnit.SECONDS); // 락 획득 시도 ( 최대 5초 대기, 락 유지 10초)
//            isLocked = lock.tryLock(5, TimeUnit.SECONDS);  // 릴리즈 타임을 생략하여, watchdog 활성, 5초 대기 기본 30초 + 자동 연장
//
//            if (!isLocked) {
//                throw new RuntimeException("재고 락 획득 실패: productId=" + productId);
//            }
//
//            log.debug("[DistributedLock] 락 획득 : {}", lockKey);
//            Inventory inventory = getInventory(productId);
//            inventory.reserve(quantity);
//
//            log.info("재고 예약 완료: productId={}, quantity={}, available={}", productId, quantity, inventory.getAvailableQuantity());
//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//            throw new RuntimeException("락 획득 중 인터럽트 발생", e);
//        } finally {
//            // 락 해제 (락을 획득한 경우에만)
//            if (isLocked && lock.isHeldByCurrentThread()) {
//                lock.unlock();
//                log.debug("[DistributedLock] 락 해제 : {}", lockKey);
//            }
//        }// finally
    } // reserveStock

    /**
     * 예약 확정 (Saga Step)
     */
    @Transactional(timeout = 30)
    public void confirmReservation(Long productId, int quantity) {
        executeWithLock(productId, () -> {
            Inventory inventory = getInventory(productId);
            inventory.confirmReservation(quantity);
            log.info("재고 예약 확정: productId={}, quantity={}", productId, quantity);
        });
    } //confirmReservation

    /**
     * 예약 취소 - 보상 트랜잭션 (Saga Compensation)
     */
    @Transactional(timeout = 30)
    public void cancelReservation(Long productId, int quantity) {
        executeWithLock(productId, () -> {
            Inventory inventory = getInventory(productId);
            inventory.cancelReservation(quantity);
            log.info("재고 예약 취소 (보상): productId={}, quantity={}", productId, quantity);
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
                throw new RuntimeException("재고 락 획득 실패: productId=" + productId);
            }//if

            log.debug("[DistributedLock] 락 획득 : {}", lockKey);

            action.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("락 획득 중 인터럽트 발생", e);
        } finally {
            if (isLocked && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("[DistributedLock] 락 해제: {}", lockKey);
            }//if
        }// finally
    } // executeWithLock
}
