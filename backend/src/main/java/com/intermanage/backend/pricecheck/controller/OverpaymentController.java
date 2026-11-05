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
import com.intermanage.backend.pricecheck.dto.OverpaymentReport;
import com.intermanage.backend.pricecheck.dto.PriceCheckReviewRequest;
import com.intermanage.backend.pricecheck.dto.PriceCheckReviewResponse;
import com.intermanage.backend.pricecheck.entity.ReviewedReport;
import com.intermanage.backend.pricecheck.service.OverpaymentService;
import com.intermanage.backend.pricecheck.service.PriceCheckService;

import jakarta.validation.Valid;

/**
 * Raport przepłaconych artykułów (kafelek w Sprzedaży i raportach). Cofanie i przywracanie statusu idzie przez
 * {@link PriceCheckController}, bo wpis sam wie, którego raportu dotyczy.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/reports/overpayments")
public class OverpaymentController {

    private final OverpaymentService overpaymentService;
    private final PriceCheckService priceCheckService;

    public OverpaymentController(OverpaymentService overpaymentService, PriceCheckService priceCheckService) {
        this.overpaymentService = overpaymentService;
        this.priceCheckService = priceCheckService;
    }

    /** Przyjęcia z ostatnich {@value OverpaymentService#PERIOD_DAYS} dni. */
    @GetMapping
    @PreAuthorize("hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public OverpaymentReport report(@PathVariable UUID storeId) {
        return overpaymentService.report(storeId);
    }

    /** Zastępuje listę dostawców pomijanych w raporcie; zwraca raport po zmianie. */
    @PutMapping("/excluded-suppliers")
    @PreAuthorize("hasAuthority('SALES:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public OverpaymentReport excludedSuppliers(@PathVariable UUID storeId,
            @Valid @RequestBody DeliveryReportExcludedSuppliersRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return overpaymentService.setExcludedSuppliers(storeId, request.supplierCodes(), principal.getId());
    }

    /** Nowy status wyjaśniania przyjęcia; zwraca historię zmian od najnowszej. */
    @PostMapping("/documents/{documentId}/reviews")
    @PreAuthorize("hasAuthority('SALES:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public List<PriceCheckReviewResponse> review(@PathVariable UUID storeId, @PathVariable UUID documentId,
            @Valid @RequestBody PriceCheckReviewRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return priceCheckService.review(storeId, ReviewedReport.OVERPAYMENT, documentId, request, principal.getId());
    }
}
