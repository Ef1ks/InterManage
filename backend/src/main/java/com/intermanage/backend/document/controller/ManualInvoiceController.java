package com.intermanage.backend.document.controller;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
