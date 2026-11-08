package com.intermanage.backend.product.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.product.entity.PriceMarkdown;

public interface PriceMarkdownRepository extends JpaRepository<PriceMarkdown, UUID> {

    List<PriceMarkdown> findByStoreIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(UUID storeId);

    List<PriceMarkdown> findByStoreIdAndIdIn(UUID storeId, Collection<UUID> ids);
}
