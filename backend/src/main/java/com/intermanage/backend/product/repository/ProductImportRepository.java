package com.intermanage.backend.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.entity.ProductImport;

public interface ProductImportRepository extends JpaRepository<ProductImport, UUID> {

    @EntityGraph(attributePaths = "importedBy")
    @Query("select i from ProductImport i where i.store.id = :storeId order by i.importedAt desc, i.id desc")
    List<ProductImport> findLatest(UUID storeId, Limit limit);

    @Query("select max(i.reportGeneratedAt) from ProductImport i where i.store.id = :storeId")
    Optional<LocalDateTime> findNewestReportGeneratedAt(UUID storeId);

    boolean existsByIdAndStoreId(UUID id, UUID storeId);
}
