package com.intermanage.backend.sales.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.nomenclature.dto.NomenclatureOption;
import com.intermanage.backend.sales.dto.DeliCostsOverview;
import com.intermanage.backend.sales.dto.SalesArticleResponse;
import com.intermanage.backend.sales.dto.SalesComparisonResponse;
import com.intermanage.backend.sales.dto.SalesHistoryResponse;
import com.intermanage.backend.sales.dto.SalesReportsOverview;
import com.intermanage.backend.sales.service.DeliCostReportService;
import com.intermanage.backend.sales.service.SalesReportService;

/** Raporty sprzedaży sklepu (kafelki w narzędziu Sprzedaż i raporty). */
@RestController
@RequestMapping("/api/stores/{storeId}/reports")
public class SalesReportController {

    private final SalesReportService salesReportService;
    private final DeliCostReportService deliCostReportService;

    public SalesReportController(SalesReportService salesReportService,
            DeliCostReportService deliCostReportService) {
        this.salesReportService = salesReportService;
        this.deliCostReportService = deliCostReportService;
    }

    /** Za jakie dni jest sprzedaż i z kiedy jest najnowszy raport. */
    @GetMapping
    @PreAuthorize("hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public SalesReportsOverview overview(@PathVariable UUID storeId) {
        return salesReportService.overview(storeId);
    }

    /**
     * Porównanie sprzedaży dwóch okresów na jednym poziomie nomenklatury: bez {@code parent} rejony, z kodem rejonu
     * rodziny, rodziny podrodziny, podrodziny artykuły; {@code unassigned=true} artykuły bez nomenklatury.
     * {@code nomenclatures} (powtarzane, kody dowolnego poziomu) zawęża porównanie do wybranych pozycji.
     */
    @GetMapping("/sales-comparison")
    @PreAuthorize("hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public SalesComparisonResponse salesComparison(@PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate previousFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate previousTo,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate currentFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate currentTo,
            @RequestParam(required = false) String parent,
            @RequestParam(defaultValue = "false") boolean unassigned,
            @RequestParam(required = false) List<String> nomenclatures) {
        return salesReportService.compare(storeId, previousFrom, previousTo, currentFrom, currentTo, parent,
                unassigned, nomenclatures);
    }

    /** Nomenklatura sklepu do filtra raportów. */
    @GetMapping("/nomenclatures")
    @PreAuthorize("hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public List<NomenclatureOption> nomenclatures(@PathVariable UUID storeId) {
        return salesReportService.nomenclatures(storeId);
    }

    /**
     * Sprzedaż dzień po dniu do wykresu: artykułu ({@code itm8}) albo pozycji nomenklatury ({@code parent}, pusty =
     * cały sklep, {@code unassigned=true} = bez nomenklatury), z tym samym filtrem {@code nomenclatures} co porównanie.
     */
    @GetMapping("/sales-history")
    @PreAuthorize("hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public SalesHistoryResponse salesHistory(@PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String itm8,
            @RequestParam(required = false) String parent,
            @RequestParam(defaultValue = "false") boolean unassigned,
            @RequestParam(required = false) List<String> nomenclatures) {
        return salesReportService.history(storeId, from, to, itm8, parent, unassigned, nomenclatures);
    }

    /** Artykuł z kartoteki: dane, ceny, stan i rotacja z ostatniego raportu kartoteki. */
    @GetMapping("/articles/{itm8}")
    @PreAuthorize("hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public SalesArticleResponse article(@PathVariable UUID storeId, @PathVariable String itm8) {
        return salesReportService.article(storeId, itm8);
    }

    /** Skąd są koszty garmażu: najnowszy raport dokumentów i do którego dnia sięgają wgrane raporty. */
    @GetMapping("/deli-costs")
    @PreAuthorize("hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public DeliCostsOverview deliCosts(@PathVariable UUID storeId) {
        return deliCostReportService.overview(storeId);
    }

    /**
     * Koszty garmażu: porównanie sprzedaży rejonu 36 z wartością protokołów reprezentacji w miejscu zakupu FIFO
     * (koszt i marża tylko przy całym garmażu). {@code parent} jak w porównaniu sprzedaży, w obrębie rejonu 36.
     */
    @GetMapping("/deli-comparison")
    @PreAuthorize("hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public SalesComparisonResponse deliComparison(@PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate previousFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate previousTo,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate currentFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate currentTo,
            @RequestParam(required = false) String parent) {
        return deliCostReportService.compare(storeId, previousFrom, previousTo, currentFrom, currentTo, parent);
    }

    /** Wykres w raporcie kosztów garmażu: przy całym garmażu z kosztem protokołów reprezentacji z dnia. */
    @GetMapping("/deli-history")
    @PreAuthorize("hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public SalesHistoryResponse deliHistory(@PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String itm8,
            @RequestParam(required = false) String parent) {
        return deliCostReportService.history(storeId, from, to, itm8, parent);
    }
}
