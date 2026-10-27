package com.intermanage.backend.document.repository;

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

    /** Najnowsze dane w bazie: data wygenerowania najświeższego wgranego raportu. */
    @Query("select max(i.reportGeneratedAt) from DocumentImport i where i.store.id = :storeId")
    Optional<LocalDateTime> findNewestReportGeneratedAt(UUID storeId);
}
