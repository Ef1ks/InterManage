package com.intermanage.backend.pricecheck.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.pricecheck.entity.ArticleMarginVerification;

public interface ArticleMarginVerificationRepository extends JpaRepository<ArticleMarginVerification, UUID> {

    @Query("""
            select v from ArticleMarginVerification v left join fetch v.verifiedBy
            where v.store.id = :storeId and v.cancelledAt is null""")
    List<ArticleMarginVerification> findActive(UUID storeId);

    @Query("""
            select v from ArticleMarginVerification v
            where v.store.id = :storeId and v.cancelledAt is null and v.itm8 in :itm8s""")
    List<ArticleMarginVerification> findActive(UUID storeId, Collection<String> itm8s);

    @Query("select v from ArticleMarginVerification v where v.store.id = :storeId and v.id in :ids")
    List<ArticleMarginVerification> findInStore(UUID storeId, Collection<UUID> ids);
}
