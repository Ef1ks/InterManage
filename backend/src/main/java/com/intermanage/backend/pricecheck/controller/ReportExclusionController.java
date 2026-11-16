package com.intermanage.backend.pricecheck.controller;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.document.dto.DeliveryReportExcludedSuppliersRequest;
import com.intermanage.backend.pricecheck.dto.ReportExcludedNomenclaturesRequest;
import com.intermanage.backend.pricecheck.dto.ReportExclusions;
import com.intermanage.backend.pricecheck.entity.ReviewedReport;
import com.intermanage.backend.pricecheck.service.ReportExclusionService;

import jakarta.validation.Valid;

/**
 * Pomijane nomenklatury i dostawcy raportów weryfikujących zakupy ({@code report}: {@code LOW_MARGIN} weryfikacja
 * przyjęć, {@code OVERPAYMENT} przepłacone, {@code BASE_PURCHASE} zakup poza bazą, {@code ARTICLE_MARGIN} artykuły
 * z za niską marżą). Każda zmiana zwraca obie listy.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/reports/exclusions/{report}")
public class ReportExclusionController {

    private final ReportExclusionService service;

    public ReportExclusionController(ReportExclusionService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public ReportExclusions get(@PathVariable UUID storeId, @PathVariable ReviewedReport report) {
        return service.get(storeId, report);
    }

    @PutMapping("/nomenclatures")
    @PreAuthorize("hasAuthority('SALES:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public ReportExclusions nomenclatures(@PathVariable UUID storeId, @PathVariable ReviewedReport report,
            @Valid @RequestBody ReportExcludedNomenclaturesRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.replaceNomenclatures(storeId, report, request.codes(), principal.getId());
    }

    @PutMapping("/suppliers")
    @PreAuthorize("hasAuthority('SALES:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public ReportExclusions suppliers(@PathVariable UUID storeId, @PathVariable ReviewedReport report,
            @Valid @RequestBody DeliveryReportExcludedSuppliersRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.replaceSuppliers(storeId, report, request.supplierCodes(), principal.getId());
    }
}
