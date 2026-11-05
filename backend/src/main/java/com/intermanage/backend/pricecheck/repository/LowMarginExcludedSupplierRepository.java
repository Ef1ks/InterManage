package com.intermanage.backend.pricecheck.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.pricecheck.entity.LowMarginExcludedSupplier;

public interface LowMarginExcludedSupplierRepository extends JpaRepository<LowMarginExcludedSupplier, UUID> {

    @Query("""
            select s from LowMarginExcludedSupplier s where s.store.id = :storeId and s.removedAt is null
            order by lower(coalesce(s.supplierName, s.supplierCode)), s.supplierCode""")
    List<LowMarginExcludedSupplier> findActive(UUID storeId);
}
