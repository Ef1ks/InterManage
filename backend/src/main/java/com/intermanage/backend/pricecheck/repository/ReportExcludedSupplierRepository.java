package com.intermanage.backend.pricecheck.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.pricecheck.entity.ReportExcludedSupplier;
import com.intermanage.backend.pricecheck.entity.ReviewedReport;

public interface ReportExcludedSupplierRepository extends JpaRepository<ReportExcludedSupplier, UUID> {

    @Query("""
            select s from ReportExcludedSupplier s
            where s.store.id = :storeId and s.report = :report and s.removedAt is null
            order by lower(coalesce(s.supplierName, s.supplierCode)), s.supplierCode""")
    List<ReportExcludedSupplier> findActive(UUID storeId, ReviewedReport report);
}
