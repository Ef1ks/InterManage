package com.intermanage.backend.sales.controller;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.sales.dto.StockGapReport;
import com.intermanage.backend.sales.dto.StockGapRequest;
import com.intermanage.backend.sales.service.StockGapService;

/** Raport braków towaru. Plik zamówienia idzie przez ten sam endpoint co w raporcie zerówek. */
@RestController
@RequestMapping("/api/stores/{storeId}/reports/stock-gaps")
public class StockGapController {

    private final StockGapService reportService;

    public StockGapController(StockGapService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public StockGapReport report(@PathVariable UUID storeId, @ModelAttribute StockGapRequest request) {
        return reportService.report(storeId, request);
    }
}
