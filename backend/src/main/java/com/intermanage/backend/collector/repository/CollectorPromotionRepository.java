package com.intermanage.backend.collector.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.collector.entity.CollectorPromotion;

public interface CollectorPromotionRepository extends JpaRepository<CollectorPromotion, UUID> {

    @Query("""
            select p from CollectorPromotion p join fetch p.collector left join fetch p.insertedBy
            where p.storeId = :storeId and p.deletedAt is null
            order by p.receivedAt desc, p.id desc""")
    List<CollectorPromotion> findLatest(UUID storeId, Limit limit);

    @Query("""
            select p from CollectorPromotion p join fetch p.collector left join fetch p.insertedBy
            where p.storeId = :storeId and p.id = :id and p.deletedAt is null""")
    Optional<CollectorPromotion> findActive(UUID storeId, UUID id);

    Optional<CollectorPromotion> findByMacroJobId(UUID macroJobId);

    /** Promocje, których nikt jeszcze nie wstawił ani nie zlecił makrem (licznik w menu). */
    @Query("""
            select count(p) from CollectorPromotion p
            where p.storeId = :storeId and p.deletedAt is null and p.insertedAt is null and p.macroJobId is null""")
    long countWaiting(UUID storeId);
}
