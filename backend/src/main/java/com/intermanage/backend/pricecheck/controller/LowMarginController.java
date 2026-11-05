package com.intermanage.backend.pricecheck.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.document.dto.DeliveryReportExcludedSuppliersRequest;
import com.intermanage.backend.pricecheck.dto.LowMarginReport;
import com.intermanage.backend.pricecheck.dto.PriceCheckReviewRequest;
import com.intermanage.backend.pricecheck.dto.PriceCheckReviewResponse;
import com.intermanage.backend.pricecheck.entity.ReviewedReport;
import com.intermanage.backend.pricecheck.service.LowMarginService;
import com.intermanage.backend.pricecheck.service.PriceCheckService;

import jakarta.validation.Valid;

/**
 * Raport za niskich marż (kafelek w Sprzedaży i raportach). Cofanie i przywracanie statusu idzie przez
 * {@link PriceCheckController}, bo wpis sam wie, którego raportu dotyczy.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/reports/low-margins")
public class LowMarginController {

    private final LowMarginService lowMarginService;
    private final PriceCheckService priceCheckService;

    public LowMarginController(LowMarginService lowMarginService, PriceCheckService priceCheckService) {
        this.lowMarginService = lowMarginService;
        this.priceCheckService = priceCheckService;
    }

    /** Przyjęcia z ostatnich {@value LowMarginService#PERIOD_DAYS} dni. */
    @GetMapping
    @PreAuthorize("hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public LowMarginReport report(@PathVariable UUID storeId) {
        return lowMarginService.report(storeId);
    }

    /** Zastępuje listę dostawców, których nie dotyczy marża; zwraca raport po zmianie. */
    @PutMapping("/excluded-suppliers")
    @PreAuthorize("hasAuthority('SALES:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public LowMarginReport excludedSuppliers(@PathVariable UUID storeId,
            @Valid @RequestBody DeliveryReportExcludedSuppliersRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return lowMarginService.setExcludedSuppliers(storeId, request.supplierCodes(), principal.getId());
    }

    /** Nowy status wyjaśniania przyjęcia; zwraca historię zmian od najnowszej. */
    @PostMapping("/documents/{documentId}/reviews")
    @PreAuthorize("hasAuthority('SALES:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public List<PriceCheckReviewResponse> review(@PathVariable UUID storeId, @PathVariable UUID documentId,
            @Valid @RequestBody PriceCheckReviewRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return priceCheckService.review(storeId, ReviewedReport.LOW_MARGIN, documentId, request, principal.getId());
    }
}
