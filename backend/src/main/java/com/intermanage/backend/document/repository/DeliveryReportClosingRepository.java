package com.intermanage.backend.document.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.document.entity.DeliveryReportClosing;

public interface DeliveryReportClosingRepository extends JpaRepository<DeliveryReportClosing, UUID> {

    /** Aktywne zamknięcia sklepu, od najnowszego okresu. */
    @Query("""
            select c from DeliveryReportClosing c left join fetch c.closedBy
            where c.store.id = :storeId and c.cancelledAt is null order by c.periodFrom desc""")
    List<DeliveryReportClosing> findActive(UUID storeId);

    @Query("select c from DeliveryReportClosing c left join fetch c.closedBy where c.id = :id and c.store.id = :storeId")
    Optional<DeliveryReportClosing> findInStore(UUID storeId, UUID id);
}
