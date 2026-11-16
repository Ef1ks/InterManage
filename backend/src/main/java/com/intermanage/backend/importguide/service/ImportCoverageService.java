package com.intermanage.backend.importguide.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.document.repository.DocumentImportRepository;
import com.intermanage.backend.importguide.dto.ImportCoverage;
import com.intermanage.backend.importguide.dto.ImportCoverage.DateRange;
import com.intermanage.backend.sales.repository.SalesImportRepository;

/**
 * Które dni sklep ma już z raportów sprzedaży i dokumentów. Liczą się okresy wgranych raportów, a nie dni z danymi:
 * dzień zamknięty (święto) nie ma sprzedaży, ale raport go objął, więc nie trzeba go drukować ponownie.
 */
@Service
public class ImportCoverageService {

    private final SalesImportRepository salesImports;
    private final DocumentImportRepository documentImports;

    public ImportCoverageService(SalesImportRepository salesImports, DocumentImportRepository documentImports) {
        this.salesImports = salesImports;
        this.documentImports = documentImports;
    }

    @Transactional(readOnly = true)
    public ImportCoverage coverage(UUID storeId) {
        List<DateRange> sales = new ArrayList<>();
        for (SalesImportRepository.CoveredPeriod period : salesImports.findCoveredPeriods(storeId)) {
            // Dnia wygenerowania raportu sprzedaży nie zapisujemy (sklep jeszcze sprzedawał), więc raport obejmuje
            // dni do poprzedniego (jak SalesImportService.firstIncompleteDay).
            LocalDate generated = period.getReportGeneratedAt() != null
                    ? period.getReportGeneratedAt().toLocalDate()
                    : period.getImportedAt().atZone(StoreTime.ZONE).toLocalDate();
            LocalDate to = period.getPeriodTo().isBefore(generated) ? period.getPeriodTo() : generated.minusDays(1);
            sales.add(new DateRange(period.getPeriodFrom(), to));
        }
        List<DateRange> documents = documentImports.findCoveredPeriods(storeId).stream()
                .map(period -> new DateRange(period.getPeriodFrom(), period.getPeriodTo()))
                .toList();
        return new ImportCoverage(merge(sales), merge(documents));
    }

    /** Okresy połączone w rozłączne, od najstarszego; stykające się dzień po dniu stają się jednym. */
    public static List<DateRange> merge(List<DateRange> ranges) {
        List<DateRange> sorted = ranges.stream()
                .filter(range -> !range.to().isBefore(range.from()))
                .sorted(Comparator.comparing(DateRange::from))
                .toList();
        List<DateRange> merged = new ArrayList<>();
        for (DateRange range : sorted) {
            DateRange last = merged.isEmpty() ? null : merged.getLast();
            if (last != null && !range.from().isAfter(last.to().plusDays(1))) {
                if (range.to().isAfter(last.to())) {
                    merged.set(merged.size() - 1, new DateRange(last.from(), range.to()));
                }
            } else {
                merged.add(range);
            }
        }
        return merged;
    }
}
