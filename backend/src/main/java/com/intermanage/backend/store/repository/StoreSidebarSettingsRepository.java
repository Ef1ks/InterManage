package com.intermanage.backend.store.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.store.entity.StoreSidebarSettings;

public interface StoreSidebarSettingsRepository extends JpaRepository<StoreSidebarSettings, UUID> {

    Optional<StoreSidebarSettings> findByStoreId(UUID storeId);

    List<StoreSidebarSettings> findByStoreIdIn(Collection<UUID> storeIds);
}
