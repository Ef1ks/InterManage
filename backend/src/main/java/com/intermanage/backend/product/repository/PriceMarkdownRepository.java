package com.intermanage.backend.product.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.product.entity.PriceMarkdown;

public interface PriceMarkdownRepository extends JpaRepository<PriceMarkdown, UUID> {

    List<PriceMarkdown> findByStoreIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(UUID storeId);

    List<PriceMarkdown> findByStoreIdAndIdIn(UUID storeId, Collection<UUID> ids);

    /** Zmiany cen tych artykułów, które jeszcze czekają na cenówkę. */
    @Query("""
            select m from PriceMarkdown m
            where m.storeId = :storeId and m.itm8 in :itm8s and m.kind = com.intermanage.backend.product.entity.PriceLabelKind.PRICE_CHANGE
              and m.labelsGeneratedAt is null and m.deletedAt is null""")
    List<PriceMarkdown> findPendingPriceChanges(UUID storeId, Collection<String> itm8s);
}
