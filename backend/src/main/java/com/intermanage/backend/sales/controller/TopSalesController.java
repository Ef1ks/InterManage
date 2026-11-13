package com.intermanage.backend.sales.controller;

import java.math.BigDecimal;
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

import com.intermanage.backend.sales.dto.TopSalesLevel;
import com.intermanage.backend.sales.dto.TopSalesMetric;
import com.intermanage.backend.sales.dto.TopSalesReport;
import com.intermanage.backend.sales.service.TopSalesService;

/** Topy sprzedażowe (kafelek w Sprzedaży i raportach). */
@RestController
@RequestMapping("/api/stores/{storeId}/reports/top-sales")
@PreAuthorize("hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
public class TopSalesController {

    private final TopSalesService topSalesService;

    public TopSalesController(TopSalesService topSalesService) {
        this.topSalesService = topSalesService;
    }

    /**
     * Top za okres {@code from}-{@code to}: {@code limit} najlepszych artykułów w każdej pozycji poziomu {@code level}.
     * {@code nomenclatures} (powtarzane, kody dowolnego poziomu), {@code brands} (marki z kartoteki) i {@code suppliers}
     * (kody ostatniego dostawcy z przyjęć PZ) zawężają raport, {@code compareStores} dokłada sklepy
     * zaprzyjaźnione, a {@code rankStore} wskazuje, z którego sklepu jest ranking (domyślnie {@code storeId}).
     */
    @GetMapping
    public TopSalesReport report(@PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam TopSalesMetric metric,
            @RequestParam(defaultValue = "REGION") TopSalesLevel level,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") BigDecimal minQuantity,
            @RequestParam(required = false) List<String> nomenclatures,
            @RequestParam(required = false) List<String> brands,
            @RequestParam(required = false) List<String> suppliers,
            @RequestParam(required = false) List<UUID> compareStores,
            @RequestParam(required = false) UUID rankStore) {
        return topSalesService.report(storeId, from, to, metric, level, limit, minQuantity, nomenclatures, brands,
                suppliers, compareStores, rankStore);
    }

    /** Sklepy zaprzyjaźnione do porównania. */
    @GetMapping("/stores")
    public List<TopSalesReport.Partner> partners(@PathVariable UUID storeId) {
        return topSalesService.partners(storeId);
    }
}
