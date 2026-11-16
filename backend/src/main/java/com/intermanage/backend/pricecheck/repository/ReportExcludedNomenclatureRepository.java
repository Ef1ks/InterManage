package com.intermanage.backend.pricecheck.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.pricecheck.entity.ReportExcludedNomenclature;
import com.intermanage.backend.pricecheck.entity.ReviewedReport;

public interface ReportExcludedNomenclatureRepository extends JpaRepository<ReportExcludedNomenclature, UUID> {

    @Query("""
            select n from ReportExcludedNomenclature n
            where n.store.id = :storeId and n.report = :report and n.removedAt is null
            order by n.code""")
    List<ReportExcludedNomenclature> findActive(UUID storeId, ReviewedReport report);
}
