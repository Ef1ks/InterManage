package com.intermanage.backend.dataentry.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.dataentry.entity.PriceRoundingRule;

public interface PriceRoundingRuleRepository extends JpaRepository<PriceRoundingRule, UUID> {

    @EntityGraph(attributePaths = "createdBy")
    List<PriceRoundingRule> findByStoreIdAndDeletedAtIsNullOrderByPriceFrom(UUID storeId);
}
