package com.intermanage.backend.dataentry.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.dto.CatalogProduct;
import com.intermanage.backend.dataentry.dto.CorrectionDelivery;
import com.intermanage.backend.dataentry.dto.CorrectionDeliveryLine;
import com.intermanage.backend.document.dto.CorrectedDelivery;
import com.intermanage.backend.document.entity.DeliveryCorrection;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentKsefInvoice;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.repository.DocumentKsefInvoiceRepository;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.document.repository.DocumentSearch;
import com.intermanage.backend.document.service.DeliveryCorrectionService;
import com.intermanage.backend.document.service.DocumentKsefLinkService;
import com.intermanage.backend.ksef.component.KsefInvoiceReader;
import com.intermanage.backend.ksef.dto.KsefInvoiceSummary;
import com.intermanage.backend.ksef.entity.KsefInvoice;
import com.intermanage.backend.ksef.repository.KsefInvoiceRepository;
import com.intermanage.backend.ksef.service.KsefInvoiceService;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Przyjęcie PZ, do którego należy faktura korygująca wczytana do tabeli: z niego makro korekty bierze numer bonu,
 * a przy korektach podających różnice także ilości i ceny, od których liczy stan po korekcie.
 */
@Service
public class DataEntryCorrectionService {

    private static final int CANDIDATES = 30;
    /** Wyszukiwanie przegląda tyle najnowszych trafień, żeby przyjęcia dostawcy korekty znalazły się na górze. */
    private static final int SCANNED = 200;
    private static final int LOOKUP_BATCH = 500;

    private final KsefInvoiceService ksefInvoices;
    private final KsefInvoiceReader reader;
    private final KsefInvoiceRepository invoices;
    private final DocumentRepository documents;
    private final DocumentKsefInvoiceRepository ksefLinks;
    private final DocumentKsefLinkService ksefLinkService;
    private final DeliveryCorrectionService corrections;
    private final SupplierRepository suppliers;
    private final DataEntryService dataEntry;

    public DataEntryCorrectionService(KsefInvoiceService ksefInvoices, KsefInvoiceReader reader,
            KsefInvoiceRepository invoices, DocumentRepository documents, DocumentKsefInvoiceRepository ksefLinks,
            DocumentKsefLinkService ksefLinkService, DeliveryCorrectionService corrections,
            SupplierRepository suppliers, DataEntryService dataEntry) {
        this.ksefInvoices = ksefInvoices;
        this.reader = reader;
        this.invoices = invoices;
        this.documents = documents;
        this.ksefLinks = ksefLinks;
        this.ksefLinkService = ksefLinkService;
        this.corrections = corrections;
        this.suppliers = suppliers;
        this.dataEntry = dataEntry;
    }

    /**
     * Najpierw przyjęcia faktur korygowanych, potem pasujące do {@code query} (numer bonu, numer faktury, dostawca),
     * a bez niego ostatnie przyjęcia dostawcy korekty. Przyjęcia dostawcy korekty są przed innymi.
     */
    @Transactional
    public List<CorrectionDelivery> deliveries(UUID storeId, UUID invoiceId, String query) {
        KsefInvoiceSummary invoice = ksefInvoices.summary(storeId, invoiceId);
        List<Supplier> listed = suppliers.findImported(storeId);
        Set<String> sellerCodes = listed.stream()
                .filter(supplier -> Objects.equals(supplier.getNip(), invoice.sellerNip()))
                .map(Supplier::getCode).collect(Collectors.toSet());

        Map<UUID, CorrectionDelivery> result = new LinkedHashMap<>();
        matched(storeId, invoiceId, invoice.sellerNip(), listed, sellerCodes)
                .forEach(document -> result.put(document.getId(), delivery(document, true)));

        List<String> words = DocumentSearch.words(query);
        if (!words.isEmpty() || !sellerCodes.isEmpty()) {
            Specification<Document> matching = DocumentSearch.inStoreMatching(storeId, DocumentType.PZ, words)
                    .and(DocumentSearch.withoutZeroDeliveries());
            if (words.isEmpty()) {
                matching = matching.and((root, criteria, cb) -> root.get("supplierCode").in(sellerCodes));
            }
            documents.findAll(matching, PageRequest.of(0, SCANNED, Sort.by(Sort.Order.desc("documentDate"))))
                    .getContent().stream()
                    .sorted(Comparator.comparing(document -> !sellerCodes.contains(document.getSupplierCode())))
                    .limit(CANDIDATES)
                    .forEach(document -> result.putIfAbsent(document.getId(), delivery(document, false)));
        }
        return List.copyOf(result.values());
    }

    /**
     * Linie przyjęcia po przypiętych korektach, z artykułami z kartoteki. Korekta od dostawcy z tą fakturą nie wchodzi
     * do stanu, bo to ją właśnie wprowadzamy: raport przyniósł ją wcześniej, a różnica policzyłaby się dwa razy.
     */
    @Transactional
    public List<CorrectionDeliveryLine> lines(UUID storeId, UUID invoiceId, UUID documentId) {
        ksefInvoices.summary(storeId, invoiceId);
        Document delivery = documents.findWithLines(storeId, DocumentType.PZ, documentId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono przyjęcia"));
        Set<UUID> thisCorrection = ksefLinks.findActiveByInvoiceIds(storeId, List.of(invoiceId)).stream()
                .map(link -> link.getDocument().getId()).collect(Collectors.toSet());
        List<DeliveryCorrection> attached = corrections.correctionsOf(List.of(delivery))
                .getOrDefault(delivery.getId(), List.of()).stream()
                .filter(link -> !thisCorrection.contains(link.getCorrection().getId())).toList();
        List<CorrectedDelivery.CorrectedLine> lines = corrections
                .corrected(delivery, corrections.correctionDocuments(storeId, attached)).lines();

        Map<String, CatalogProduct> byEan = products(storeId, lines.stream()
                .map(CorrectedDelivery.CorrectedLine::ean).toList(), false);
        Map<String, CatalogProduct> byItm8 = products(storeId, lines.stream()
                .filter(line -> !byEan.containsKey(DataEntryService.key(line.ean())))
                .map(CorrectedDelivery.CorrectedLine::itm8).toList(), true);
        return lines.stream().map(line -> {
            CatalogProduct product = byEan.get(DataEntryService.key(line.ean()));
            if (product == null) {
                product = byItm8.get(DataEntryService.key(line.itm8()));
            }
            return new CorrectionDeliveryLine(line.itm8(), line.ean(), line.name(), line.brand(), line.quantity(),
                    line.purchasePrice(), product != null ? product.itm8() : null,
                    product != null ? product.catalogEan() : null);
        }).toList();
    }

    /** Przyjęcia faktur korygowanych: z numerem faktury jako nr dok. dostawcy albo z przypiętą fakturą. */
    private List<Document> matched(UUID storeId, UUID invoiceId, String sellerNip, List<Supplier> listed,
            Set<String> sellerCodes) {
        List<KsefInvoiceReader.CorrectedInvoice> corrected;
        try {
            corrected = reader.correctedInvoices(ksefInvoices.xml(storeId, invoiceId).content());
        } catch (KsefInvoiceReader.InvalidInvoiceException e) {
            throw ApiException.badRequest("Nie udało się odczytać faktury z KSeF: " + e.getMessage());
        }
        List<String> numbers = corrected.stream().map(KsefInvoiceReader.CorrectedInvoice::number)
                .filter(Objects::nonNull).map(number -> number.toUpperCase(Locale.ROOT)).distinct().toList();
        List<String> ksefNumbers = corrected.stream().map(KsefInvoiceReader.CorrectedInvoice::ksefNumber)
                .filter(Objects::nonNull).distinct().toList();

        Map<UUID, Document> found = new LinkedHashMap<>();
        if (!numbers.isEmpty()) {
            // Numer faktury nie jest unikalny między dostawcami; dokument dostawcy spoza listy zostaje.
            Set<String> otherSuppliers = listed.stream()
                    .filter(supplier -> supplier.getNip() != null && !sellerCodes.contains(supplier.getCode()))
                    .map(Supplier::getCode).collect(Collectors.toSet());
            documents.findDeliveriesBySupplierDocument(storeId, numbers).stream()
                    .filter(document -> !otherSuppliers.contains(document.getSupplierCode()))
                    .forEach(document -> found.put(document.getId(), document));
        }
        String nip = ksefLinkService.nip(storeId);
        if (nip != null) {
            List<KsefInvoice> originals = new ArrayList<>();
            if (!ksefNumbers.isEmpty()) {
                originals.addAll(invoices.findByKsefNumbers(nip, ksefNumbers));
            }
            if (!numbers.isEmpty()) {
                invoices.findByInvoiceNumbers(nip, numbers).stream()
                        .filter(original -> Objects.equals(original.getSellerNip(), sellerNip))
                        .forEach(originals::add);
            }
            if (!originals.isEmpty()) {
                ksefLinks.findActiveByInvoiceIds(storeId, originals.stream().map(KsefInvoice::getId).distinct().toList())
                        .stream().map(DocumentKsefInvoice::getDocument)
                        .filter(document -> document.getType() == DocumentType.PZ)
                        .forEach(document -> found.put(document.getId(), document));
            }
        }
        return found.values().stream().sorted(Comparator.comparing(Document::getDocumentDate).reversed()).toList();
    }

    /** Artykuły dla kodów, kluczem jest kod bez zer wiodących ({@code lookup} bierze najwyżej 500 kodów naraz). */
    private Map<String, CatalogProduct> products(UUID storeId, List<String> codes, boolean byItm8) {
        List<String> distinct = codes.stream().filter(Objects::nonNull).distinct().toList();
        Map<String, CatalogProduct> found = new HashMap<>();
        for (int from = 0; from < distinct.size(); from += LOOKUP_BATCH) {
            dataEntry.lookup(storeId, distinct.subList(from, Math.min(from + LOOKUP_BATCH, distinct.size())), byItm8)
                    .forEach(product -> found.put(product.ean(), product));
        }
        return found;
    }

    private static CorrectionDelivery delivery(Document document, boolean matched) {
        return new CorrectionDelivery(document.getId(), document.getNumber(), document.getDocumentDate(),
                document.getSupplierName(), document.getSupplierDocumentNumber(), document.getPurchaseValue(), matched);
    }
}
