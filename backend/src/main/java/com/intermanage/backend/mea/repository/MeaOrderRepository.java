package com.intermanage.backend.mea.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.mea.entity.MeaOrder;

public interface MeaOrderRepository extends JpaRepository<MeaOrder, UUID> {

    Optional<MeaOrder> findByIdAndStoreId(UUID id, UUID storeId);

    /** Zapisane zamówienia jednego MEA w sklepie, od najnowszego. */
    @EntityGraph(attributePaths = "createdBy")
    List<MeaOrder> findByStoreIdAndNumberAndSavedAtIsNotNullOrderBySavedAtDesc(UUID storeId, String number);

    /** Najnowsze zapisane zamówienie każdego MEA w sklepie. */
    @Query("""
            select o from MeaOrder o
            where o.store.id = :storeId and o.savedAt is not null
              and o.savedAt = (select max(x.savedAt) from MeaOrder x
                               where x.store.id = :storeId and x.number = o.number and x.savedAt is not null)
            """)
    List<MeaOrder> findLatestSaved(UUID storeId);
}
