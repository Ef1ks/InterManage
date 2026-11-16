package com.intermanage.backend.document.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.document.entity.DocumentImport;

public interface DocumentImportRepository extends JpaRepository<DocumentImport, UUID> {

    @EntityGraph(attributePaths = "importedBy")
    @Query("select i from DocumentImport i where i.store.id = :storeId order by i.importedAt desc, i.id desc")
    List<DocumentImport> findLatest(UUID storeId, Limit limit);

    /** Wgrania starsze niż {@code before}: kolejna porcja wspólnej historii importów. */
    @EntityGraph(attributePaths = "importedBy")
    @Query("select i from DocumentImport i where i.store.id = :storeId and i.importedAt < :before order by i.importedAt desc, i.id desc")
    List<DocumentImport> findLatestBefore(UUID storeId, Instant before, Limit limit);

    /** Najnowsze dane w bazie: data wygenerowania najświeższego wgranego raportu. */
    @Query("select max(i.reportGeneratedAt) from DocumentImport i where i.store.id = :storeId")
    Optional<LocalDateTime> findNewestReportGeneratedAt(UUID storeId);

    /** Do jakiego dnia sięgają wgrane raporty dokumentów (koniec okresu "Wybór danych"). */
    @Query("select max(i.periodTo) from DocumentImport i where i.store.id = :storeId")
    Optional<LocalDate> findLastPeriodTo(UUID storeId);

    /** Okres raportu wgranych dokumentów ("Wybór danych"). */
    interface CoveredPeriod {
        LocalDate getPeriodFrom();

        LocalDate getPeriodTo();
    }

    @Query("select i.periodFrom as periodFrom, i.periodTo as periodTo from DocumentImport i "
            + "where i.store.id = :storeId and i.periodFrom is not null and i.periodTo is not null")
    List<CoveredPeriod> findCoveredPeriods(UUID storeId);
}
