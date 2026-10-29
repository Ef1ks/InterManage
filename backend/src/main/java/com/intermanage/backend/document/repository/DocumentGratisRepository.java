package com.intermanage.backend.document.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.document.entity.DocumentGratis;

public interface DocumentGratisRepository extends JpaRepository<DocumentGratis, UUID> {

    @Query("""
            select g from DocumentGratis g join fetch g.supplier left join fetch g.registeredBy
            where g.document.id = :documentId and g.deletedAt is null""")
    Optional<DocumentGratis> findActive(UUID documentId);

    /** Gratisy dokumentów ze strony listy, jednym zapytaniem. */
    @Query("""
            select g from DocumentGratis g join fetch g.supplier left join fetch g.registeredBy
            where g.document.id in :documentIds and g.deletedAt is null""")
    List<DocumentGratis> findActive(Collection<UUID> documentIds);

    /** Ostatnio cofnięty gratis dokumentu (do "Przywróć"). */
    Optional<DocumentGratis> findFirstByDocumentIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(UUID documentId);

    /** Wartość gratisów jednego rodzaju w miesiącu i rejonie nomenklatury (pusty kod = linie bez nomenklatury). */
    interface MonthRegionValue {
        LocalDate getMonth();

        String getRegionCode();

        String getKind();

        BigDecimal getSaleValueGross();
    }

    /**
     * Aktywne gratisy sklepu w miesiącach [from, to] (pierwsze dni miesięcy) po miesiącu gratisu (wybranym albo
     * z daty dokumentu), rejonie nomenklatury linii i rodzaju. Wartością jest sprzedaż brutto linii.
     */
    @Query(value = """
            with gratis as (
                select g.document_id, g.kind,
                       coalesce(g.report_month, cast(date_trunc('month', d.document_date) as date)) as month
                from document_gratis g
                join documents d on d.id = g.document_id
                where d.store_id = :storeId and g.deleted_at is null
            )
            select g.month as month, coalesce(left(replace(l.nomenclature_code, ' ', ''), 2), '') as region_code,
                   g.kind as kind, coalesce(sum(l.sale_value_gross), 0) as sale_value_gross
            from gratis g
            join document_lines l on l.document_id = g.document_id
            where g.month between :from and :to
            group by 1, 2, 3
            """, nativeQuery = true)
    List<MonthRegionValue> sumByMonthAndRegion(UUID storeId, LocalDate from, LocalDate to);

    /** Liczba gratisów w każdym miesiącu [from, to] (dokument liczy się raz, choć ma linie w kilku rejonach). */
    interface MonthCount {
        LocalDate getMonth();

        long getDocumentCount();
    }

    @Query(value = """
            select coalesce(g.report_month, cast(date_trunc('month', d.document_date) as date)) as month,
                   count(*) as document_count
            from document_gratis g
            join documents d on d.id = g.document_id
            where d.store_id = :storeId and g.deleted_at is null
              and coalesce(g.report_month, cast(date_trunc('month', d.document_date) as date)) between :from and :to
            group by 1
            """, nativeQuery = true)
    List<MonthCount> countByMonth(UUID storeId, LocalDate from, LocalDate to);
}
