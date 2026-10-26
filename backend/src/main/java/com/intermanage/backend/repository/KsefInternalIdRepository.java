package com.intermanage.backend.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.entity.KsefInternalId;

public interface KsefInternalIdRepository extends JpaRepository<KsefInternalId, UUID> {

    @Query("select i from KsefInternalId i where i.store.id = :storeId and i.removedAt is null order by i.internalId")
    List<KsefInternalId> findActive(UUID storeId);

    @Query("select i from KsefInternalId i where i.id = :id and i.store.id = :storeId")
    Optional<KsefInternalId> findInStore(UUID storeId, UUID id);

    @Query("""
            select count(i) > 0 from KsefInternalId i
            where i.store.id = :storeId and i.internalId = :internalId and i.removedAt is null""")
    boolean existsActive(UUID storeId, String internalId);
}
