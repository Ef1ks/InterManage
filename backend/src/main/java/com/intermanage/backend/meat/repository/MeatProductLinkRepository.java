package com.intermanage.backend.meat.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.meat.entity.MeatProductLink;

public interface MeatProductLinkRepository extends JpaRepository<MeatProductLink, UUID> {

    @Query("""
            select l from MeatProductLink l left join fetch l.product
            where l.storeId = :storeId and l.supplierId = :supplierId and l.documentKey in :keys
            and l.deletedAt is null""")
    List<MeatProductLink> findActive(UUID storeId, UUID supplierId, Collection<String> keys);
}
