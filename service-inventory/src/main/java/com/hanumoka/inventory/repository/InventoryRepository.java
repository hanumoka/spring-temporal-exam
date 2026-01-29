package com.hanumoka.inventory.repository;

import com.hanumoka.inventory.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByProductId(Long productId);

    @Query("SELECT i FROM Inventory i JOIN FETCH i.product WHERE i.product.id = :productId")
    Optional<Inventory> findByProductIdWithProduct(@Param("productId") Long productId);

    @Query("SELECT i FROM Inventory i JOIN FETCH i.product WHERE i.product.productCode = :productCode")
    Optional<Inventory> findByProductCode(@Param("productCode") String productCode);
}
