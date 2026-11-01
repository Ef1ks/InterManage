package com.intermanage.backend.dataentry.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.common.component.ProtoFile;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.component.InvoiceXml;
import com.intermanage.backend.dataentry.dto.CatalogListItem;
import com.intermanage.backend.dataentry.dto.CatalogListPage;
import com.intermanage.backend.dataentry.dto.CatalogListRequest;
import com.intermanage.backend.dataentry.dto.CatalogProduct;
import com.intermanage.backend.dataentry.dto.DataEntryInvoice;
import com.intermanage.backend.dataentry.dto.DataEntryInvoiceItem;
import com.intermanage.backend.dataentry.dto.DataEntryInvoicePage;
import com.intermanage.backend.dataentry.dto.InvoiceBulkRequest;
import com.intermanage.backend.dataentry.dto.InvoiceBulkResult;
import com.intermanage.backend.dataentry.dto.InvoiceBulkUndoRequest;
import com.intermanage.backend.dataentry.dto.InvoiceMarkRequest;
import com.intermanage.backend.dataentry.dto.SkippedSupplier;
import com.intermanage.backend.dataentry.dto.SkippedSupplierRequest;
import com.intermanage.backend.dataentry.dto.ExportRequest;
import com.intermanage.backend.dataentry.dto.NomenclatureItem;
import com.intermanage.backend.dataentry.dto.ProductLink;
import com.intermanage.backend.dataentry.dto.ProductLinkRequest;
import com.intermanage.backend.dataentry.dto.ProtocolRequest;
import com.intermanage.backend.dataentry.dto.SuggestionRequest;
import com.intermanage.backend.dataentry.dto.SuggestionResponse;
import com.intermanage.backend.dataentry.dto.TemporaryXmlRequest;
import com.intermanage.backend.dataentry.dto.XmlDocument;
import com.intermanage.backend.dataentry.dto.XmlExportItem;
import com.intermanage.backend.dataentry.entity.DataEntryXmlExport;
import com.intermanage.backend.dataentry.service.DataEntryInvoiceService;
import com.intermanage.backend.dataentry.service.DataEntryLinkService;
import com.intermanage.backend.dataentry.service.DataEntryProtocolService;
import com.intermanage.backend.dataentry.service.DataEntryService;
import com.intermanage.backend.dataentry.service.DataEntrySuggestionService;
import com.intermanage.backend.dataentry.service.DataEntryXmlService;
import com.intermanage.backend.ksef.component.KsefInvoicePdf;
import com.intermanage.backend.ksef.service.KsefInvoiceService;
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
    private final DataEntryInvoiceService invoiceService;
    private final DataEntryXmlService xmlService;

    public DataEntryController(DataEntryService service, DataEntrySuggestionService suggestionService,
            DataEntryProtocolService protocolService, DataEntryLinkService linkService,
            DataEntryInvoiceService invoiceService, DataEntryXmlService xmlService) {
        this.service = service;
        this.suggestionService = suggestionService;
        this.protocolService = protocolService;
        this.linkService = linkService;
        this.invoiceService = invoiceService;
        this.xmlService = xmlService;
    }

    private static final String INVOICES = VIEW + " and hasAuthority('KSEF_INVOICES:VIEW')";

    /**
     * Okno wczytywania: faktury ({@code kind=INVOICES}) albo korekty ({@code CORRECTIONS}) sklepu, strony po 100;
     * {@code status}: NEW do wczytania (domyślnie, pobrane też), DONE wczytane i nie moje, ALL; {@code internalId} jak
     * w Fakturach KSeF (puste = IDWew sklepu i bez IDWew, OWN, NONE).
     */
    @GetMapping("/invoices")
    @PreAuthorize(INVOICES)
    public DataEntryInvoicePage invoices(@PathVariable UUID storeId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String query, @RequestParam(required = false) String internalId,
            @RequestParam(defaultValue = "INVOICES") String kind,
            @RequestParam(defaultValue = "NEW") DataEntryInvoiceService.ListStatus status,
            @RequestParam(defaultValue = "false") boolean showSkipped) {
        return invoiceService.list(storeId, page, query, internalId, "CORRECTIONS".equals(kind), status,
                showSkipped);
    }

    /** Znacznik faktury: LOADED po wczytaniu do tabeli, NOT_MINE "nie moja faktura" (DOWNLOADED daje pobranie PDF). */
    @PostMapping("/invoices/{invoiceId}/marks")
    @PreAuthorize(INVOICES)
    public DataEntryInvoiceItem mark(@PathVariable UUID storeId, @PathVariable UUID invoiceId,
            @Valid @RequestBody InvoiceMarkRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return invoiceService.mark(storeId, invoiceId, request.kind(), principal.getId());
    }

    /**
     * Działanie na zaznaczonych fakturach (podanych albo wszystkich pasujących do filtrów listy): wczytane, nie moje
     * albo z powrotem do wczytania.
     */
    @PostMapping("/invoices/bulk")
    @PreAuthorize(INVOICES)
    public InvoiceBulkResult bulk(@PathVariable UUID storeId, @Valid @RequestBody InvoiceBulkRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return invoiceService.bulk(storeId, request, principal.getId());
    }

    /** „Cofnij” działania zbiorczego. */
    @PostMapping("/invoices/bulk/undo")
    @PreAuthorize(INVOICES)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void undoBulk(@PathVariable UUID storeId, @RequestBody InvoiceBulkUndoRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        invoiceService.undoBulk(storeId, request, principal.getId());
    }

    /** Faktura wraca na listę "Do wczytania" (znaczniki sklepu dostają znacznik usunięcia). */
    @DeleteMapping("/invoices/{invoiceId}/marks")
    @PreAuthorize(INVOICES)
    public DataEntryInvoiceItem restoreInvoice(@PathVariable UUID storeId, @PathVariable UUID invoiceId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return invoiceService.restore(storeId, invoiceId, principal.getId());
    }

    /** PDF faktury: {@code variant=BASIC} (domyślnie, do wprowadzania) albo FULL; zapisuje informację "pobrana". */
    @GetMapping("/invoices/{invoiceId}/pdf")
    @PreAuthorize(INVOICES)
    public ResponseEntity<byte[]> invoicePdf(@PathVariable UUID storeId, @PathVariable UUID invoiceId,
            @RequestParam(defaultValue = "BASIC") KsefInvoicePdf.Variant variant,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        KsefInvoiceService.ExportFile file = invoiceService.pdf(storeId, invoiceId, variant, principal.getId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(file.content());
    }

    @GetMapping("/skipped-suppliers")
    @PreAuthorize(VIEW)
    public List<SkippedSupplier> skippedSuppliers(@PathVariable UUID storeId) {
        return invoiceService.skippedSuppliers(storeId);
    }

    /**
     * Reguła dostawcy w oknie wczytywania (niezależnie od pomijanych w Fakturach KSeF): {@code mode} HIDDEN pomijany,
     * DONE odkładany do wczytanych.
     */
    @PostMapping("/skipped-suppliers")
    @PreAuthorize(VIEW)
    public SkippedSupplier skipSupplier(@PathVariable UUID storeId, @Valid @RequestBody SkippedSupplierRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return invoiceService.skip(storeId, request, principal.getId());
    }

    @DeleteMapping("/skipped-suppliers/{id}")
    @PreAuthorize(VIEW)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unskipSupplier(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        invoiceService.unskip(storeId, id, principal.getId());
    }

    @PostMapping("/skipped-suppliers/{id}/restore")
    @PreAuthorize(VIEW)
    public SkippedSupplier restoreSkippedSupplier(@PathVariable UUID storeId, @PathVariable UUID id) {
        return invoiceService.restoreSkipped(storeId, id);
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
                        ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file.content());
    }

    /**
     * Plik XML faktury dla programu sklepu; zapisuje go przeglądarka w wybranym folderze. Nagłówek {@code X-Xml-Export-Id}
     * to wpis, który po udanym zapisie na dysku potwierdza {@code POST /xml-history/{id}/saved} (dopiero wtedy jest
     * w historii).
     */
    @PostMapping("/export/xml")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> exportXml(@PathVariable UUID storeId, @Valid @RequestBody ExportRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        DataEntryXmlService.ExportedFile file = xmlService.export(storeId, request, principal.getId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(XML_EXPORT_ID, file.id().toString())
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, XML_EXPORT_ID)
                .contentType(new MediaType(MediaType.APPLICATION_XML, InvoiceXml.CHARSET))
                .body(file.content());
    }

    private static final String XML_EXPORT_ID = "X-Xml-Export-Id";

    /** Przeglądarka zapisała plik na dysku: od teraz jest w historii. Ta sama tabela co przy generowaniu pliku. */
    @PostMapping("/xml-history/{id}/saved")
    @PreAuthorize(VIEW)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void xmlSaved(@PathVariable UUID storeId, @PathVariable UUID id, @Valid @RequestBody ExportRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        xmlService.confirmSaved(storeId, id, request, principal.getId());
    }

    /** 10 ostatnich zapisów tymczasowych sklepu, od najnowszego. */
    @GetMapping("/xml-temporary")
    @PreAuthorize(VIEW)
    public List<XmlExportItem> xmlTemporary(@PathVariable UUID storeId) {
        return xmlService.temporary(storeId);
    }

    /** "Zapisz tymczasowo": plik XML i tabela z przeglądarki, bez zapisu na dysku; ponad 10 najstarszy znika. */
    @PostMapping("/xml-temporary")
    @PreAuthorize(VIEW)
    public XmlExportItem saveTemporary(@PathVariable UUID storeId, @Valid @RequestBody TemporaryXmlRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return xmlService.saveTemporary(storeId, request.export(), request.draft(), principal.getId());
    }

    @DeleteMapping("/xml-temporary/{id}")
    @PreAuthorize(VIEW)
    public List<XmlExportItem> deleteTemporary(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return xmlService.deleteTemporary(storeId, id, principal.getId());
    }

    @PostMapping("/xml-temporary/{id}/restore")
    @PreAuthorize(VIEW)
    public List<XmlExportItem> restoreTemporary(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return xmlService.restoreTemporary(storeId, id, principal.getId());
    }

    /** 100 ostatnich plików XML zapisanych w sklepie przez "Zapisz XML", od najnowszego. */
    @GetMapping("/xml-history")
    @PreAuthorize(VIEW)
    public List<XmlExportItem> xmlHistory(@PathVariable UUID storeId) {
        return xmlService.history(storeId);
    }

    /** Plik z historii albo zapis tymczasowy jako dokument do tabeli (zapis tymczasowy z tabelą w {@code draft}). */
    @GetMapping("/xml-history/{id}")
    @PreAuthorize(VIEW)
    public XmlDocument xmlHistoryDocument(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return xmlService.fromHistory(storeId, id, seesKsef(principal));
    }

    /** Plik z historii do ponownego pobrania, taki jak przy zapisie. */
    @GetMapping("/xml-history/{id}/file")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> xmlHistoryFile(@PathVariable UUID storeId, @PathVariable UUID id) {
        DataEntryXmlExport file = xmlService.file(storeId, id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.getFileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(new MediaType(MediaType.APPLICATION_XML, InvoiceXml.CHARSET))
                .body(file.getContent());
    }

    /** Plik XML faktury z dysku (własny albo od dostawcy) jako dokument do tabeli. */
    @PostMapping("/xml-documents")
    @PreAuthorize(VIEW)
    public XmlDocument readXml(@PathVariable UUID storeId, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        try {
            return xmlService.fromFile(storeId, file.getOriginalFilename(), file.getBytes(), seesKsef(principal));
        } catch (IOException e) {
            throw ApiException.badRequest("Nie udało się odczytać pliku");
        }
    }

    /** Faktura z KSeF przy pliku XML tylko z dostępem do faktur KSeF. */
    private static boolean seesKsef(AppUserPrincipal principal) {
        return principal.getAuthorities().stream()
                .anyMatch(authority -> "KSEF_INVOICES:VIEW".equals(authority.getAuthority()));
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
                        ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(ProtoFile.CONTENT_TYPE))
                .body(file.content());
    }
}
