package com.intermanage.backend.product.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.product.entity.PriceMarkdown;

public interface PriceMarkdownRepository extends JpaRepository<PriceMarkdown, UUID> {

    /** Lista cenówek należy do osoby w sklepie: każdy widzi tylko to, co sam dodał. */
    List<PriceMarkdown> findByStoreIdAndCreatedByIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(UUID storeId,
            UUID userId);

    List<PriceMarkdown> findByStoreIdAndCreatedByIdAndIdIn(UUID storeId, UUID userId, Collection<UUID> ids);

    /** Zmiany cen tych artykułów na liście tej osoby, które jeszcze czekają na cenówkę. */
    @Query("""
            select m from PriceMarkdown m
            where m.storeId = :storeId and m.createdBy.id = :userId and m.itm8 in :itm8s and m.kind = com.intermanage.backend.product.entity.PriceLabelKind.PRICE_CHANGE
              and m.labelsGeneratedAt is null and m.deletedAt is null""")
    List<PriceMarkdown> findPendingPriceChanges(UUID storeId, UUID userId, Collection<String> itm8s);
}
