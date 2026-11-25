package com.intermanage.backend.ksef.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.ksef.entity.KsefAllSuppliersAccess;

public interface KsefAllSuppliersAccessRepository extends JpaRepository<KsefAllSuppliersAccess, UUID> {

    @Query("select a from KsefAllSuppliersAccess a where a.store.id = :storeId and a.user.id = :userId"
            + " and a.removedAt is null")
    Optional<KsefAllSuppliersAccess> findActive(UUID storeId, UUID userId);

    @Query("select a.user.id from KsefAllSuppliersAccess a where a.store.id = :storeId and a.removedAt is null")
    List<UUID> findActiveUserIds(UUID storeId);
}
