package com.intermanage.backend.controller;

import java.util.List;
import java.util.UUID;

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

import com.intermanage.backend.dto.DocumentPage;
import com.intermanage.backend.dto.DocumentResponse;
import com.intermanage.backend.dto.DocumentsOverview;
import com.intermanage.backend.dto.KsefInvoiceCandidate;
import com.intermanage.backend.dto.LinkKsefInvoiceRequest;
import com.intermanage.backend.dto.RegisterGratisRequest;
import com.intermanage.backend.dto.SupplierDocumentNumberRequest;
import com.intermanage.backend.security.AppUserPrincipal;
import com.intermanage.backend.service.DocumentGratisService;
import com.intermanage.backend.service.DocumentKsefLinkService;
import com.intermanage.backend.service.DocumentService;

import jakarta.validation.Valid;

/**
 * Dokumenty magazynowe sklepu do przeglądania, po typach (przyjęcia, likwidacje, zwroty...). {@code type} w ścieżce
 * to kod z systemu sklepu (np. {@code pz}). Raport, z którego pochodzą, wgrywa się w Imporcie danych
 * ({@link DocumentImportController}).
 */
@RestController
@RequestMapping("/api/stores/{storeId}/documents")
public class DocumentController {

    private static final String VIEW =
            "hasAuthority('DOCUMENTS:VIEW') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT =
            "hasAuthority('DOCUMENTS:EDIT') and @storeAccess.canAccess(authentication, #storeId)";

    private final DocumentService documentService;
    private final DocumentGratisService gratisService;
    private final DocumentKsefLinkService ksefLinks;

    public DocumentController(DocumentService documentService, DocumentGratisService gratisService,
            DocumentKsefLinkService ksefLinks) {
        this.ksefLinks = ksefLinks;
        this.documentService = documentService;
        this.gratisService = gratisService;
    }

    /** Kafelki typów z liczbą dokumentów i data najnowszego wgranego raportu. */
    @GetMapping
    @PreAuthorize(VIEW)
    public DocumentsOverview overview(@PathVariable UUID storeId) {
        return documentService.overview(storeId);
    }

    /**
     * Strona listy dokumentów typu (po 100). {@code sort}: number, date, supplier, supplierDocument, lines, purchase,
     * sale; {@code direction}: asc / desc; {@code query}: słowa do wyszukania w numerze, dostawcy, nr dok. dostawcy.
     */
    @GetMapping("/{type}")
    @PreAuthorize(VIEW)
    public DocumentPage list(@PathVariable UUID storeId, @PathVariable String type,
            @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "number") String sort, @RequestParam(defaultValue = "asc") String direction) {
        return documentService.list(storeId, type, page, query, sort, direction);
    }

    @GetMapping("/{type}/{id}")
    @PreAuthorize(VIEW)
    public DocumentResponse document(@PathVariable UUID storeId, @PathVariable String type, @PathVariable UUID id) {
        return documentService.document(storeId, type, id);
    }

    /**
     * Faktury KSeF do przypięcia przyjęcia albo korekty od dostawcy: domyślnie kwota netto ±10% wartości zakupu,
     * najpierw ten sam dostawca i najbliższa kwota; {@code query} szuka po numerze faktury, dostawcy, NIP-ie.
     */
    @GetMapping("/{type}/{id}/ksef-invoices/candidates")
    @PreAuthorize(EDIT + " and hasAuthority('KSEF_INVOICES:VIEW')")
    public List<KsefInvoiceCandidate> ksefInvoiceCandidates(@PathVariable UUID storeId, @PathVariable String type,
            @PathVariable UUID id, @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "true") boolean amountRange) {
        return ksefLinks.invoiceCandidates(storeId, type, id, query, amountRange);
    }

    /** Przypina fakturę KSeF do dokumentu - faktura staje się wprowadzona. */
    @PostMapping("/{type}/{id}/ksef-invoices")
    @PreAuthorize(EDIT + " and hasAuthority('KSEF_INVOICES:VIEW')")
    public DocumentResponse linkKsefInvoice(@PathVariable UUID storeId, @PathVariable String type,
            @PathVariable UUID id, @Valid @RequestBody LinkKsefInvoiceRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        ksefLinks.linkableDocument(storeId, type, id);
        ksefLinks.link(storeId, id, request.invoiceId(), principal.getId());
        return documentService.document(storeId, type, id);
    }

    /** Odpina fakturę (znacznik); „Przywróć” to ponowne przypięcie. */
    @DeleteMapping("/{type}/{id}/ksef-invoices/{invoiceId}")
    @PreAuthorize(EDIT)
    public DocumentResponse unlinkKsefInvoice(@PathVariable UUID storeId, @PathVariable String type,
            @PathVariable UUID id, @PathVariable UUID invoiceId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        ksefLinks.linkableDocument(storeId, type, id);
        ksefLinks.unlink(storeId, id, invoiceId, principal.getId());
        return documentService.document(storeId, type, id);
    }

    /** Poprawia numer dokumentu dostawcy w przyjęciu PZ (import go nie nadpisze). */
    @PutMapping("/{type}/{id}/supplier-document-number")
    @PreAuthorize(EDIT)
    public DocumentResponse editSupplierDocumentNumber(@PathVariable UUID storeId, @PathVariable String type,
            @PathVariable UUID id, @Valid @RequestBody SupplierDocumentNumberRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return documentService.editSupplierDocumentNumber(storeId, type, id, request.supplierDocumentNumber(),
                principal.getId());
    }

    /** Cofa poprawkę numeru: znowu obowiązuje numer z raportu. */
    @DeleteMapping("/{type}/{id}/supplier-document-number")
    @PreAuthorize(EDIT)
    public DocumentResponse restoreSupplierDocumentNumber(@PathVariable UUID storeId, @PathVariable String type,
            @PathVariable UUID id) {
        return documentService.restoreSupplierDocumentNumber(storeId, type, id);
    }

    /** Rejestruje przeniesienie stanów jako gratis od dostawcy albo zmienia dostawcę. */
    @PutMapping("/{type}/{id}/gratis")
    @PreAuthorize(EDIT)
    public DocumentResponse registerGratis(@PathVariable UUID storeId, @PathVariable String type, @PathVariable UUID id,
            @Valid @RequestBody RegisterGratisRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return gratisService.register(storeId, type, id, request.supplierId(), principal.getId());
    }

    /** Cofa rejestrację (znacznik, do przywrócenia). */
    @DeleteMapping("/{type}/{id}/gratis")
    @PreAuthorize(EDIT)
    public DocumentResponse cancelGratis(@PathVariable UUID storeId, @PathVariable String type, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return gratisService.cancel(storeId, type, id, principal.getId());
    }

    @PostMapping("/{type}/{id}/gratis/restore")
    @PreAuthorize(EDIT)
    public DocumentResponse restoreGratis(@PathVariable UUID storeId, @PathVariable String type, @PathVariable UUID id) {
        return gratisService.restore(storeId, type, id);
    }
}
