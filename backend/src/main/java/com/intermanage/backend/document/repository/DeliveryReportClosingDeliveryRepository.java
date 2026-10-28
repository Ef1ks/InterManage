package com.intermanage.backend.document.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.document.entity.DeliveryReportClosingDelivery;

public interface DeliveryReportClosingDeliveryRepository extends JpaRepository<DeliveryReportClosingDelivery, UUID> {

    /** Przyjęcia zamkniętego raportu: z jego okresu i zmiany przeniesione z wcześniejszych zamknięć. */
    @Query("""
            select r from DeliveryReportClosingDelivery r join fetch r.delivery left join fetch r.originClosing
            where r.closing.id = :closingId""")
    List<DeliveryReportClosingDelivery> findByClosing(UUID closingId);

    /**
     * Wszystko, co aktywne zamknięcia wiedzą o przyjęciach z okresów {@code homeClosingIds}: wpisy z tych zamknięć
     * i zmiany przeniesione z nich do późniejszych.
     */
    @Query("""
            select r from DeliveryReportClosingDelivery r join fetch r.closing c left join r.originClosing o
            where c.cancelledAt is null and r.delivery.id in :deliveryIds
              and ((o is null and c.id in :homeClosingIds) or o.id in :homeClosingIds)""")
    List<DeliveryReportClosingDelivery> findKnown(Collection<UUID> homeClosingIds, Collection<UUID> deliveryIds);

    /** Wpisy przyjęć w aktywnych zamknięciach ich własnego okresu (bez zmian przeniesionych do późniejszych). */
    @Query("""
            select r from DeliveryReportClosingDelivery r join fetch r.closing c
            where c.cancelledAt is null and r.originClosing is null and r.delivery.id in :deliveryIds""")
    List<DeliveryReportClosingDelivery> findClosedDeliveries(Collection<UUID> deliveryIds);
}
