package com.intermanage.backend.sales.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.sales.dto.SalesImportOverview;
import com.intermanage.backend.sales.dto.SalesImportResponse;
import com.intermanage.backend.sales.service.SalesImportService;

/** Raport sprzedaży wg artykułu i dnia wgrywany w narzędziu Import danych. */
@RestController
@RequestMapping("/api/stores/{storeId}/imports/sales")
public class SalesImportController {

    private final SalesImportService salesImportService;

    public SalesImportController(SalesImportService salesImportService) {
        this.salesImportService = salesImportService;
    }

    /** Ile wierszy sprzedaży ma sklep, za jakie dni, i ostatnie wgrania. */
    @GetMapping
    @PreAuthorize("hasAuthority('IMPORTS:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public SalesImportOverview imports(@PathVariable UUID storeId) {
        return salesImportService.imports(storeId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('IMPORTS:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public SalesImportResponse importReport(@PathVariable UUID storeId, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return salesImportService.importReport(storeId, file, principal.getId());
    }
}
