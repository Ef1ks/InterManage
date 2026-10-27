package com.intermanage.backend.deliorder.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.deliorder.entity.OrderSection;

public interface OrderSectionRepository extends JpaRepository<OrderSection, UUID> {

    @Query("select s from OrderSection s where s.store.id = :storeId order by s.position, s.id")
    List<OrderSection> findByStoreId(UUID storeId);

    Optional<OrderSection> findByIdAndStoreId(UUID id, UUID storeId);

    @Query("select coalesce(max(s.position), -1) + 1 from OrderSection s where s.store.id = :storeId")
    int nextPosition(UUID storeId);
}
