package com.intermanage.backend.sales.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.Access;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.nomenclature.dto.NomenclatureOption;
import com.intermanage.backend.product.dto.ArticleCodesResponse;
import com.intermanage.backend.product.service.ArticleCodesService;
import com.intermanage.backend.sales.dto.ArticleDeliveryResponse;
import com.intermanage.backend.sales.dto.ArticleProtocolResponse;
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

    /**
     * Zakres sprzedaży i dane artykułu to karta artykułu ({@link Access#ARTICLE_STATS}): widzą ją też Magazyn i
     * Wprowadzanie danych; wykres sprzedaży z tymi uprawnieniami tylko dla artykułu, nie dla nomenklatury i sklepu.
     */
    private static final String ARTICLE_VIEW = Access.ARTICLE_STATS;

    private final SalesReportService salesReportService;
    private final DeliCostReportService deliCostReportService;
    private final ArticleCodesService articleCodesService;

    public SalesReportController(SalesReportService salesReportService,
            DeliCostReportService deliCostReportService, ArticleCodesService articleCodesService) {
        this.salesReportService = salesReportService;
        this.deliCostReportService = deliCostReportService;
        this.articleCodesService = articleCodesService;
    }

    /** Za jakie dni jest sprzedaż i z kiedy jest najnowszy raport. */
    @GetMapping
    @PreAuthorize(ARTICLE_VIEW)
    public SalesReportsOverview overview(@PathVariable UUID storeId) {
        return salesReportService.overview(storeId);
    }

    /**
     * Porównanie sprzedaży dwóch okresów na jednym poziomie nomenklatury: bez {@code parent} rejony, z kodem rejonu
     * rodziny, rodziny podrodziny, podrodziny artykuły; {@code unassigned=true} artykuły bez nomenklatury.
     * {@code nomenclatures} (powtarzane, kody dowolnego poziomu) zawęża porównanie do wybranych pozycji.
     * {@code compareStore} dokłada do każdego wiersza aktualną sprzedaż drugiego sklepu (swojego albo
     * zaprzyjaźnionego, lista jak w raporcie lady).
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
            @RequestParam(required = false) List<String> nomenclatures,
            @RequestParam(required = false) UUID compareStore,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return salesReportService.compare(storeId, previousFrom, previousTo, currentFrom, currentTo, parent,
                unassigned, nomenclatures, compareStore, principal.getId());
    }

    /** Nomenklatura sklepu do filtrów i wyborów: słownik, widzi ją każdy z dostępem do sklepu. */
    @GetMapping("/nomenclatures")
    @PreAuthorize(Access.STORE)
    public List<NomenclatureOption> nomenclatures(@PathVariable UUID storeId) {
        return salesReportService.nomenclatures(storeId);
    }

    /**
     * Sprzedaż dzień po dniu do wykresu: artykułu ({@code itm8}) albo pozycji nomenklatury ({@code parent}, pusty =
     * cały sklep, {@code unassigned=true} = bez nomenklatury), z tym samym filtrem {@code nomenclatures} co porównanie.
     */
    @GetMapping("/sales-history")
    @PreAuthorize("(hasAuthority('SALES:VIEW') or (" + Access.ARTICLE_STATS_AUTHORITIES + " and #itm8 != null)) "
            + "and " + Access.STORE)
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
    @PreAuthorize(ARTICLE_VIEW)
    public SalesArticleResponse article(@PathVariable UUID storeId, @PathVariable String itm8) {
        return salesReportService.article(storeId, itm8);
    }

    /** Kody artykułu: EAN-y z kartoteki, par z raportów i multikodów oraz inne artykuły z tymi samymi kodami. */
    @GetMapping("/articles/{itm8}/codes")
    @PreAuthorize(ARTICLE_VIEW)
    public ArticleCodesResponse articleCodes(@PathVariable UUID storeId, @PathVariable String itm8) {
        return articleCodesService.codes(storeId, itm8);
    }

    /** Ostatnie dostawy artykułu (przyjęcia PZ): ilość i cena zakupu, od najnowszej. */
    @GetMapping("/articles/{itm8}/deliveries")
    @PreAuthorize(ARTICLE_VIEW)
    public List<ArticleDeliveryResponse> articleDeliveries(@PathVariable UUID storeId, @PathVariable String itm8) {
        return salesReportService.deliveries(storeId, itm8);
    }

    /** Ostatnie protokoły artykułu inne niż przyjęcia i korekty: rodzaj, data i ilość, od najnowszego. */
    @GetMapping("/articles/{itm8}/protocols")
    @PreAuthorize(ARTICLE_VIEW)
    public List<ArticleProtocolResponse> articleProtocols(@PathVariable UUID storeId, @PathVariable String itm8) {
        return salesReportService.protocols(storeId, itm8);
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
