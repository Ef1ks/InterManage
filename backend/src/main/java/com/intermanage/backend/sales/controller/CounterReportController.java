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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.sales.dto.CounterReport;
import com.intermanage.backend.sales.service.CounterReportService;
import com.intermanage.backend.sales.service.PeriodSummaryService;

/** Raport lady: sprzedaż rejonów 24, 36 i 46 w kilku sklepach naraz, z wydrukiem. */
@RestController
@RequestMapping("/api/stores/{storeId}/reports/counter")
@PreAuthorize("hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
public class CounterReportController {

    private final CounterReportService service;

    public CounterReportController(CounterReportService service) {
        this.service = service;
    }

    /** Sklepy do porównania: dostępne użytkownikowi i zaprzyjaźnione z bieżącym. */
    @GetMapping("/stores")
    public List<CounterReport.Option> stores(@PathVariable UUID storeId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.options(storeId, principal.getId());
    }

    /**
     * Raport za okres; {@code regions} (powtarzane, puste = wszystkie) to rejony lady, {@code stores} sklepy
     * porównywane z bieżącym.
     */
    @GetMapping
    public CounterReport report(@PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<String> regions,
            @RequestParam(required = false) List<UUID> stores,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.report(storeId, principal.getId(), from, to, regions, stores);
    }

    /** Wydruk (PDF): {@code metrics} powtarzane w kolejności kolumn, {@code articles} dokłada artykuły. */
    @GetMapping("/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<String> regions,
            @RequestParam(required = false) List<UUID> stores,
            @RequestParam(required = false) List<CounterReport.Metric> metrics,
            @RequestParam(defaultValue = "GROSS") CounterReport.Basis basis,
            @RequestParam(defaultValue = "false") boolean articles,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        PeriodSummaryService.ExportFile file = service.pdf(storeId, principal.getId(), from, to, regions, stores,
                metrics, basis, articles);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
