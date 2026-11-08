package com.intermanage.backend.sales.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.sales.entity.SlowMovingExcludedArticle;

public interface SlowMovingExcludedArticleRepository extends JpaRepository<SlowMovingExcludedArticle, UUID> {

    @Query("""
            select excluded from SlowMovingExcludedArticle excluded
            join fetch excluded.product product
            left join fetch excluded.excludedBy
            where product.store.id = :storeId and excluded.removedAt is null
            order by product.name, product.itm8""")
    List<SlowMovingExcludedArticle> findActive(UUID storeId);

    @Query("""
            select excluded.product.itm8 from SlowMovingExcludedArticle excluded
            where excluded.product.store.id = :storeId and excluded.removedAt is null""")
    Set<String> findActiveItm8s(UUID storeId);

    @Query("""
            select excluded from SlowMovingExcludedArticle excluded
            join fetch excluded.product product
            left join fetch excluded.excludedBy
            where product.id = :productId and excluded.removedAt is null""")
    Optional<SlowMovingExcludedArticle> findActiveByProduct(UUID productId);

    @Query("""
            select excluded from SlowMovingExcludedArticle excluded
            where excluded.id = :id and excluded.product.store.id = :storeId and excluded.removedAt is null""")
    Optional<SlowMovingExcludedArticle> findActiveInStore(UUID storeId, UUID id);
}
