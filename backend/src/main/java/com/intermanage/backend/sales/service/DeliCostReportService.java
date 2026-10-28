package com.intermanage.backend.sales.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.repository.DocumentImportRepository;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.nomenclature.entity.Nomenclature;
import com.intermanage.backend.nomenclature.repository.NomenclatureRepository;
import com.intermanage.backend.sales.dto.DeliCostsOverview;
import com.intermanage.backend.sales.dto.SalesComparisonResponse;
import com.intermanage.backend.sales.dto.SalesComparisonResponse.Level;
import com.intermanage.backend.sales.dto.SalesComparisonRow;
import com.intermanage.backend.sales.dto.SalesFigures;
import com.intermanage.backend.sales.dto.SalesHistoryDay;
import com.intermanage.backend.sales.dto.SalesHistoryResponse;

/**
 * Koszty garmażu: porównanie sprzedaży jak {@link SalesReportService}, ale tylko rejonu 36 (garmaż), a w miejsce
 * zakupu FIFO wartość protokołów reprezentacji (RWREP) z okresu, bo na nich sklep wydaje składniki zużyte do
 * produkcji garmażu. Składniki są z różnych nomenklatur i nie wiadomo, do którego wyrobu poszły, więc koszt i marża
 * są tylko przy całym garmażu (wiersz rejonu 36, suma, wykres rejonu); niżej koszt i marża są puste.
 */
@Service
@Transactional(readOnly = true)
public class DeliCostReportService {

    /** Rejon nomenklatury garmażu. */
    static final String DELI_REGION = "36";

    private static final List<String> DELI_FILTER = List.of(DELI_REGION);

    private final SalesReportService salesReports;
    private final DocumentRepository documents;
    private final DocumentImportRepository documentImports;
    private final NomenclatureRepository nomenclatures;

    public DeliCostReportService(SalesReportService salesReports, DocumentRepository documents,
            DocumentImportRepository documentImports, NomenclatureRepository nomenclatures) {
        this.salesReports = salesReports;
        this.documents = documents;
        this.documentImports = documentImports;
        this.nomenclatures = nomenclatures;
    }

    public DeliCostsOverview overview(UUID storeId) {
        return new DeliCostsOverview(documentImports.findNewestReportGeneratedAt(storeId).orElse(null),
                documentImports.findLastPeriodTo(storeId).orElse(null));
    }

    /**
     * Jeden poziom porównania garmażu: bez {@code parent} wiersz rejonu 36 z kosztem protokołów reprezentacji
     * (także bez sprzedaży, gdy były koszty), z kodem rejonu jego rodziny i dalej w dół jak w porównaniu sprzedaży.
     */
    public SalesComparisonResponse compare(UUID storeId, LocalDate previousFrom, LocalDate previousTo,
            LocalDate currentFrom, LocalDate currentTo, String parent) {
        String code = parent == null ? "" : parent.strip();
        if (!code.isEmpty() && !code.startsWith(DELI_REGION)) {
            throw ApiException.badRequest("Raport kosztów garmażu obejmuje tylko rejon " + DELI_REGION);
        }
        SalesComparisonResponse sales = salesReports.compare(storeId, previousFrom, previousTo, currentFrom,
                currentTo, code, false, DELI_FILTER);
        if (sales.level() != Level.REGION) {
            List<SalesComparisonRow> rows = sales.rows().stream()
                    .map(row -> new SalesComparisonRow(row.code(), row.name(), row.ean(), row.brand(),
                            withoutCost(row.previous()), withoutCost(row.current())))
                    .toList();
            return new SalesComparisonResponse(previousFrom, previousTo, currentFrom, currentTo, sales.parent(),
                    sales.level(), rows, withoutCost(sales.previousTotal()), withoutCost(sales.currentTotal()));
        }

        BigDecimal previousCost = cost(storeId, previousFrom, previousTo);
        BigDecimal currentCost = cost(storeId, currentFrom, currentTo);
        SalesComparisonRow region = sales.rows().stream().filter(row -> DELI_REGION.equals(row.code())).findFirst()
                .orElse(null);
        if (region == null && previousCost.signum() == 0 && currentCost.signum() == 0) {
            return new SalesComparisonResponse(previousFrom, previousTo, currentFrom, currentTo, sales.parent(),
                    Level.REGION, List.of(), withCost(SalesFigures.ZERO, BigDecimal.ZERO),
                    withCost(SalesFigures.ZERO, BigDecimal.ZERO));
        }
        SalesComparisonRow row = new SalesComparisonRow(DELI_REGION,
                region != null ? region.name() : regionName(storeId), null, null,
                withCost(region != null ? region.previous() : SalesFigures.ZERO, previousCost),
                withCost(region != null ? region.current() : SalesFigures.ZERO, currentCost));
        return new SalesComparisonResponse(previousFrom, previousTo, currentFrom, currentTo, sales.parent(),
                Level.REGION, List.of(row), row.previous(), row.current());
    }

    /**
     * Sprzedaż dzień po dniu do wykresu: artykułu ({@code itm8}) albo pozycji nomenklatury garmażu. Przy całym
     * garmażu (bez {@code parent} albo rejon 36) z kosztem protokołów reprezentacji z dnia, niżej bez kosztu.
     */
    public SalesHistoryResponse history(UUID storeId, LocalDate from, LocalDate to, String itm8, String parent) {
        String code = parent == null ? "" : parent.strip();
        boolean article = itm8 != null && !itm8.isBlank();
        if (!article && !code.isEmpty() && !code.startsWith(DELI_REGION)) {
            throw ApiException.badRequest("Raport kosztów garmażu obejmuje tylko rejon " + DELI_REGION);
        }
        SalesHistoryResponse sales = salesReports.history(storeId, from, to, itm8, code, false, DELI_FILTER);
        if (article || !(code.isEmpty() || code.equals(DELI_REGION))) {
            return new SalesHistoryResponse(from, to,
                    sales.days().stream()
                            .map(day -> new SalesHistoryDay(day.date(), withoutCost(day.figures())))
                            .toList(),
                    withoutCost(sales.total()));
        }

        Map<LocalDate, BigDecimal> costs = new HashMap<>();
        documents.sumPurchaseValueByDay(storeId, DocumentType.RWREP.name(), from, to)
                .forEach(day -> costs.put(day.getDay(), day.getPurchaseValue().negate()));
        BigDecimal totalCost = costs.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return new SalesHistoryResponse(from, to,
                sales.days().stream()
                        .map(day -> new SalesHistoryDay(day.date(),
                                withCost(day.figures(), costs.getOrDefault(day.date(), BigDecimal.ZERO))))
                        .toList(),
                withCost(sales.total(), totalCost));
    }

    /** Wartość protokołów reprezentacji z okresu: wydania mają wartość z minusem, koszt to jej przeciwność. */
    private BigDecimal cost(UUID storeId, LocalDate from, LocalDate to) {
        return documents.sumPurchaseValueByDay(storeId, DocumentType.RWREP.name(), from, to).stream()
                .map(DocumentRepository.DayValue::getPurchaseValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .negate();
    }

    private String regionName(UUID storeId) {
        return nomenclatures.findByStoreIdAndCodeIn(storeId, List.of(DELI_REGION)).stream().findFirst()
                .map(Nomenclature::getName).orElse(null);
    }

    /** Koszt protokołów zamiast zakupu FIFO, marża = sprzedaż netto - koszt. */
    private static SalesFigures withCost(SalesFigures figures, BigDecimal cost) {
        return new SalesFigures(figures.quantity(), cost, figures.saleValueGross(), figures.saleValueNet(),
                figures.saleValueNet().subtract(cost));
    }

    private static SalesFigures withoutCost(SalesFigures figures) {
        return new SalesFigures(figures.quantity(), null, figures.saleValueGross(), figures.saleValueNet(), null);
    }
}
