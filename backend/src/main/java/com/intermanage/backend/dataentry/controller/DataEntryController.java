package com.intermanage.backend.dataentry.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.common.component.ProtoFile;
import com.intermanage.backend.dataentry.component.InvoiceXml;
import com.intermanage.backend.dataentry.dto.CatalogListItem;
import com.intermanage.backend.dataentry.dto.CatalogListPage;
import com.intermanage.backend.dataentry.dto.CatalogListRequest;
import com.intermanage.backend.dataentry.dto.CatalogProduct;
import com.intermanage.backend.dataentry.dto.DataEntryInvoice;
import com.intermanage.backend.dataentry.dto.ExportRequest;
import com.intermanage.backend.dataentry.dto.NomenclatureItem;
import com.intermanage.backend.dataentry.dto.ProductLink;
import com.intermanage.backend.dataentry.dto.ProductLinkRequest;
import com.intermanage.backend.dataentry.dto.ProtocolRequest;
import com.intermanage.backend.dataentry.dto.SuggestionRequest;
import com.intermanage.backend.dataentry.dto.SuggestionResponse;
import com.intermanage.backend.dataentry.service.DataEntryLinkService;
import com.intermanage.backend.dataentry.service.DataEntryProtocolService;
import com.intermanage.backend.dataentry.service.DataEntryService;
import com.intermanage.backend.dataentry.service.DataEntrySuggestionService;
import com.intermanage.backend.store.entity.ProtocolKind;

import jakarta.validation.Valid;

/**
 * Wprowadzanie danych: faktura z KSeF w tabeli (z kartoteką sklepu), artykuły po EAN-ie, nomenklatura, propozycje AI
 * dla nowych artykułów (EDIT, bo każde wywołanie modelu kosztuje), plik XML faktury dla programu sklepu, eksport
 * tabeli do Excela i protokoły PROTO1-6.
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
    private final DataEntryProtocolService protocolService;
    private final DataEntryLinkService linkService;

    public DataEntryController(DataEntryService service, DataEntrySuggestionService suggestionService,
            DataEntryProtocolService protocolService, DataEntryLinkService linkService) {
        this.service = service;
        this.suggestionService = suggestionService;
        this.protocolService = protocolService;
        this.linkService = linkService;
    }

    /**
     * Pozycje faktury z KSeF z artykułami z kartoteki; {@code source}: skąd wziąć EAN (domyślnie AUTO). Faktury widzi
     * tylko osoba z dostępem do faktur KSeF.
     */
    @GetMapping("/invoices/{invoiceId}")
    @PreAuthorize(VIEW + " and hasAuthority('KSEF_INVOICES:VIEW')")
    public DataEntryInvoice invoice(@PathVariable UUID storeId, @PathVariable UUID invoiceId,
            @RequestParam(required = false) String source) {
        return service.invoice(storeId, invoiceId, source);
    }

    /** Artykuły z kartoteki dla wpisanych EAN-ów ({@code ?ean=…&ean=…}). */
    @GetMapping("/products")
    @PreAuthorize(VIEW)
    public List<CatalogProduct> products(@PathVariable UUID storeId, @RequestParam List<String> ean) {
        return service.lookup(storeId, ean);
    }

    /**
     * Lista towarów z kartoteki z filtrem nad każdą kolumną (fragment, {@code *} = dowolny ciąg), nomenklaturą,
     * przedziałami cen i sortowaniem po stanie; strony po 100 od {@code page} = 0.
     */
    @GetMapping("/catalog")
    @PreAuthorize(VIEW)
    public CatalogListPage catalog(@PathVariable UUID storeId, @ModelAttribute CatalogListRequest request) {
        return service.catalogList(storeId, request);
    }

    /** Propozycje artykułów do przypięcia pozycji bez EAN-u: podobne nazwy, najpierw towar dostawcy o tym NIP-ie. */
    @GetMapping("/link-suggestions")
    @PreAuthorize(VIEW)
    public List<CatalogListItem> linkSuggestions(@PathVariable UUID storeId, @RequestParam String text,
            @RequestParam(required = false) String sellerNip) {
        return linkService.suggestions(storeId, text, sellerNip);
    }

    /** Przypina kod pozycji dostawcy do artykułu: kolejne faktury tego dostawcy wczytują go od razu. */
    @PostMapping("/links")
    @PreAuthorize(VIEW)
    public ProductLink link(@PathVariable UUID storeId, @Valid @RequestBody ProductLinkRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return linkService.link(storeId, request, principal.getId());
    }

    /** Odpięcie (znacznik). */
    @DeleteMapping("/links/{linkId}")
    @PreAuthorize(VIEW)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(@PathVariable UUID storeId, @PathVariable UUID linkId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        linkService.unlink(storeId, linkId, principal.getId());
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

    /** Plik XML faktury dla programu sklepu; zapisuje go przeglądarka w wybranym folderze. */
    @PostMapping("/export/xml")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> exportXml(@PathVariable UUID storeId, @Valid @RequestBody ExportRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        DataEntryService.ExportFile file = service.exportXml(storeId, request, principal.getId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .contentType(new MediaType(MediaType.APPLICATION_XML, InvoiceXml.CHARSET))
                .body(file.content());
    }

    /** Plik PROTO1-PROTO6 protokołu z artykułów tabeli; zapisuje go przeglądarka w folderze plików PROTO. */
    @PostMapping("/protocols/{kind}")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> protocol(@PathVariable UUID storeId, @PathVariable ProtocolKind kind,
            @Valid @RequestBody ProtocolRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        DataEntryProtocolService.ProtocolFile file =
                protocolService.generate(storeId, kind, request, principal.getUsername());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .contentType(MediaType.parseMediaType(ProtoFile.CONTENT_TYPE))
                .body(file.content());
    }
}
