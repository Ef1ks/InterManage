package com.intermanage.backend.document.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.common.exception.InvalidReportException;
import com.intermanage.backend.deliorder.repository.DeliOrderDocumentRepository;
import com.intermanage.backend.document.component.DeliveryNotePdf;
import com.intermanage.backend.document.component.DocumentReportParser;
import com.intermanage.backend.document.dto.CorrectedDelivery;
import com.intermanage.backend.document.dto.CorrectionTotals;
import com.intermanage.backend.document.dto.DocumentImportResponse;
import com.intermanage.backend.document.dto.DocumentLineRow;
import com.intermanage.backend.document.dto.DocumentPage;
import com.intermanage.backend.document.dto.DocumentResponse;
import com.intermanage.backend.document.dto.DocumentTypeSummary;
import com.intermanage.backend.document.dto.DocumentsOverview;
import com.intermanage.backend.document.dto.InvoiceAmountCheck;
import com.intermanage.backend.document.dto.LinkedDeliOrder;
import com.intermanage.backend.document.dto.LinkedKsefInvoice;
import com.intermanage.backend.document.dto.ParsedDocumentReport;
import com.intermanage.backend.document.entity.DeliveryCorrection;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentGratis;
import com.intermanage.backend.document.entity.DocumentImport;
import com.intermanage.backend.document.entity.DocumentLine;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.mapper.DocumentMapper;
import com.intermanage.backend.document.repository.DocumentGratisRepository;
import com.intermanage.backend.document.repository.DocumentImportRepository;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.document.repository.DocumentSearch;
import com.intermanage.backend.document.repository.DocumentTotalsRepository;
import com.intermanage.backend.nomenclature.entity.Nomenclature;
import com.intermanage.backend.nomenclature.repository.NomenclatureRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Dokumenty magazynowe z raportu Wejścia/Wyjścia (typy z {@link DocumentType}). Plik jest najpierw w całości czytany
 * i sprawdzany, potem w jednej transakcji scalany z dokumentami sklepu po typie i numerze wewnętrznym: nowe dokumenty
 * są dodawane, znane aktualizowane linia po linii (N° ligne + ITM8). Raporty mogą obejmować nakładające się okresy,
 * więc ten sam dokument przychodzi wielokrotnie - nic nie jest wtedy usuwane, a podsumowanie liczy się ze wszystkich
 * zapisanych linii.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private static final int HISTORY_SIZE = 30;
    /** Dokumentów jednego typu są tysiące; frontend dociąga kolejne strony przy przewijaniu. */
    public static final int PAGE_SIZE = 100;
    /** Klucz blokady to ten przedrostek + id sklepu; inny niż przy imporcie produktów, więc oba importy nie czekają na siebie. */
    private static final String IMPORT_LOCK_PREFIX = "document-import:";

    private final TransactionTemplate transaction;
    private final DocumentReportParser reportParser;
    private final DocumentMapper documentMapper;
    private final DocumentImportRepository imports;
    private final DocumentRepository documents;
    private final AppUserRepository users;
    private final StoreRepository stores;
    private final DeliOrderDocumentRepository deliOrderDocuments;
    private final DocumentGratisRepository gratis;
    private final DocumentKsefLinkService ksefLinks;
    private final DeliveryCorrectionService corrections;
    private final SupplierRepository suppliers;
    private final NomenclatureRepository nomenclatures;
    private final DeliveryNotePdf deliveryNotePdf;
    private final InvoiceAmountCheckService amountCheckService;

    public DocumentService(TransactionTemplate transaction, DocumentReportParser reportParser,
            DocumentMapper documentMapper, DocumentImportRepository imports, DocumentRepository documents,
            AppUserRepository users, StoreRepository stores, DeliOrderDocumentRepository deliOrderDocuments,
            DocumentGratisRepository gratis, DocumentKsefLinkService ksefLinks, DeliveryCorrectionService corrections,
            SupplierRepository suppliers, NomenclatureRepository nomenclatures, DeliveryNotePdf deliveryNotePdf,
            InvoiceAmountCheckService amountCheckService) {
        this.amountCheckService = amountCheckService;
        this.ksefLinks = ksefLinks;
        this.corrections = corrections;
        this.suppliers = suppliers;
        this.nomenclatures = nomenclatures;
        this.deliveryNotePdf = deliveryNotePdf;
        this.transaction = transaction;
        this.reportParser = reportParser;
        this.documentMapper = documentMapper;
        this.imports = imports;
        this.documents = documents;
        this.users = users;
        this.stores = stores;
        this.deliOrderDocuments = deliOrderDocuments;
        this.gratis = gratis;
    }

    /** Wszystkie typy w kolejności z {@link DocumentType}, także te bez dokumentów. */
    @Transactional(readOnly = true)
    public DocumentsOverview overview(UUID storeId) {
        Map<DocumentType, DocumentRepository.TypeCount> counts = new HashMap<>();
        documents.countByType(storeId).forEach(count -> counts.put(count.getType(), count));
        List<DocumentTypeSummary> types = Arrays.stream(DocumentType.values())
                .map(type -> {
                    DocumentRepository.TypeCount count = counts.get(type);
                    return documentMapper.toSummary(type, count != null ? count.getDocumentCount() : 0,
                            count != null ? count.getLastDocumentDate() : null);
                })
                .toList();
        return new DocumentsOverview(imports.findNewestReportGeneratedAt(storeId).orElse(null), types);
    }

    /**
     * Strona listy dokumentów typu; wyszukiwanie, filtry i sortowanie w bazie ({@link DocumentSearch}). {@code product}
     * szuka artykułu w liniach, {@code invoice} działa w przyjęciach i korektach od dostawcy, {@code corrections}
     * w przyjęciach.
     */
    @Transactional(readOnly = true)
    public DocumentPage list(UUID storeId, String typeCode, int page, String query, String product,
            DocumentSearch.Presence invoice, DocumentSearch.Presence withCorrections, String sort, String direction) {
        DocumentType type = type(typeCode);
        Specification<Document> matching = DocumentSearch.inStoreMatching(storeId, type, DocumentSearch.words(query))
                .and(DocumentSearch.withProduct(DocumentSearch.words(product)));
        if (type.canLinkKsefInvoice() && invoice != DocumentSearch.Presence.ALL) {
            matching = matching.and(DocumentSearch.withInvoice(invoice, ksefLinks.nip(storeId)));
        }
        if (type == DocumentType.PZ) {
            matching = matching.and(DocumentSearch.withCorrections(withCorrections));
        }
        Page<Document> found = documents.findAll(matching,
                PageRequest.of(Math.max(page, 0), PAGE_SIZE, DocumentSearch.sort(sort, direction)));
        DocumentTotalsRepository.Totals totals = documents.totals(matching);
        DocumentRepository.TypeCount count = documents.countByType(storeId).stream()
                .filter(typeCount -> typeCount.getType() == type).findFirst().orElse(null);
        DocumentTypeSummary summary = documentMapper.toSummary(type, count != null ? count.getDocumentCount() : 0,
                count != null ? count.getLastDocumentDate() : null);
        Map<UUID, DocumentGratis> gratisByDocument = found.isEmpty() ? Map.of()
                : gratis.findActive(found.getContent().stream().map(Document::getId).toList()).stream()
                        .collect(Collectors.toMap(item -> item.getDocument().getId(), Function.identity()));
        Map<UUID, List<DeliveryCorrection>> correctionsByDelivery = corrections.correctionsOf(found.getContent());
        Map<UUID, Document> deliveries = corrections.deliveriesOf(found.getContent());
        Map<UUID, List<LinkedKsefInvoice>> ksefInvoices =
                ksefLinks.invoicesWithCorrections(storeId, found.getContent(), correctionsByDelivery);
        Map<UUID, CorrectionTotals> correctionTotals = new HashMap<>();
        found.getContent().forEach(document -> correctionTotals.put(document.getId(),
                corrections.totals(document, correctionsByDelivery.get(document.getId()))));
        Map<UUID, InvoiceAmountCheck> amountChecks =
                amountCheckService.checks(found.getContent(), ksefInvoices, correctionTotals);
        return new DocumentPage(summary, imports.findNewestReportGeneratedAt(storeId).orElse(null),
                found.getContent().stream()
                        .map(document -> documentMapper.toSummary(document, gratisByDocument.get(document.getId()),
                                ksefInvoices.getOrDefault(document.getId(), List.of()),
                                correctionTotals.get(document.getId()),
                                deliveries.containsKey(document.getId())
                                        ? documentMapper.toLinkedDelivery(deliveries.get(document.getId())) : null,
                                amountChecks.get(document.getId())))
                        .toList(),
                found.getNumber(),
                found.hasNext(), totals.count(), totals.purchaseValue(), totals.saleValueGross());
    }

    /**
     * Dokument z liniami i gratisem, jeśli go zarejestrowano; ruch międzymagazynowy także z zamówieniami garmażu,
     * które na nim jadą. Przyjęcie z przypiętymi korektami ma też linie i wartości po korektach, a korekta -
     * przyjęcie, do którego ją przypięto.
     */
    @Transactional(readOnly = true)
    public DocumentResponse document(UUID storeId, String typeCode, UUID id) {
        Document document = documents.findWithLines(storeId, type(typeCode), id)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu"));
        List<LinkedDeliOrder> deliOrders = document.getType() != DocumentType.MM ? List.of()
                : deliOrderDocuments.findActiveByDocumentId(document.getId()).stream()
                        .map(link -> new LinkedDeliOrder(link.getOrder().getId(), link.getOrder().getStore().getNumber(),
                                link.getOrder().getStore().getName(), link.getOrder().getDeliveryDate()))
                        .toList();
        Map<UUID, List<DeliveryCorrection>> links = corrections.correctionsOf(List.of(document));
        List<DeliveryCorrection> ofDelivery = links.getOrDefault(document.getId(), List.of());
        CorrectedDelivery corrected = ofDelivery.isEmpty() ? null
                : corrections.corrected(document, corrections.correctionDocuments(storeId, ofDelivery));
        Document delivery = corrections.deliveriesOf(List.of(document)).get(document.getId());
        Map<UUID, List<LinkedKsefInvoice>> ksefInvoices =
                ksefLinks.invoicesWithCorrections(storeId, List.of(document), links);
        CorrectionTotals totals = corrections.totals(document, ofDelivery);
        Map<UUID, CorrectionTotals> totalsByDocument = new HashMap<>();
        totalsByDocument.put(document.getId(), totals);
        return documentMapper.toResponse(document, deliOrders, gratis.findActive(document.getId()).orElse(null),
                ksefInvoices.getOrDefault(document.getId(), List.of()),
                ofDelivery.stream().map(documentMapper::toCorrectionResponse).toList(), corrected, totals,
                delivery != null ? documentMapper.toLinkedDelivery(delivery) : null,
                amountCheckService.checks(List.of(document), ksefInvoices, totalsByDocument).get(document.getId()));
    }

    /**
     * Wydruk bonu dostawy przyjęcia PZ (PDF jak z systemu sklepu): linie i sumy po przypiętych korektach, więc
     * zmienia się, gdy dojdzie albo zmieni się korekta.
     */
    @Transactional(readOnly = true)
    public ExportFile deliveryNote(UUID storeId, String typeCode, UUID id, UUID userId) {
        Document document = documents.findWithLines(storeId, type(typeCode), id)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu"));
        if (document.getType() != DocumentType.PZ) {
            throw ApiException.badRequest("Bon dostawy drukuje się dla przyjęcia towaru");
        }
        List<Document> correctionDocuments = corrections.correctionDocuments(storeId,
                corrections.correctionsOf(List.of(document)).getOrDefault(document.getId(), List.of()));
        CorrectedDelivery corrected = corrections.corrected(document, correctionDocuments);
        Supplier supplier = document.getSupplierCode() == null ? null
                : suppliers.findImported(storeId).stream()
                        .filter(found -> document.getSupplierCode().equals(found.getCode())).findFirst().orElse(null);
        List<String> regionCodes = corrected.lines().stream().map(CorrectedDelivery.CorrectedLine::nomenclatureCode)
                .filter(code -> code != null && code.length() >= 2).map(code -> code.substring(0, 2))
                .distinct().toList();
        Map<String, String> regions = regionCodes.isEmpty() ? Map.of()
                : nomenclatures.findByStoreIdAndCodeIn(storeId, regionCodes).stream()
                        .collect(Collectors.toMap(Nomenclature::getCode, Nomenclature::getName, (a, b) -> a));
        String printedBy = userId != null ? users.findById(userId).map(AppUser::getDisplayName).orElse(null) : null;
        byte[] pdf = deliveryNotePdf.pdf(new DeliveryNotePdf.Note(document, document.getStore(), supplier,
                corrected, correctionDocuments, regions, printedBy));
        return new ExportFile("bon-dostawy-" + document.bonNumber() + ".pdf",
                "application/pdf", pdf);
    }

    public record ExportFile(String fileName, String contentType, byte[] content) {
    }

    /**
     * Ręcznie poprawiony numer dokumentu dostawcy w przyjęciu PZ (po nim faktura KSeF wiąże się z dostawą). Numer
     * z raportu zostaje zapisany obok, a kolejne importy nie nadpisują poprawki.
     */
    @Transactional
    public DocumentResponse editSupplierDocumentNumber(UUID storeId, String typeCode, UUID id, String number,
            UUID userId) {
        Document document = editableDelivery(storeId, typeCode, id);
        String trimmed = number == null || number.isBlank() ? null : number.strip();
        if (Objects.equals(trimmed, document.getReportSupplierDocumentNumber())) {
            document.restoreReportSupplierDocumentNumber();
        } else {
            document.editSupplierDocumentNumber(trimmed, userId != null ? users.getReferenceById(userId) : null);
        }
        documents.flush();
        return document(storeId, typeCode, id);
    }

    /** Cofa ręczną poprawkę: obowiązuje znowu numer z raportu. */
    @Transactional
    public DocumentResponse restoreSupplierDocumentNumber(UUID storeId, String typeCode, UUID id) {
        editableDelivery(storeId, typeCode, id).restoreReportSupplierDocumentNumber();
        documents.flush();
        return document(storeId, typeCode, id);
    }

    private Document editableDelivery(UUID storeId, String typeCode, UUID id) {
        Document document = documents.findWithLines(storeId, type(typeCode), id)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu"));
        if (document.getType() != DocumentType.PZ) {
            throw ApiException.badRequest("Numer dokumentu dostawcy można poprawić tylko w przyjęciu towaru");
        }
        return document;
    }

    @Transactional(readOnly = true)
    public List<DocumentImportResponse> imports(UUID storeId) {
        return imports.findLatest(storeId, Limit.of(HISTORY_SIZE)).stream().map(documentMapper::toResponse).toList();
    }

    private static DocumentType type(String code) {
        return DocumentType.fromCode(code).orElseThrow(() -> ApiException.notFound("Nieznany typ dokumentu"));
    }

    public DocumentImportResponse importReport(UUID storeId, MultipartFile file, UUID userId) {
        String fileName = file.getOriginalFilename() != null
                ? Path.of(file.getOriginalFilename()).getFileName().toString() : "dokumenty.xlsx";
        if (file.isEmpty()) {
            throw ApiException.badRequest("Wybierz plik do importu");
        }
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".xlsx") && !lowerName.endsWith(".xlsm")) {
            throw ApiException.badRequest("Obsługujemy pliki .xlsx i .xlsm. Plik .xls zapisz w Excelu jako .xlsx.");
        }

        ParsedDocumentReport report = parse(file);
        DocumentImportResponse result = transaction.execute(status -> merge(storeId, report, fileName, userId));
        log.info("Import dokumentów do sklepu {} z {}: {} linii, zapisane {} (nowe dokumenty {}, uzupełnione {}), "
                + "pominięte {} ({})", storeId, fileName, result.totalLines(), result.savedLines(), result.createdCount(),
                result.updatedCount(), result.skippedLines(), result.skippedTypes());
        return result;
    }

    private ParsedDocumentReport parse(MultipartFile file) {
        Path temp = null;
        try {
            // Skoroszyt to archiwum zip, które trzeba czytać w dowolnej kolejności, więc potrzebny jest plik na dysku.
            temp = Files.createTempFile("document-import-", ".xlsx");
            file.transferTo(temp);
            return reportParser.parse(temp);
        } catch (InvalidReportException e) {
            throw ApiException.badRequest(e.getMessage())
                    .withProperty("rowErrors", e.errors())
                    .withProperty("rowErrorCount", e.errorCount());
        } catch (IOException e) {
            log.warn("Nie udało się odczytać pliku importu dokumentów", e);
            throw ApiException.badRequest("Nie udało się odczytać pliku. Sprawdź, czy nie jest uszkodzony.");
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.warn("Nie udało się usunąć pliku tymczasowego {}", temp, e);
                }
            }
        }
    }

    private DocumentImportResponse merge(UUID storeId, ParsedDocumentReport report, String fileName, UUID userId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        if (report.storeNumber() != null && !report.storeNumber().equals(store.getNumber())) {
            throw ApiException.conflict("To raport sklepu " + report.storeNumber() + ", a wybrany jest sklep "
                    + store.getNumber() + " (" + store.getName() + "). Przełącz sklep albo wgraj właściwy plik.");
        }
        documents.lockForImport(IMPORT_LOCK_PREFIX + storeId);

        AppUser user = userId != null ? users.getReferenceById(userId) : null;
        String skippedTypes = report.skippedLinesByType().isEmpty() ? null
                : String.join(", ", report.skippedLinesByType().keySet().stream().sorted().toList());
        DocumentImport documentImport = imports.save(new DocumentImport(store, fileName, report.generatedAt(),
                report.periodFrom(), report.periodTo(), user, report.totalLines(), report.lines().size(),
                report.skippedLines(), skippedTypes));

        // Linie z pliku pogrupowane po typie i numerze dokumentu, w kolejności z raportu.
        Map<DocumentType, Map<String, List<DocumentLineRow>>> linesByDocument = new LinkedHashMap<>();
        for (DocumentLineRow line : report.lines()) {
            linesByDocument.computeIfAbsent(line.type(), type -> new LinkedHashMap<>())
                    .computeIfAbsent(line.documentNumber(), number -> new ArrayList<>()).add(line);
        }

        int created = 0;
        int updated = 0;
        for (Map.Entry<DocumentType, Map<String, List<DocumentLineRow>>> byType : linesByDocument.entrySet()) {
            Map<String, Document> existing = new HashMap<>();
            documents.findWithLines(storeId, byType.getKey(), byType.getValue().keySet())
                    .forEach(document -> existing.put(document.getNumber(), document));
            for (Map.Entry<String, List<DocumentLineRow>> entry : byType.getValue().entrySet()) {
                List<DocumentLineRow> lines = entry.getValue();
                Document document = existing.get(entry.getKey());
                boolean isNew = document == null;
                boolean changed = false;
                if (isNew) {
                    document = new Document(store, lines.getFirst(), documentImport);
                } else {
                    changed = document.applyHeader(lines.getFirst(), documentImport);
                }
                Map<String, DocumentLine> byKey = new HashMap<>();
                document.getLines().forEach(line -> byKey.put(line.lineKey(), line));
                for (DocumentLineRow line : lines) {
                    changed |= document.applyLine(line, byKey.get(line.lineKey()));
                }
                document.recalculateSummary();
                if (isNew) {
                    documents.save(document);
                    created++;
                } else if (changed) {
                    updated++;
                }
            }
        }

        documentImport.recordResult(created, updated);
        return documentMapper.toResponse(documentImport);
    }
}
