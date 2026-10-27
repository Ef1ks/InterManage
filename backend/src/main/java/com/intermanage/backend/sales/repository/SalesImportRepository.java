package com.intermanage.backend.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.entity.SalesImport;

public interface SalesImportRepository extends JpaRepository<SalesImport, UUID> {

    @Query("select i from SalesImport i left join fetch i.importedBy where i.store.id = :storeId order by i.importedAt desc, i.id desc")
    List<SalesImport> findLatest(UUID storeId, Limit limit);

    /** Wierszy sprzedaży sklepu: każdy zapisuje dokładnie jeden import i nic go potem nie zmienia ani nie usuwa. */
    @Query("select coalesce(sum(i.createdCount), 0) from SalesImport i where i.store.id = :storeId")
    long countRows(UUID storeId);

    /** Kiedy wygenerowano najnowszy wgrany raport sprzedaży sklepu (raporty mogą być wgrywane w dowolnej kolejności). */
    @Query("select max(i.reportGeneratedAt) from SalesImport i where i.store.id = :storeId")
    Optional<LocalDateTime> findLastReportGeneratedAt(UUID storeId);

    /** Blokada importu sprzedaży sklepu do końca transakcji, jak w {@link DocumentRepository#lockForImport}. */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) l", nativeQuery = true)
    int lockForImport(String key);
}
