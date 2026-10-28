package com.intermanage.backend.pricecheck.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.pricecheck.entity.PriceCheckReview;

public interface PriceCheckReviewRepository extends JpaRepository<PriceCheckReview, UUID> {

    /** Wpisy bez znacznika do przyjęć z datą w [from, to), od najnowszego. */
    @Query("""
            select r from PriceCheckReview r join fetch r.document d left join fetch r.createdBy
            where r.store.id = :storeId and r.deletedAt is null and d.documentDate >= :from and d.documentDate < :to
            order by r.createdAt desc""")
    List<PriceCheckReview> findActiveBetween(UUID storeId, LocalDateTime from, LocalDateTime to);

    /** Wpisy bez znacznika jednego przyjęcia, od najnowszego. */
    @Query("""
            select r from PriceCheckReview r left join fetch r.createdBy
            where r.store.id = :storeId and r.document.id = :documentId and r.deletedAt is null
            order by r.createdAt desc""")
    List<PriceCheckReview> findActiveByDocument(UUID storeId, UUID documentId);

    @Query("select r from PriceCheckReview r left join fetch r.createdBy where r.id = :id and r.store.id = :storeId")
    Optional<PriceCheckReview> findInStore(UUID storeId, UUID id);
}
