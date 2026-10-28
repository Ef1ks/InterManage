package com.intermanage.backend.sales.controller;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.sales.dto.PeriodSummaryResponse;
import com.intermanage.backend.sales.dto.RegionBudgetResponse;
import com.intermanage.backend.sales.dto.RegionBudgetsRequest;
import com.intermanage.backend.sales.service.PeriodSummaryService;

import jakarta.validation.Valid;

/**
 * Podsumowanie sprzedaży i strat za okres (raport z wydrukiem) i budżet rejonów w ustawieniach sklepu: docelowa
 * marża i najwyższe straty.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/reports")
public class PeriodSummaryController {

    private static final String VIEW =
            "hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT =
            "hasAuthority('SALES:EDIT') and @storeAccess.canAccess(authentication, #storeId)";

    private final PeriodSummaryService summaryService;

    public PeriodSummaryController(PeriodSummaryService summaryService) {
        this.summaryService = summaryService;
    }

    @GetMapping("/period-summary")
    @PreAuthorize(VIEW)
    public PeriodSummaryResponse summary(@PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return summaryService.summary(storeId, from, to);
    }

    /** Wydruk podsumowania (PDF). */
    @GetMapping("/period-summary/pdf")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> summaryPdf(@PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        PeriodSummaryService.ExportFile file = summaryService.pdf(storeId, from, to, principal.getId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    @GetMapping("/region-budgets")
    @PreAuthorize(VIEW)
    public List<RegionBudgetResponse> budgets(@PathVariable UUID storeId) {
        return summaryService.budgets(storeId);
    }

    /** Zapisuje budżety podanych rejonów; zwraca wszystkie. */
    @PutMapping("/region-budgets")
    @PreAuthorize(EDIT)
    public List<RegionBudgetResponse> saveBudgets(@PathVariable UUID storeId,
            @Valid @RequestBody RegionBudgetsRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return summaryService.saveBudgets(storeId, request, principal.getId());
    }
}
