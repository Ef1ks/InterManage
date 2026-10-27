package com.intermanage.backend.document.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
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

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.document.dto.CorrectionCandidate;
import com.intermanage.backend.document.dto.DocumentPage;
import com.intermanage.backend.document.dto.DocumentResponse;
import com.intermanage.backend.document.dto.DocumentsOverview;
import com.intermanage.backend.document.dto.InvoiceComparison;
import com.intermanage.backend.document.dto.KsefInvoiceCandidate;
import com.intermanage.backend.document.dto.LinkDocumentRequest;
import com.intermanage.backend.document.dto.LinkKsefInvoiceRequest;
import com.intermanage.backend.document.dto.RegisterGratisRequest;
import com.intermanage.backend.document.dto.SupplierDocumentNumberRequest;
import com.intermanage.backend.document.repository.DocumentSearch;
import com.intermanage.backend.document.service.DeliveryCorrectionService;
import com.intermanage.backend.document.service.DocumentGratisService;
import com.intermanage.backend.document.service.DocumentKsefLinkService;
import com.intermanage.backend.document.service.DocumentService;
import com.intermanage.backend.document.service.InvoiceAmountCheckService;
import com.intermanage.backend.document.service.InvoiceComparisonService;

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
    private final DeliveryCorrectionService corrections;
    private final InvoiceAmountCheckService amountChecks;
    private final InvoiceComparisonService invoiceComparison;

    public DocumentController(DocumentService documentService, DocumentGratisService gratisService,
            DocumentKsefLinkService ksefLinks, DeliveryCorrectionService corrections,
            InvoiceAmountCheckService amountChecks, InvoiceComparisonService invoiceComparison) {
        this.invoiceComparison = invoiceComparison;
        this.ksefLinks = ksefLinks;
        this.corrections = corrections;
        this.amountChecks = amountChecks;
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
     * sale; {@code direction}: asc / desc; {@code query}: słowa do wyszukania w numerze, dostawcy, nr dok. dostawcy;
     * {@code product}: słowa z nazwy, marki, EAN-u albo ITM8 artykułu w liniach. {@code invoice} (przyjęcia i korekty
     * od dostawcy) i {@code corrections} (przyjęcia): {@code with} / {@code without}.
     */
    @GetMapping("/{type}")
    @PreAuthorize(VIEW)
    public DocumentPage list(@PathVariable UUID storeId, @PathVariable String type,
            @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) String query,
            @RequestParam(required = false) String product, @RequestParam(required = false) String invoice,
            @RequestParam(required = false) String corrections,
            @RequestParam(defaultValue = "number") String sort, @RequestParam(defaultValue = "asc") String direction) {
        return documentService.list(storeId, type, page, query, product, DocumentSearch.Presence.of(invoice),
                DocumentSearch.Presence.of(corrections), sort, direction);
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

    /**
     * Wydruk bonu dostawy przyjęcia PZ (PDF) z wartościami po przypiętych korektach.
     */
    @GetMapping("/{type}/{id}/delivery-note")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> deliveryNote(@PathVariable UUID storeId, @PathVariable String type,
            @PathVariable UUID id, @AuthenticationPrincipal AppUserPrincipal principal) {
        DocumentService.ExportFile file = documentService.deliveryNote(storeId, type, id, principal.getId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    /**
     * Do przyjęcia PZ: jego korekty do przypięcia (do roku po nim); do korekty: przyjęcia, których może dotyczyć
     * (do 120 dni przed nią). Zawsze od tego samego dostawcy, najpierw z największą liczbą wspólnych artykułów.
     */
    @GetMapping("/{type}/{id}/corrections/candidates")
    @PreAuthorize(EDIT)
    public List<CorrectionCandidate> correctionCandidates(@PathVariable UUID storeId, @PathVariable String type,
            @PathVariable UUID id, @RequestParam(required = false) String query) {
        return corrections.candidates(storeId, type, id, query);
    }

    /** Przypina korektę do przyjęcia; {@code documentId} to druga strona (korekta albo przyjęcie). */
    @PostMapping("/{type}/{id}/corrections")
    @PreAuthorize(EDIT)
    public DocumentResponse attachCorrection(@PathVariable UUID storeId, @PathVariable String type,
            @PathVariable UUID id, @Valid @RequestBody LinkDocumentRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        corrections.attach(storeId, type, id, request.documentId(), principal.getId());
        return documentService.document(storeId, type, id);
    }

    /** Odpina korektę od przyjęcia (znacznik); „Przywróć” to ponowne przypięcie. */
    @DeleteMapping("/{type}/{id}/corrections/{documentId}")
    @PreAuthorize(EDIT)
    public DocumentResponse detachCorrection(@PathVariable UUID storeId, @PathVariable String type,
            @PathVariable UUID id, @PathVariable UUID documentId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        corrections.detach(storeId, type, id, documentId, principal.getId());
        return documentService.document(storeId, type, id);
    }

    /**
     * Potwierdza, że różnica między wartością zakupu przyjęcia a sumą netto jego faktur KSeF jest w porządku
     * (dla obecnych kwot - po ich zmianie różnica wraca do sprawdzenia).
     */
    @PutMapping("/{type}/{id}/amount-confirmation")
    @PreAuthorize(EDIT)
    public DocumentResponse confirmAmount(@PathVariable UUID storeId, @PathVariable String type, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        amountChecks.confirm(storeId, type, id, principal.getId());
        return documentService.document(storeId, type, id);
    }

    /**
     * Przyjęcie porównane z XML jego faktur KSeF pozycja po pozycji (po EAN): w którym artykule jest różnica. XML
     * pobiera się z KSeF tylko raz na fakturę.
     */
    @GetMapping("/{type}/{id}/invoice-comparison")
    @PreAuthorize(VIEW + " and hasAuthority('KSEF_INVOICES:VIEW')")
    public InvoiceComparison invoiceComparison(@PathVariable UUID storeId, @PathVariable String type,
            @PathVariable UUID id) {
        return invoiceComparison.compare(storeId, type, id);
    }

    /** Cofa potwierdzenie różnicy kwot (znacznik). */
    @DeleteMapping("/{type}/{id}/amount-confirmation")
    @PreAuthorize(EDIT)
    public DocumentResponse cancelAmountConfirmation(@PathVariable UUID storeId, @PathVariable String type,
            @PathVariable UUID id, @AuthenticationPrincipal AppUserPrincipal principal) {
        amountChecks.cancel(storeId, type, id, principal.getId());
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
