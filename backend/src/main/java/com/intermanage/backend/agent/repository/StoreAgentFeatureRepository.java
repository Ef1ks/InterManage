package com.intermanage.backend.agent.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.agent.entity.AgentFeature;
import com.intermanage.backend.agent.entity.StoreAgentFeature;

public interface StoreAgentFeatureRepository extends JpaRepository<StoreAgentFeature, UUID> {

    List<StoreAgentFeature> findByStoreId(UUID storeId);

    Optional<StoreAgentFeature> findByStoreIdAndFeature(UUID storeId, AgentFeature feature);
}
