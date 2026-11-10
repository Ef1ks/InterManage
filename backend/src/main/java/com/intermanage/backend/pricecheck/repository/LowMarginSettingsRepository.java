package com.intermanage.backend.pricecheck.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.pricecheck.entity.LowMarginSettings;

public interface LowMarginSettingsRepository extends JpaRepository<LowMarginSettings, UUID> {

    Optional<LowMarginSettings> findByStoreId(UUID storeId);
}
