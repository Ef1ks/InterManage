package com.intermanage.backend.sales.controller;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.nomenclature.entity.NomenclatureLevel;
import com.intermanage.backend.sales.dto.ProtocolReportGrouping;
import com.intermanage.backend.sales.dto.ProtocolReportPrintRequest;
import com.intermanage.backend.sales.dto.ProtocolReportResponse;
import com.intermanage.backend.sales.dto.ProtocolReportsOverview;
import com.intermanage.backend.sales.service.PeriodSummaryService.ExportFile;
import com.intermanage.backend.sales.service.ProtocolReportService;
import com.intermanage.backend.store.entity.ProtocolKind;

import jakarta.validation.Valid;

/** Raport protokołów (kafelek w Sprzedaży i raportach). */
@RestController
@RequestMapping("/api/stores/{storeId}/reports/protocols")
@PreAuthorize("hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
public class ProtocolReportController {

    private final ProtocolReportService protocolReportService;

    public ProtocolReportController(ProtocolReportService protocolReportService) {
        this.protocolReportService = protocolReportService;
    }

    /** Protokoły do wyboru (bez wyłączonych w Ustawieniach sklepu) i do kiedy są wgrane dokumenty. */
    @GetMapping
    public ProtocolReportsOverview overview(@PathVariable UUID storeId) {
        return protocolReportService.overview(storeId);
    }

    /**
     * Protokół za okres {@code from}-{@code to} (oba końce włącznie, wg daty dokumentu) po artykule, marce, ostatnim
     * dostawcy albo nomenklaturze ({@code level}, domyślnie rejon). Z {@code previousFrom} i {@code previousTo}
     * porównanie z tamtym okresem.
     */
    @GetMapping("/{protocol}")
    public ProtocolReportResponse report(@PathVariable UUID storeId, @PathVariable ProtocolKind protocol,
            @RequestParam ProtocolReportGrouping grouping,
            @RequestParam(required = false) NomenclatureLevel level,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate previousFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate previousTo) {
        return protocolReportService.report(storeId, protocol, grouping, level, from, to, previousFrom, previousTo);
    }

    /**
     * Wydruk PDF raportu z wybranymi grupami (np. jedną marką, żeby pokazać dostawcy przeterminowany towar). POST, bo
     * klucze grup bywają puste („Bez marki”) i bywa ich dużo; niczego nie zapisuje.
     */
    @PostMapping("/{protocol}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID storeId, @PathVariable ProtocolKind protocol,
            @Valid @RequestBody ProtocolReportPrintRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        ExportFile file = protocolReportService.pdf(storeId, protocol, request, principal.getId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(file.content());
    }
}
