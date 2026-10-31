package com.intermanage.backend.dataentry.controller;

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
import com.intermanage.backend.dataentry.dto.CatalogProduct;
import com.intermanage.backend.dataentry.dto.DataEntryInvoice;
import com.intermanage.backend.dataentry.dto.ExportRequest;
import com.intermanage.backend.dataentry.dto.NomenclatureItem;
import com.intermanage.backend.dataentry.dto.SuggestionRequest;
import com.intermanage.backend.dataentry.dto.SuggestionResponse;
import com.intermanage.backend.dataentry.service.DataEntryService;
import com.intermanage.backend.dataentry.service.DataEntrySuggestionService;

import jakarta.validation.Valid;

/**
 * Wprowadzanie danych: faktura z KSeF w tabeli (z kartoteką sklepu), artykuły po EAN-ie, nomenklatura, propozycje AI
 * dla nowych artykułów (EDIT, bo każde wywołanie modelu kosztuje) i eksport tabeli do Excela.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/data-entry")
public class DataEntryController {

    private static final String VIEW =
            "hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT =
            "hasAuthority('DATA_ENTRY:EDIT') and @storeAccess.canAccess(authentication, #storeId)";

    private final DataEntryService service;
    private final DataEntrySuggestionService suggestionService;

    public DataEntryController(DataEntryService service, DataEntrySuggestionService suggestionService) {
        this.service = service;
        this.suggestionService = suggestionService;
    }

    /** Pozycje faktury z KSeF z artykułami z kartoteki; faktury widzi tylko osoba z dostępem do faktur KSeF. */
    @GetMapping("/invoices/{invoiceId}")
    @PreAuthorize(VIEW + " and hasAuthority('KSEF_INVOICES:VIEW')")
    public DataEntryInvoice invoice(@PathVariable UUID storeId, @PathVariable UUID invoiceId) {
        return service.invoice(storeId, invoiceId);
    }

    /** Artykuły z kartoteki dla wpisanych EAN-ów ({@code ?ean=…&ean=…}). */
    @GetMapping("/products")
    @PreAuthorize(VIEW)
    public List<CatalogProduct> products(@PathVariable UUID storeId, @RequestParam List<String> ean) {
        return service.lookup(storeId, ean);
    }

    @GetMapping("/nomenclatures")
    @PreAuthorize(VIEW)
    public List<NomenclatureItem> nomenclatures(@PathVariable UUID storeId) {
        return service.nomenclature(storeId);
    }

    @PostMapping("/suggestions")
    @PreAuthorize(EDIT)
    public SuggestionResponse suggestions(@PathVariable UUID storeId, @Valid @RequestBody SuggestionRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return suggestionService.suggest(storeId, request, principal.getId());
    }

    @PostMapping("/export/xlsx")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> export(@PathVariable UUID storeId, @Valid @RequestBody ExportRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        DataEntryService.ExportFile file = service.export(storeId, request, principal.getId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file.content());
    }
}
