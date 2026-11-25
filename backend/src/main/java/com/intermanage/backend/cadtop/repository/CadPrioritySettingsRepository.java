package com.intermanage.backend.cadtop.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.cadtop.entity.CadPrioritySettings;

public interface CadPrioritySettingsRepository extends JpaRepository<CadPrioritySettings, UUID> {

    @Query("""
            select s.storeId from CadPrioritySettings s
            where s.automatic = true
              and exists (select 1 from Store t where t.id = s.storeId and t.active = true)""")
    List<UUID> findAutomaticStores();
}
