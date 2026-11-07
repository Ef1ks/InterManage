package com.intermanage.backend.bakery.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.bakery.entity.BakeryHiddenArticle;

public interface BakeryHiddenArticleRepository extends JpaRepository<BakeryHiddenArticle, UUID> {

    @Query("""
            select hidden from BakeryHiddenArticle hidden join fetch hidden.product
            where hidden.supplier.id = :supplierId and hidden.removedAt is null""")
    List<BakeryHiddenArticle> findActive(UUID supplierId);
}
