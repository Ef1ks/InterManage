package com.intermanage.backend.sales.controller;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.sales.dto.SalesComparisonResponse;
import com.intermanage.backend.sales.dto.SalesReportsOverview;
import com.intermanage.backend.sales.service.SalesReportService;

/** Raporty sprzedaży sklepu (kafelki w narzędziu Sprzedaż i raporty). */
@RestController
@RequestMapping("/api/stores/{storeId}/reports")
public class SalesReportController {

    private final SalesReportService salesReportService;

    public SalesReportController(SalesReportService salesReportService) {
        this.salesReportService = salesReportService;
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
     */
    @GetMapping("/sales-comparison")
    @PreAuthorize("hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public SalesComparisonResponse salesComparison(@PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate previousFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate previousTo,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate currentFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate currentTo,
            @RequestParam(required = false) String parent,
            @RequestParam(defaultValue = "false") boolean unassigned) {
        return salesReportService.compare(storeId, previousFrom, previousTo, currentFrom, currentTo, parent,
                unassigned);
    }
}
