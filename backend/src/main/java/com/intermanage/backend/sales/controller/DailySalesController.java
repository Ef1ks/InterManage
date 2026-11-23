package com.intermanage.backend.sales.controller;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.sales.dto.DailySalesReport;
import com.intermanage.backend.sales.dto.DailySalesRequest;
import com.intermanage.backend.sales.service.DailySalesService;
import com.intermanage.backend.sales.service.PeriodSummaryService.ExportFile;

/** Sprzedaż dzień po dniu po nomenklaturze i jej wydruk. */
@RestController
@RequestMapping("/api/stores/{storeId}/reports/daily-sales")
@PreAuthorize("hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
public class DailySalesController {

    private final DailySalesService service;

    public DailySalesController(DailySalesService service) {
        this.service = service;
    }

    @GetMapping
    public DailySalesReport report(@PathVariable UUID storeId, @ModelAttribute DailySalesRequest request) {
        return service.report(storeId, request);
    }

    /** Wydruk PDF: {@code level} REGION|FAMILY, {@code measure}, {@code layout} DAYS_AS_ROWS|DAYS_AS_COLUMNS. */
    @GetMapping("/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID storeId, @ModelAttribute DailySalesRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        ExportFile file = service.pdf(storeId, request, principal.getId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
