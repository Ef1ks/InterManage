package com.intermanage.backend.promotion.controller;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.promotion.dto.PromotionReport;
import com.intermanage.backend.promotion.service.PromotionReportService;

/** Skuteczność promocji (kafelek w Sprzedaży i raportach). */
@RestController
@RequestMapping("/api/stores/{storeId}/reports/promotions")
@PreAuthorize("hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
public class PromotionReportController {

    private final PromotionReportService promotionReportService;

    public PromotionReportController(PromotionReportService promotionReportService) {
        this.promotionReportService = promotionReportService;
    }

    /** Promocje kończące się w okresie {@code from}-{@code to} (oba końce włącznie, najwyżej 92 dni). */
    @GetMapping
    public PromotionReport report(@PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return promotionReportService.report(storeId, from, to);
    }
}
