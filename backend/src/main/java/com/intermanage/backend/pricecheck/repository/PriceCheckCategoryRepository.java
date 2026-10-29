package com.intermanage.backend.pricecheck.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.pricecheck.entity.PriceCheckCategory;

public interface PriceCheckCategoryRepository extends JpaRepository<PriceCheckCategory, UUID> {

    /** Kategorie bez znacznika, w kolejności dodania. */
    @Query("select c from PriceCheckCategory c where c.store.id = :storeId and c.deletedAt is null "
            + "order by c.createdAt, c.id")
    List<PriceCheckCategory> findActive(UUID storeId);

    @Query("select c from PriceCheckCategory c where c.id = :id and c.store.id = :storeId")
    Optional<PriceCheckCategory> findInStore(UUID storeId, UUID id);

    /** Czy inna aktywna kategoria sklepu ma już tę nazwę (bez względu na wielkość liter). */
    @Query("""
            select count(c) > 0 from PriceCheckCategory c where c.store.id = :storeId and c.deletedAt is null
            and lower(c.name) = lower(:name) and (:exceptId is null or c.id <> :exceptId)""")
    boolean existsActiveName(UUID storeId, String name, UUID exceptId);
}
