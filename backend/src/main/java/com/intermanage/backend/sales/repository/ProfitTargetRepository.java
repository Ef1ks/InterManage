package com.intermanage.backend.sales.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.sales.entity.ProfitTarget;

public interface ProfitTargetRepository extends JpaRepository<ProfitTarget, UUID> {

    @EntityGraph(attributePaths = "createdBy")
    List<ProfitTarget> findByStoreIdAndDeletedAtIsNullOrderByYearDesc(UUID storeId);

    Optional<ProfitTarget> findByStoreIdAndYearAndDeletedAtIsNull(UUID storeId, int year);
}
