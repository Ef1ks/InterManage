package com.intermanage.backend.sales.controller;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.sales.dto.NegativeStockReport;
import com.intermanage.backend.sales.dto.NegativeStockRequest;
import com.intermanage.backend.sales.service.NegativeStockService;

/** Raport ujemnych stanów. */
@RestController
@RequestMapping("/api/stores/{storeId}/reports/negative-stock")
public class NegativeStockController {

    private final NegativeStockService reportService;

    public NegativeStockController(NegativeStockService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public NegativeStockReport report(@PathVariable UUID storeId, @ModelAttribute NegativeStockRequest request) {
        return reportService.report(storeId, request);
    }
}
