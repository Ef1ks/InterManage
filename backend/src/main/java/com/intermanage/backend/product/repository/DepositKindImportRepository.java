package com.intermanage.backend.product.repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.product.entity.DepositKindImport;

public interface DepositKindImportRepository extends JpaRepository<DepositKindImport, UUID> {

    @Query("select i from DepositKindImport i left join fetch i.importedBy where i.store.id = :storeId order by i.importedAt desc, i.id desc")
    List<DepositKindImport> findLatest(UUID storeId, Limit limit);

    /** Wgrania starsze niż {@code before}: kolejna porcja wspólnej historii importów. */
    @Query("select i from DepositKindImport i left join fetch i.importedBy where i.store.id = :storeId and i.importedAt < :before order by i.importedAt desc, i.id desc")
    List<DepositKindImport> findLatestBefore(UUID storeId, Instant before, Limit limit);

    @Query("select max(i.reportGeneratedAt) from DepositKindImport i where i.store.id = :storeId")
    Optional<LocalDateTime> findNewestReportGeneratedAt(UUID storeId);
}
