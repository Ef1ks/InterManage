package com.intermanage.backend.sales.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
import com.intermanage.backend.sales.dto.PeriodSalesReport;
import com.intermanage.backend.sales.dto.PeriodSalesRequest;
import com.intermanage.backend.sales.service.PeriodSalesService;
import com.intermanage.backend.sales.service.PeriodSummaryService.ExportFile;

/** Sprzedaż za okres po nomenklaturze z filtrami, artykuły podrodziny i wydruk do wybranego poziomu. */
@RestController
@RequestMapping("/api/stores/{storeId}/reports/period-sales")
@PreAuthorize("hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
public class PeriodSalesController {

    private final PeriodSalesService service;

    public PeriodSalesController(PeriodSalesService service) {
        this.service = service;
    }

    @GetMapping
    public PeriodSalesReport report(@PathVariable UUID storeId, @ModelAttribute PeriodSalesRequest request) {
        return service.report(storeId, request);
    }

    /** Artykuły podrodziny {@code parent} ({@code brak} = bez nomenklatury) z tymi samymi filtrami. */
    @GetMapping("/articles")
    public List<PeriodSalesReport.Article> articles(@PathVariable UUID storeId,
            @ModelAttribute PeriodSalesRequest request) {
        return service.articles(storeId, request);
    }

    /** Wydruk PDF: te same filtry, {@code depth} = REGION, FAMILY (domyślnie), SUBFAMILY albo ARTICLE. */
    @GetMapping("/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID storeId, @ModelAttribute PeriodSalesRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        ExportFile file = service.pdf(storeId, request, principal.getId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
