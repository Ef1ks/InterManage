package com.intermanage.backend.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.dto.SalesComparisonResponse;
import com.intermanage.backend.dto.SalesComparisonResponse.Level;
import com.intermanage.backend.dto.SalesComparisonRow;
import com.intermanage.backend.dto.SalesFigures;
import com.intermanage.backend.dto.SalesReportsOverview;
import com.intermanage.backend.exception.ApiException;
import com.intermanage.backend.repository.SaleRepository;
import com.intermanage.backend.repository.SalesImportRepository;

/**
 * Raporty z zapisanej sprzedaży. Porównanie sprzedaży zestawia dwa okresy (poprzedni i aktualny) jako całość, bez
 * podziału na dni, i schodzi po drzewie nomenklatury: rejony → rodziny → podrodziny → artykuły. Każdy poziom to
 * osobne zapytanie, liczone w locie z tabeli sprzedaży (miesiąc do miesiąca to ok. 100 tys. wierszy, rok ok. 1,5 mln).
 */
@Service
@Transactional(readOnly = true)
public class SalesReportService {

    /** Najdłuższy okres porównania: rok do roku, także w latach przestępnych. */
    static final int MAX_PERIOD_DAYS = 366;

    /** Rozwinięta pozycja: nic (rejony sklepu), rejon, rodzina albo podrodzina. */
    private static final Pattern PARENT = Pattern.compile("([0-9]{2}){0,3}");

    private final SaleRepository sales;
    private final SalesImportRepository imports;

    public SalesReportService(SaleRepository sales, SalesImportRepository imports) {
        this.sales = sales;
        this.imports = imports;
    }

    public SalesReportsOverview overview(UUID storeId) {
        SaleRepository.Coverage coverage = sales.findCoverage(storeId);
        return new SalesReportsOverview(coverage.getFirstDate(), coverage.getLastDate(),
                imports.findLastReportGeneratedAt(storeId).orElse(null));
    }

    /**
     * Jeden poziom porównania pod {@code parent}: pusty tekst daje rejony, kod rejonu jego rodziny, kod rodziny
     * podrodziny, a kod podrodziny artykuły. {@code unassigned} daje artykuły bez nomenklatury (spoza kartoteki).
     */
    public SalesComparisonResponse compare(UUID storeId, LocalDate previousFrom, LocalDate previousTo,
            LocalDate currentFrom, LocalDate currentTo, String parent, boolean unassigned) {
        checkPeriod(previousFrom, previousTo, "Poprzedni okres");
        checkPeriod(currentFrom, currentTo, "Aktualny okres");
        String code = parent == null ? "" : parent.strip();
        if (!PARENT.matcher(code).matches() || unassigned && !code.isEmpty()) {
            throw ApiException.badRequest("Nieznana pozycja nomenklatury: " + code);
        }

        Level level;
        List<SaleRepository.ComparisonRow> found;
        if (unassigned || code.length() == 6) {
            level = Level.PRODUCT;
            found = sales.compareArticles(storeId, previousFrom, previousTo, currentFrom, currentTo, code);
        } else {
            level = switch (code.length()) {
                case 0 -> Level.REGION;
                case 2 -> Level.FAMILY;
                default -> Level.SUBFAMILY;
            };
            found = sales.compareByNomenclature(storeId, previousFrom, previousTo, currentFrom, currentTo,
                    code.length() + 2, code);
        }

        List<SalesComparisonRow> rows = found.stream().map(SalesReportService::toRow).toList();
        SalesFigures previousTotal = rows.stream().map(SalesComparisonRow::previous)
                .reduce(SalesFigures.ZERO, SalesFigures::plus);
        SalesFigures currentTotal = rows.stream().map(SalesComparisonRow::current)
                .reduce(SalesFigures.ZERO, SalesFigures::plus);
        return new SalesComparisonResponse(previousFrom, previousTo, currentFrom, currentTo,
                unassigned ? null : code, level, rows, previousTotal, currentTotal);
    }

    private static void checkPeriod(LocalDate from, LocalDate to, String label) {
        if (from.isAfter(to)) {
            throw ApiException.badRequest(label + ": data początkowa jest późniejsza niż końcowa");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_PERIOD_DAYS) {
            throw ApiException.badRequest(label + " może mieć najwyżej " + MAX_PERIOD_DAYS + " dni");
        }
    }

    private static SalesComparisonRow toRow(SaleRepository.ComparisonRow row) {
        return new SalesComparisonRow(
                row.getCode() == null ? "" : row.getCode(),
                row.getName(),
                row.getEan(),
                row.getBrand(),
                new SalesFigures(row.getPreviousQuantity(), row.getPreviousCostValue(),
                        row.getPreviousSaleValueGross(), row.getPreviousSaleValueNet(),
                        row.getPreviousMarginValue()),
                new SalesFigures(row.getCurrentQuantity(), row.getCurrentCostValue(),
                        row.getCurrentSaleValueGross(), row.getCurrentSaleValueNet(),
                        row.getCurrentMarginValue()));
    }
}
