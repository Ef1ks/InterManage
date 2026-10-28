package com.intermanage.backend.pricecheck.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.pricecheck.entity.PriceCheckSettings;

public interface PriceCheckSettingsRepository extends JpaRepository<PriceCheckSettings, UUID> {

    @EntityGraph(attributePaths = "updatedBy")
    Optional<PriceCheckSettings> findByStoreId(UUID storeId);
}
