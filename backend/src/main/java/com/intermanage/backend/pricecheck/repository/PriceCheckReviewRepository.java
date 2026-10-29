package com.intermanage.backend.pricecheck.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.pricecheck.entity.PriceCheckReview;

public interface PriceCheckReviewRepository extends JpaRepository<PriceCheckReview, UUID> {

    /** Wszystkie wpisy bez znacznika w sklepie, od najnowszego (przyjęcie ze statusem zostaje w raporcie bez terminu). */
    @Query("""
            select r from PriceCheckReview r join fetch r.document left join fetch r.createdBy
            where r.store.id = :storeId and r.deletedAt is null
            order by r.createdAt desc""")
    List<PriceCheckReview> findActive(UUID storeId);

    /** Wpisy bez znacznika jednego przyjęcia, od najnowszego. */
    @Query("""
            select r from PriceCheckReview r left join fetch r.createdBy
            where r.store.id = :storeId and r.document.id = :documentId and r.deletedAt is null
            order by r.createdAt desc""")
    List<PriceCheckReview> findActiveByDocument(UUID storeId, UUID documentId);

    @Query("select r from PriceCheckReview r left join fetch r.createdBy where r.id = :id and r.store.id = :storeId")
    Optional<PriceCheckReview> findInStore(UUID storeId, UUID id);
}
