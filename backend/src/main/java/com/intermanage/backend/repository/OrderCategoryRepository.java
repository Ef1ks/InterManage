package com.intermanage.backend.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.entity.OrderCategory;

public interface OrderCategoryRepository extends JpaRepository<OrderCategory, UUID> {

    @Query("select c from OrderCategory c where c.id = :id and c.section.store.id = :storeId")
    Optional<OrderCategory> findInStore(UUID id, UUID storeId);
}
