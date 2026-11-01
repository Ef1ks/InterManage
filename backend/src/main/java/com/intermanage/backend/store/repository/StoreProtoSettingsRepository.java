package com.intermanage.backend.store.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.store.entity.StoreProtoSettings;

public interface StoreProtoSettingsRepository extends JpaRepository<StoreProtoSettings, UUID> {

    @EntityGraph(attributePaths = "updatedBy")
    Optional<StoreProtoSettings> findByStoreId(UUID storeId);
}
