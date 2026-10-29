package com.intermanage.backend.sales.controller;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.sales.dto.GratisReportResponse;
import com.intermanage.backend.sales.service.GratisReportService;
import com.intermanage.backend.sales.service.PeriodSummaryService.ExportFile;

/** Raport przyjętych gratisów za miesiące {@code from}..{@code to} (RRRR-MM) i jego wydruk. */
@RestController
@RequestMapping("/api/stores/{storeId}/reports/gratis")
public class GratisReportController {

    private static final String VIEW =
            "hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)";

    private final GratisReportService reportService;

    public GratisReportController(GratisReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public GratisReportResponse report(@PathVariable UUID storeId, @RequestParam YearMonth from,
            @RequestParam YearMonth to) {
        return reportService.report(storeId, from, to);
    }

    @GetMapping("/pdf")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID storeId, @RequestParam YearMonth from,
            @RequestParam YearMonth to, @AuthenticationPrincipal AppUserPrincipal principal) {
        ExportFile file = reportService.pdf(storeId, from, to, principal.getId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
