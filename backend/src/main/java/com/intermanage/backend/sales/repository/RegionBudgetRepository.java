package com.intermanage.backend.sales.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.sales.entity.RegionBudget;

public interface RegionBudgetRepository extends JpaRepository<RegionBudget, UUID> {

    @EntityGraph(attributePaths = "updatedBy")
    List<RegionBudget> findByStoreIdOrderByRegionCode(UUID storeId);
}
