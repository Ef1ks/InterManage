package com.intermanage.backend.agent.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.agent.entity.StoreAgentSettings;

public interface StoreAgentSettingsRepository extends JpaRepository<StoreAgentSettings, UUID> {
}
