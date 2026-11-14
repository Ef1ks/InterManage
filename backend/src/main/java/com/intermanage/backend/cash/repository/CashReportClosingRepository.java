package com.intermanage.backend.cash.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.cash.entity.CashReportClosing;
import com.intermanage.backend.cash.entity.CashReportClosingRow;

public interface CashReportClosingRepository extends JpaRepository<CashReportClosing, UUID> {

    Optional<CashReportClosing> findByIdAndStoreId(UUID id, UUID storeId);

    boolean existsByStoreIdAndReportDate(UUID storeId, LocalDate reportDate);

    boolean existsByStoreId(UUID storeId);

    /** Pozycja dokumentu w zakończonym raporcie (numer RK i pozycja na wydruku dokumentu). */
    @Query("select r from CashReportClosingRow r join fetch r.closing where r.document.id = :documentId")
    Optional<CashReportClosingRow> findRowOfDocument(UUID documentId);

    @Query("select max(c.reportDate) from CashReportClosing c where c.storeId = :storeId")
    Optional<LocalDate> lastClosedDate(UUID storeId);

    @Query("""
            select c from CashReportClosing c left join fetch c.closedBy
            where c.storeId = :storeId order by c.reportDate desc
            """)
    List<CashReportClosing> findAllNewestFirst(UUID storeId);

    /**
     * Dni z dokumentami do zakończenia: najwyżej {@code lastDay}, bez raportu zamkniętego tego dnia, od najstarszego.
     * Wiersz: id sklepu, dzień.
     */
    @Query("""
            select distinct d.storeId, d.issueDate from CashDocument d
            where d.issueDate <= :lastDay
              and not exists (select 1 from CashReportClosing c
                              where c.storeId = d.storeId and c.reportDate = d.issueDate)
            order by d.issueDate
            """)
    List<Object[]> findDaysToClose(LocalDate lastDay);
}
