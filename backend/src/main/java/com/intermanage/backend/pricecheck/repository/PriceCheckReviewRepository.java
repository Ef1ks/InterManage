package com.intermanage.backend.pricecheck.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.pricecheck.entity.PriceCheckReview;
import com.intermanage.backend.pricecheck.entity.ReviewedReport;

public interface PriceCheckReviewRepository extends JpaRepository<PriceCheckReview, UUID> {

    /** Wszystkie wpisy raportu bez znacznika w sklepie, od najnowszego (przyjęcie ze statusem zostaje bez terminu). */
    @Query("""
            select r from PriceCheckReview r join fetch r.document left join fetch r.createdBy
            where r.store.id = :storeId and r.report = :report and r.deletedAt is null
            order by r.createdAt desc""")
    List<PriceCheckReview> findActive(UUID storeId, ReviewedReport report);

    /** Wpisy raportu bez znacznika jednego przyjęcia, od najnowszego. */
    @Query("""
            select r from PriceCheckReview r left join fetch r.createdBy
            where r.store.id = :storeId and r.report = :report and r.document.id = :documentId and r.deletedAt is null
            order by r.createdAt desc""")
    List<PriceCheckReview> findActiveByDocument(UUID storeId, ReviewedReport report, UUID documentId);

    @Query("select r from PriceCheckReview r left join fetch r.createdBy where r.id = :id and r.store.id = :storeId")
    Optional<PriceCheckReview> findInStore(UUID storeId, UUID id);
}
