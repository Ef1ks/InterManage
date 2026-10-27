package com.intermanage.backend.ksef.controller;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.document.dto.DocumentCandidate;
import com.intermanage.backend.document.dto.LinkDocumentRequest;
import com.intermanage.backend.document.service.DocumentKsefLinkService;
import com.intermanage.backend.document.service.DocumentService;
import com.intermanage.backend.ksef.dto.KsefInternalIdRequest;
import com.intermanage.backend.ksef.dto.KsefInvoicePage;
import com.intermanage.backend.ksef.dto.KsefInvoiceSummary;
import com.intermanage.backend.ksef.dto.KsefLinkConnectionRequest;
import com.intermanage.backend.ksef.dto.KsefOverview;
import com.intermanage.backend.ksef.dto.KsefSettingsResponse;
import com.intermanage.backend.ksef.dto.KsefShareConnectionRequest;
import com.intermanage.backend.ksef.dto.KsefSkippedSupplierRequest;
import com.intermanage.backend.ksef.repository.KsefInvoiceSearch;
import com.intermanage.backend.ksef.service.KsefConnectionService;
import com.intermanage.backend.ksef.service.KsefInvoiceService;
import com.intermanage.backend.ksef.service.KsefSyncService;

import jakarta.validation.Valid;

/**
 * Faktury zakupowe z KSeF sklepu: lista z filtrami i oznaczaniem "wprowadzona" (VIEW / EDIT) oraz ustawienia
 * połączenia, IDWew i pomijanych dostawców (EDIT).
 */
@RestController
@RequestMapping("/api/stores/{storeId}/ksef")
public class KsefController {

    private static final String VIEW =
            "hasAuthority('KSEF_INVOICES:VIEW') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT =
            "hasAuthority('KSEF_INVOICES:EDIT') and @storeAccess.canAccess(authentication, #storeId)";

    private final KsefInvoiceService invoiceService;
    private final KsefConnectionService connectionService;
    private final KsefSyncService syncService;
    private final DocumentKsefLinkService documentLinks;
    private final DocumentService documentService;

    public KsefController(KsefInvoiceService invoiceService, KsefConnectionService connectionService,
            KsefSyncService syncService, DocumentKsefLinkService documentLinks, DocumentService documentService) {
        this.documentService = documentService;
        this.invoiceService = invoiceService;
        this.connectionService = connectionService;
        this.syncService = syncService;
        this.documentLinks = documentLinks;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public KsefOverview overview(@PathVariable UUID storeId) {
        return invoiceService.overview(storeId);
    }

    /**
     * Strona faktur (po 100). {@code entered}: ALL / ENTERED / NOT_ENTERED, {@code kind}: ALL / INVOICES /
     * CORRECTIONS, {@code internalId}: IDWew sklepu, OWN (tylko z IDWew sklepu) albo NONE (bez IDWew), {@code sort}:
     * issueDate, number, seller, net, gross.
     */
    @GetMapping("/invoices")
    @PreAuthorize(VIEW)
    public KsefInvoicePage invoices(@PathVariable UUID storeId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "ALL") KsefInvoiceSearch.Entered entered,
            @RequestParam(defaultValue = "ALL") KsefInvoiceSearch.Kind kind,
            @RequestParam(required = false) String internalId,
            @RequestParam(defaultValue = "false") boolean showSkipped,
            @RequestParam(defaultValue = "issueDate") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return invoiceService.list(storeId, page,
                new KsefInvoiceSearch.Filter(query, from, to, entered, kind, internalId, showSkipped), sort, direction);
    }

    @PutMapping("/invoices/{id}/entered")
    @PreAuthorize(EDIT)
    public KsefInvoiceSummary markEntered(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return invoiceService.setEntered(storeId, id, true, principal.getId());
    }

    @DeleteMapping("/invoices/{id}/entered")
    @PreAuthorize(EDIT)
    public KsefInvoiceSummary unmarkEntered(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return invoiceService.setEntered(storeId, id, false, principal.getId());
    }

    /**
     * Przyjęcia i korekty od dostawcy do przypięcia faktury: domyślnie wartość zakupu ±10% kwoty netto faktury,
     * najpierw ten sam dostawca i najbliższa kwota; {@code query} szuka po numerze, dostawcy, nr dok. dostawcy.
     */
    @GetMapping("/invoices/{id}/documents/candidates")
    @PreAuthorize(EDIT + " and hasAuthority('DOCUMENTS:VIEW')")
    public List<DocumentCandidate> documentCandidates(@PathVariable UUID storeId, @PathVariable UUID id,
            @RequestParam(required = false) String query, @RequestParam(defaultValue = "true") boolean amountRange) {
        return documentLinks.documentCandidates(storeId, id, query, amountRange);
    }

    /** Przypina dokument sklepu do faktury - faktura staje się wprowadzona. */
    @PostMapping("/invoices/{id}/documents")
    @PreAuthorize(EDIT + " and hasAuthority('DOCUMENTS:VIEW')")
    public KsefInvoiceSummary linkDocument(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody LinkDocumentRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        documentLinks.link(storeId, request.documentId(), id, principal.getId());
        return invoiceService.summary(storeId, id);
    }

    /** Odpina dokument (znacznik); „Przywróć” to ponowne przypięcie. */
    @DeleteMapping("/invoices/{id}/documents/{documentId}")
    @PreAuthorize(EDIT)
    public KsefInvoiceSummary unlinkDocument(@PathVariable UUID storeId, @PathVariable UUID id,
            @PathVariable UUID documentId, @AuthenticationPrincipal AppUserPrincipal principal) {
        documentLinks.unlink(storeId, documentId, id, principal.getId());
        return invoiceService.summary(storeId, id);
    }

    @GetMapping("/invoices/{id}/xml")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> xml(@PathVariable UUID storeId, @PathVariable UUID id) {
        return download(invoiceService.xml(storeId, id));
    }

    /**
     * Bon dostawy przyjęcia PZ z tą fakturą - także z innego sklepu tej samej firmy (wspólne połączenie KSeF), np.
     * gdy faktura została wprowadzona w drugim sklepie.
     */
    @GetMapping("/invoices/{id}/documents/{documentId}/delivery-note")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> deliveryNote(@PathVariable UUID storeId, @PathVariable UUID id,
            @PathVariable UUID documentId, @AuthenticationPrincipal AppUserPrincipal principal) {
        UUID deliveryStore = invoiceService.companyDeliveryStore(storeId, id, documentId);
        DocumentService.ExportFile file = documentService.deliveryNote(deliveryStore, "pz", documentId,
                principal.getId());
        return download(new KsefInvoiceService.ExportFile(file.fileName(), file.contentType(), file.content()));
    }

    /** Faktura jako PDF do czytania i wydruku, zrobiona z XML z KSeF. */
    @GetMapping("/invoices/{id}/pdf")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID storeId, @PathVariable UUID id) {
        return download(invoiceService.pdf(storeId, id));
    }

    private static ResponseEntity<byte[]> download(KsefInvoiceService.ExportFile file) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    /**
     * Zaczyna pobieranie nowych faktur z KSeF w tle (poza tym co pół godziny samo) i od razu zwraca nagłówek
     * z postępem ({@code syncRunning}); wynik pojawia się w nagłówku po zakończeniu.
     */
    @PostMapping("/sync")
    @PreAuthorize(EDIT)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KsefOverview sync(@PathVariable UUID storeId) {
        syncService.start(storeId);
        return invoiceService.overview(storeId);
    }

    @GetMapping("/settings")
    @PreAuthorize(EDIT)
    public KsefSettingsResponse settings(@PathVariable UUID storeId) {
        return connectionService.settings(storeId);
    }

    /**
     * Połączenie (multipart): {@code nip}, {@code certificate} (.crt), {@code privateKey} (.key), {@code password}.
     * Pliki i hasło można pominąć, gdy zmienia się tylko NIP. Po zapisie od razu test logowania.
     */
    @PutMapping(path = "/settings/connection", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(EDIT)
    public KsefSettingsResponse saveConnection(@PathVariable UUID storeId, @RequestParam String nip,
            @RequestPart(required = false) MultipartFile certificate,
            @RequestPart(required = false) MultipartFile privateKey,
            @RequestParam(required = false) String password,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return connectionService.saveConnection(storeId, nip, certificate, privateKey, password, principal.getId());
    }

    /** Podłączenie do firmy połączonej już w innym sklepie użytkownika, bez certyfikatu. */
    @PostMapping("/settings/connection/link")
    @PreAuthorize(EDIT)
    public KsefSettingsResponse linkConnection(@PathVariable UUID storeId,
            @Valid @RequestBody KsefLinkConnectionRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return connectionService.linkConnection(storeId, request.nip(), principal.getId());
    }

    /** Podłączenie innych sklepów użytkownika do firmy tego sklepu (bez wgrywania certyfikatu w każdym). */
    @PostMapping("/settings/connection/share")
    @PreAuthorize(EDIT)
    public KsefSettingsResponse shareConnection(@PathVariable UUID storeId,
            @Valid @RequestBody KsefShareConnectionRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return connectionService.shareConnection(storeId, request.storeIds(), principal.getId());
    }

    @PostMapping("/settings/test")
    @PreAuthorize(EDIT)
    public KsefSettingsResponse test(@PathVariable UUID storeId) {
        return connectionService.test(storeId);
    }

    @PostMapping("/settings/internal-ids")
    @PreAuthorize(EDIT)
    public KsefSettingsResponse addInternalId(@PathVariable UUID storeId,
            @Valid @RequestBody KsefInternalIdRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return connectionService.addInternalId(storeId, request.internalId(), request.name(), principal.getId());
    }

    @DeleteMapping("/settings/internal-ids/{id}")
    @PreAuthorize(EDIT)
    public KsefSettingsResponse removeInternalId(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return connectionService.removeInternalId(storeId, id, principal.getId());
    }

    @PostMapping("/settings/internal-ids/{id}/restore")
    @PreAuthorize(EDIT)
    public KsefSettingsResponse restoreInternalId(@PathVariable UUID storeId, @PathVariable UUID id) {
        return connectionService.restoreInternalId(storeId, id);
    }

    @PostMapping("/settings/skipped-suppliers")
    @PreAuthorize(EDIT)
    public KsefSettingsResponse addSkippedSupplier(@PathVariable UUID storeId,
            @Valid @RequestBody KsefSkippedSupplierRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return connectionService.addSkippedSupplier(storeId, request.nip(), request.name(), principal.getId());
    }

    @DeleteMapping("/settings/skipped-suppliers/{id}")
    @PreAuthorize(EDIT)
    public KsefSettingsResponse removeSkippedSupplier(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return connectionService.removeSkippedSupplier(storeId, id, principal.getId());
    }

    @PostMapping("/settings/skipped-suppliers/{id}/restore")
    @PreAuthorize(EDIT)
    public KsefSettingsResponse restoreSkippedSupplier(@PathVariable UUID storeId, @PathVariable UUID id) {
        return connectionService.restoreSkippedSupplier(storeId, id);
    }
}
