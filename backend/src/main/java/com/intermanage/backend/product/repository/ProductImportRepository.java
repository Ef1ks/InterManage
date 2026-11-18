package com.intermanage.backend.product.repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.product.entity.ProductImport;

public interface ProductImportRepository extends JpaRepository<ProductImport, UUID> {

    @EntityGraph(attributePaths = "importedBy")
    @Query("select i from ProductImport i where i.store.id = :storeId order by i.importedAt desc, i.id desc")
    List<ProductImport> findLatest(UUID storeId, Limit limit);

    /** Wgrania starsze niż {@code before}: kolejna porcja wspólnej historii importów. */
    @EntityGraph(attributePaths = "importedBy")
    @Query("select i from ProductImport i where i.store.id = :storeId and i.importedAt < :before order by i.importedAt desc, i.id desc")
    List<ProductImport> findLatestBefore(UUID storeId, Instant before, Limit limit);

    /** Id importu z najnowszym raportem: artykuł, którego w nim nie było, zniknął z kartoteki systemu sklepu. */
    @Query("select i.id from ProductImport i where i.store.id = :storeId order by i.reportGeneratedAt desc, i.id desc")
    List<UUID> findNewestReportIds(UUID storeId, Limit limit);

    @Query("select max(i.reportGeneratedAt) from ProductImport i where i.store.id = :storeId")
    Optional<LocalDateTime> findNewestReportGeneratedAt(UUID storeId);

    boolean existsByIdAndStoreId(UUID id, UUID storeId);
}
