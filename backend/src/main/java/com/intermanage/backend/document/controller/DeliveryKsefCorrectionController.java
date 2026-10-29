package com.intermanage.backend.document.controller;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.document.dto.DeliveryKsefCorrections;
import com.intermanage.backend.document.dto.LinkDocumentRequest;
import com.intermanage.backend.document.service.DeliveryKsefCorrectionService;

import jakarta.validation.Valid;

/**
 * Korekty z KSeF faktury przyjęcia PZ i przypinanie ich do korekt od dostawcy (WZ) razem z WZ do przyjęcia
 * ({@link DeliveryKsefCorrectionService}).
 */
@RestController
@RequestMapping("/api/stores/{storeId}/documents/pz/{id}/ksef-corrections")
public class DeliveryKsefCorrectionController {

    private static final String VIEW = "hasAuthority('DOCUMENTS:VIEW') and hasAuthority('KSEF_INVOICES:VIEW')"
            + " and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT = "hasAuthority('DOCUMENTS:EDIT') and hasAuthority('KSEF_INVOICES:VIEW')"
            + " and @storeAccess.canAccess(authentication, #storeId)";

    private final DeliveryKsefCorrectionService service;

    public DeliveryKsefCorrectionController(DeliveryKsefCorrectionService service) {
        this.service = service;
    }

    public record LinkResult(boolean attached) {
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public DeliveryKsefCorrections corrections(@PathVariable UUID storeId, @PathVariable UUID id) {
        return service.of(storeId, id);
    }

    /** {@code attached}: WZ została teraz przypięta do przyjęcia (cofnięcie podaje wtedy {@code detach=true}). */
    @PostMapping("/{invoiceId}/document")
    @PreAuthorize(EDIT)
    public LinkResult link(@PathVariable UUID storeId, @PathVariable UUID id, @PathVariable UUID invoiceId,
            @Valid @RequestBody LinkDocumentRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return new LinkResult(service.link(storeId, id, invoiceId, request.documentId(), principal.getId()));
    }

    @DeleteMapping("/{invoiceId}/document/{documentId}")
    @PreAuthorize(EDIT)
    public void unlink(@PathVariable UUID storeId, @PathVariable UUID id, @PathVariable UUID invoiceId,
            @PathVariable UUID documentId, @RequestParam(defaultValue = "false") boolean detach,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        service.unlink(storeId, id, invoiceId, documentId, detach, principal.getId());
    }
}
