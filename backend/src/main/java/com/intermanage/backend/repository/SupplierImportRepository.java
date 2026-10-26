package com.intermanage.backend.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.entity.SupplierImport;

public interface SupplierImportRepository extends JpaRepository<SupplierImport, UUID> {

    @Query("select i from SupplierImport i left join fetch i.importedBy where i.store.id = :storeId order by i.importedAt desc, i.id desc")
    List<SupplierImport> findLatest(UUID storeId, Limit limit);

    @Query("select max(i.reportGeneratedAt) from SupplierImport i where i.store.id = :storeId")
    Optional<LocalDateTime> findNewestReportGeneratedAt(UUID storeId);
}
