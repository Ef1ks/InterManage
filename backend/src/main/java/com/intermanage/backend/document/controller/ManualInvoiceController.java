package com.intermanage.backend.document.controller;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.document.dto.DocumentResponse;
import com.intermanage.backend.document.dto.ManualInvoiceRequest;
import com.intermanage.backend.document.service.DocumentManualInvoiceService;
import com.intermanage.backend.document.service.DocumentService;

import jakarta.validation.Valid;

/**
 * Faktury spoza KSeF przy przyjęciach i ich korektach: dostawca z listy dostawców i numer wpisany ręcznie. Każda
 * zmiana zwraca cały dokument.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/documents/{type}/{id}/manual-invoices")
public class ManualInvoiceController {

    private static final String VIEW =
            "hasAuthority('DOCUMENTS:VIEW') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT =
            "hasAuthority('DOCUMENTS:EDIT') and @storeAccess.canAccess(authentication, #storeId)";

    private final DocumentManualInvoiceService manualInvoices;
    private final DocumentService documentService;

    public ManualInvoiceController(DocumentManualInvoiceService manualInvoices, DocumentService documentService) {
        this.manualInvoices = manualInvoices;
        this.documentService = documentService;
    }

    @PostMapping
    @PreAuthorize(EDIT)
    public DocumentResponse add(@PathVariable UUID storeId, @PathVariable String type, @PathVariable UUID id,
            @Valid @RequestBody ManualInvoiceRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        manualInvoices.add(storeId, type, id, request, principal.getId());
        return documentService.document(storeId, type, id);
    }

    /** Usuwa fakturę (znacznik); „Przywróć” to {@code POST …/{invoiceId}/restore}. */
    @DeleteMapping("/{invoiceId}")
    @PreAuthorize(EDIT)
    public DocumentResponse remove(@PathVariable UUID storeId, @PathVariable String type, @PathVariable UUID id,
            @PathVariable UUID invoiceId, @AuthenticationPrincipal AppUserPrincipal principal) {
        manualInvoices.remove(storeId, type, id, invoiceId, principal.getId());
        return documentService.document(storeId, type, id);
    }

    @PostMapping("/{invoiceId}/restore")
    @PreAuthorize(EDIT)
    public DocumentResponse restore(@PathVariable UUID storeId, @PathVariable String type, @PathVariable UUID id,
            @PathVariable UUID invoiceId) {
        manualInvoices.restore(storeId, type, id, invoiceId);
        return documentService.document(storeId, type, id);
    }

    /** Dołącza plik faktury (multipart {@code file}: PDF albo JPG, PNG); poprzedni dostaje znacznik. */
    @PutMapping(path = "/{invoiceId}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(EDIT)
    public DocumentResponse attachFile(@PathVariable UUID storeId, @PathVariable String type, @PathVariable UUID id,
            @PathVariable UUID invoiceId, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        manualInvoices.attachFile(storeId, type, id, invoiceId, file, principal.getId());
        return documentService.document(storeId, type, id);
    }

    /** Plik faktury do podglądu, pobrania i wydruku. */
    @GetMapping("/{invoiceId}/file")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> file(@PathVariable UUID storeId, @PathVariable String type, @PathVariable UUID id,
            @PathVariable UUID invoiceId) {
        DocumentService.ExportFile file = manualInvoices.file(storeId, type, id, invoiceId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    /** Usuwa plik (znacznik); „Przywróć” to {@code POST …/file/restore}. */
    @DeleteMapping("/{invoiceId}/file")
    @PreAuthorize(EDIT)
    public DocumentResponse removeFile(@PathVariable UUID storeId, @PathVariable String type, @PathVariable UUID id,
            @PathVariable UUID invoiceId, @AuthenticationPrincipal AppUserPrincipal principal) {
        manualInvoices.removeFile(storeId, type, id, invoiceId, principal.getId());
        return documentService.document(storeId, type, id);
    }

    @PostMapping("/{invoiceId}/file/restore")
    @PreAuthorize(EDIT)
    public DocumentResponse restoreFile(@PathVariable UUID storeId, @PathVariable String type, @PathVariable UUID id,
            @PathVariable UUID invoiceId) {
        manualInvoices.restoreFile(storeId, type, id, invoiceId);
        return documentService.document(storeId, type, id);
    }
}
