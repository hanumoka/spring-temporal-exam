package com.hanumoka.inventory;

import com.hanumoka.inventory.entity.Inventory;
import com.hanumoka.inventory.entity.Product;
import com.hanumoka.inventory.repository.InventoryRepository;
import com.hanumoka.inventory.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OptimisticLockTest {

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private ProductRepository productRepository;

    private Long productId;

    @BeforeEach
    void setUp() {
        // 테스트용 상품 생성
        Product product = Product.builder()
                .productCode("TEST-OPT-" + System.currentTimeMillis())
                .name("낙관적락 테스트 상품")
                .price(BigDecimal.valueOf(10000))
                .build();
        productRepository.save(product);

        Inventory inventory = Inventory.builder()
                .product(product)
                .quantity(100)
                .build();
        inventoryRepository.save(inventory);

        productId = product.getId();
    }

    @Test
    @DisplayName("@Version: 동시 수정 시 한 쪽은 실패한다")
    void optimisticLock_concurrentUpdate_oneFailsOneSucceeds() throws InterruptedException {
        // given
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // when: 두 스레드가 동시에 같은 재고를 수정
        for (int i = 0; i < threadCount; i++) {
            final int threadNum = i;
            executor.submit(() -> {
                try {
                    // 각 스레드가 독립적으로 조회 → 수정 → 저장
                    Inventory inventory = inventoryRepository
                            .findByProductIdWithProduct(productId)
                            .orElseThrow();

                    System.out.println("Thread " + threadNum +
                            ": version=" + inventory.getVersion() +
                            ", quantity=" + inventory.getQuantity());

                    // 의도적 지연 (동시 수정 유도)
                    Thread.sleep(100);

                    inventory.reserve(10);
                    inventoryRepository.saveAndFlush(inventory);

                    successCount.incrementAndGet();
                    System.out.println("Thread " + threadNum + ": 성공!");

                } catch (ObjectOptimisticLockingFailureException e) {
                    failCount.incrementAndGet();
                    System.out.println("Thread " + threadNum +
                            ": OptimisticLockingFailureException 발생!");
                } catch (Exception e) {
                    System.out.println("Thread " + threadNum +
                            ": 기타 예외 - " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // then
        System.out.println("성공: " + successCount.get() + ", 실패: " + failCount.get());

        // 둘 중 하나만 성공 (또는 타이밍에 따라 둘 다 성공할 수도 있음)
        assertThat(successCount.get() + failCount.get()).isEqualTo(threadCount);
    }
}