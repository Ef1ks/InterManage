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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.dataentry.component.OrderFile;
import com.intermanage.backend.dataentry.dto.OrderFileRequest;
import com.intermanage.backend.dataentry.service.DataEntryProtocolService;
import com.intermanage.backend.sales.dto.ZeroStockReport;
import com.intermanage.backend.sales.service.PeriodSummaryService.ExportFile;
import com.intermanage.backend.sales.service.ZeroStockService;

import jakarta.validation.Valid;

/**
 * Raport zerówek dla wybranych pozycji nomenklatury, jego wydruk z kodami kreskowymi i plik zamówienia COMMANDE
 * z artykułów zaznaczonych w oknie. Plik jest tu też z {@code SALES:VIEW}, bez uprawnienia do wprowadzania danych.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/reports/zero-stock")
public class ZeroStockController {

    private static final String VIEW =
            "hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)";

    private final ZeroStockService reportService;
    private final DataEntryProtocolService protocolService;

    public ZeroStockController(ZeroStockService reportService, DataEntryProtocolService protocolService) {
        this.reportService = reportService;
        this.protocolService = protocolService;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public ZeroStockReport report(@PathVariable UUID storeId,
            @RequestParam(defaultValue = "") List<String> nomenclatures) {
        return reportService.report(storeId, nomenclatures);
    }

    @GetMapping("/pdf")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID storeId,
            @RequestParam(defaultValue = "") List<String> nomenclatures,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        ExportFile file = reportService.pdf(storeId, nomenclatures, principal.getId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    @PostMapping("/order")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> order(@PathVariable UUID storeId, @Valid @RequestBody OrderFileRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        DataEntryProtocolService.ProtocolFile file = protocolService.order(request, principal.getUsername());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(OrderFile.CONTENT_TYPE))
                .body(file.content());
    }
}
