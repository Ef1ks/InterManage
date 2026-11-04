package com.intermanage.backend.leaflet.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.leaflet.entity.PromoLeafletLink;

public interface PromoLeafletLinkRepository extends JpaRepository<PromoLeafletLink, UUID> {

    @EntityGraph(attributePaths = "product")
    List<PromoLeafletLink> findByStoreIdAndEanInAndDeletedAtIsNull(UUID storeId, Collection<String> eans);

    @EntityGraph(attributePaths = "product")
    Optional<PromoLeafletLink> findByStoreIdAndEanAndDeletedAtIsNull(UUID storeId, String ean);

    @EntityGraph(attributePaths = { "product", "previous" })
    Optional<PromoLeafletLink> findByIdAndStoreId(UUID id, UUID storeId);
}
