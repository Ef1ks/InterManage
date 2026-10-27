package com.intermanage.backend.document.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.document.entity.DeliveryCorrection;

/** Korekty przypięte do przyjęć PZ; liczą się tylko nieodpięte ({@code unlinkedAt is null}). */
public interface DeliveryCorrectionRepository extends JpaRepository<DeliveryCorrection, UUID> {

    /** Korekty przyjęć (same nagłówki), od najstarszej. */
    @Query("""
            select c from DeliveryCorrection c join fetch c.correction d left join fetch c.linkedBy
            where c.delivery.id in :deliveryIds and c.unlinkedAt is null order by d.documentDate, d.number""")
    List<DeliveryCorrection> findActiveByDeliveryIds(Collection<UUID> deliveryIds);

    /** Przyjęcia, do których przypięto korekty. */
    @Query("""
            select c from DeliveryCorrection c join fetch c.delivery
            where c.correction.id in :correctionIds and c.unlinkedAt is null""")
    List<DeliveryCorrection> findActiveByCorrectionIds(Collection<UUID> correctionIds);

    @Query("""
            select c from DeliveryCorrection c join fetch c.delivery
            where c.correction.id = :correctionId and c.unlinkedAt is null""")
    Optional<DeliveryCorrection> findActiveByCorrection(UUID correctionId);
}
