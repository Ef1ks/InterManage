package com.intermanage.backend.sales.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.sales.dto.SlowMovingExcludeRequest;
import com.intermanage.backend.sales.dto.SlowMovingExcludedItem;
import com.intermanage.backend.sales.dto.SlowMovingReport;
import com.intermanage.backend.sales.dto.SlowMovingRequest;
import com.intermanage.backend.sales.service.PeriodSummaryService.ExportFile;
import com.intermanage.backend.sales.service.SlowMovingExclusionService;
import com.intermanage.backend.sales.service.SlowMovingService;

import jakarta.validation.Valid;

/** Raport niesprzedających się artykułów, jego wydruk i lista artykułów pomijanych. */
@RestController
@RequestMapping("/api/stores/{storeId}/reports/slow-moving")
public class SlowMovingController {

    private static final String VIEW =
            "hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT =
            "hasAuthority('SALES:EDIT') and @storeAccess.canAccess(authentication, #storeId)";

    private final SlowMovingService reportService;
    private final SlowMovingExclusionService exclusions;

    public SlowMovingController(SlowMovingService reportService, SlowMovingExclusionService exclusions) {
        this.reportService = reportService;
        this.exclusions = exclusions;
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

    @GetMapping("/excluded")
    @PreAuthorize(VIEW)
    public List<SlowMovingExcludedItem> excluded(@PathVariable UUID storeId) {
        return exclusions.list(storeId);
    }

    @PostMapping("/excluded")
    @PreAuthorize(EDIT)
    public SlowMovingExcludedItem exclude(@PathVariable UUID storeId,
            @Valid @RequestBody SlowMovingExcludeRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return exclusions.exclude(storeId, request.itm8(), principal.getId());
    }

    @DeleteMapping("/excluded/{id}")
    @PreAuthorize(EDIT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeExcluded(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        exclusions.remove(storeId, id, principal.getId());
    }
}
