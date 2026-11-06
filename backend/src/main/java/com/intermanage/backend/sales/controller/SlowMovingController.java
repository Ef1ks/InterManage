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
import com.intermanage.backend.sales.dto.SlowMovingReport;
import com.intermanage.backend.sales.dto.SlowMovingRequest;
import com.intermanage.backend.sales.service.PeriodSummaryService.ExportFile;
import com.intermanage.backend.sales.service.SlowMovingService;

/** Raport niesprzedających się artykułów i jego wydruk. */
@RestController
@RequestMapping("/api/stores/{storeId}/reports/slow-moving")
public class SlowMovingController {

    private static final String VIEW =
            "hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)";

    private final SlowMovingService reportService;

    public SlowMovingController(SlowMovingService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public SlowMovingReport report(@PathVariable UUID storeId, @ModelAttribute SlowMovingRequest request) {
        return reportService.report(storeId, request);
    }

    @GetMapping("/pdf")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID storeId, @ModelAttribute SlowMovingRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        ExportFile file = reportService.pdf(storeId, request, principal.getId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
