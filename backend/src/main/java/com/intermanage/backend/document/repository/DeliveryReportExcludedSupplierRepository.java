package com.intermanage.backend.document.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.document.entity.DeliveryReportExcludedSupplier;

public interface DeliveryReportExcludedSupplierRepository extends JpaRepository<DeliveryReportExcludedSupplier, UUID> {

    @Query("""
            select s from DeliveryReportExcludedSupplier s where s.store.id = :storeId and s.removedAt is null
            order by lower(coalesce(s.supplierName, s.supplierCode)), s.supplierCode""")
    List<DeliveryReportExcludedSupplier> findActive(UUID storeId);
}
