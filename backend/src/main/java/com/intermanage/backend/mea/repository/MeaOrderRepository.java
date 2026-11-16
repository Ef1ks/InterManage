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

    /** Pliki czekające w kolejce sklepu do wysłania do ATHOS, od najstarszego. */
    @EntityGraph(attributePaths = "createdBy")
    @Query("""
            select o from MeaOrder o
            where o.store.id = :storeId and o.queuedAt is not null and o.savedAt is null
              and o.removedAt is null and o.replacedBy is null
            order by o.queuedAt
            """)
    List<MeaOrder> findQueue(UUID storeId);

    /** Plik tego MEA czekający w kolejce sklepu (najwyżej jeden). */
    @EntityGraph(attributePaths = "createdBy")
    @Query("""
            select o from MeaOrder o
            where o.store.id = :storeId and o.number = :number and o.queuedAt is not null and o.savedAt is null
              and o.removedAt is null and o.replacedBy is null
            """)
    List<MeaOrder> findQueued(UUID storeId, String number);
}
