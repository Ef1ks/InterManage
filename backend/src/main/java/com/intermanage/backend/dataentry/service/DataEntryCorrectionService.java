package com.intermanage.backend.dataentry.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
import com.intermanage.backend.ksef.repository.KsefInvoiceXmlRepository;
import com.intermanage.backend.ksef.service.KsefCorrectedInvoiceService;
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
    /**
     * Podpowiedzi bez przyjęcia faktury korygowanej: przyjęcia od tylu dni przed datą faktury korygowanej do tylu po niej
     * (towar z faktury z 12.06 bywa przyjęty 14.06, a bon wpisany jeszcze później).
     */
    private static final int SUGGEST_DAYS_BEFORE = 7;
    private static final int SUGGEST_DAYS_AFTER = 30;
    /** Korekta bez daty faktury korygowanej: przyjęcia z tylu dni przed korektą. */
    private static final int SUGGEST_DAYS_WITHOUT_DATE = 90;
    private static final int SUGGESTIONS = 10;
    private static final BigDecimal QUANTITY_TOLERANCE = new BigDecimal("0.0005");
    /** Korekty SCA PR dotyczą bonów bazy (538, 88110) i refaktur (16538), choć na liście dostawców NIP ma tylko 16538. */
    private static final Set<String> BASE_SUPPLIER_CODES = Set.of("538", "88110", "16538");

    private final KsefInvoiceService ksefInvoices;
    private final KsefInvoiceReader reader;
    private final KsefInvoiceRepository invoices;
    private final DocumentRepository documents;
    private final DocumentKsefInvoiceRepository ksefLinks;
    private final DocumentKsefLinkService ksefLinkService;
    private final DeliveryCorrectionService corrections;
    private final SupplierRepository suppliers;
    private final DataEntryService dataEntry;
    private final KsefInvoiceXmlRepository xmlFiles;

    public DataEntryCorrectionService(KsefInvoiceService ksefInvoices, KsefInvoiceReader reader,
            KsefInvoiceRepository invoices, DocumentRepository documents, DocumentKsefInvoiceRepository ksefLinks,
            DocumentKsefLinkService ksefLinkService, DeliveryCorrectionService corrections,
            SupplierRepository suppliers, DataEntryService dataEntry, KsefInvoiceXmlRepository xmlFiles) {
        this.xmlFiles = xmlFiles;
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
        List<Document> matched = matched(storeId, invoiceId, invoice.sellerNip(), listed, sellerCodes);
        Set<String> articles = matched.size() > 1 ? correctionArticles(storeId, invoiceId) : Set.of();
        matched.stream()
                .map(document -> delivery(document, true, articles.isEmpty() ? 0 : common(storeId, document, articles)))
                // Najpierw przyjęcie z największą liczbą artykułów korekty (faktura bazy ma kilka WZ, więc kilka PZ).
                .sorted(Comparator.comparingInt(CorrectionDelivery::commonArticles).reversed())
                .forEach(delivery -> result.put(delivery.id(), delivery));

        List<String> words = DocumentSearch.words(query);
        if (matched.isEmpty() && words.isEmpty()) {
            suggestions(storeId, invoiceId, invoice, sellerCodes)
                    .forEach(delivery -> result.putIfAbsent(delivery.id(), delivery));
        }
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
                    .forEach(document -> result.putIfAbsent(document.getId(), delivery(document, false, 0)));
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
            // Faktura zbiorcza (np. baza SCA PR) podaje dokumenty WZ, a ich numery są numerem dokumentu dostawcy w PZ.
            if (!ksefNumbers.isEmpty()) {
                originals.addAll(invoices.findByKsefNumbers(nip, ksefNumbers));
            }
            if (!numbers.isEmpty()) {
                invoices.findByInvoiceNumbers(nip, numbers).stream()
                        .filter(original -> Objects.equals(original.getSellerNip(), sellerNip))
                        .forEach(originals::add);
            }
            List<String> deliveryNotes = originals.stream().map(original -> xmlFiles.findById(original.getId()))
                    .flatMap(java.util.Optional::stream).flatMap(xml -> wzNumbers(xml.getContent()).stream())
                    .map(number -> number.toUpperCase(Locale.ROOT)).distinct().toList();
            if (!deliveryNotes.isEmpty()) {
                documents.findDeliveriesBySupplierDocument(storeId, deliveryNotes)
                        .forEach(document -> found.put(document.getId(), document));
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

    private List<String> wzNumbers(byte[] xml) {
        try {
            return reader.deliveryNotes(xml);
        } catch (KsefInvoiceReader.InvalidInvoiceException e) {
            return List.of();
        }
    }

    /** Kody artykułów korekty bez zer wiodących: GTIN i indeks (u bazy to ITM8) każdej pozycji. */
    private Set<String> correctionArticles(UUID storeId, UUID invoiceId) {
        try {
            return reader.lineCodes(ksefInvoices.xml(storeId, invoiceId).content()).stream()
                    .flatMap(line -> java.util.stream.Stream.of(line.gtin(), line.index()))
                    .map(DataEntryService::key).filter(code -> code != null && !code.isBlank())
                    .collect(Collectors.toSet());
        } catch (KsefInvoiceReader.InvalidInvoiceException e) {
            return Set.of();
        }
    }

    /** Ile artykułów korekty jest w liniach przyjęcia (po ITM8 albo EAN-ie, bez zer wiodących). */
    private int common(UUID storeId, Document document, Set<String> articles) {
        return documents.findWithLines(storeId, DocumentType.PZ, document.getId())
                .map(delivery -> (int) delivery.getLines().stream()
                        .filter(line -> articles.contains(DataEntryService.key(line.getItm8()))
                                || articles.contains(DataEntryService.key(line.getEan())))
                        .count())
                .orElse(0);
    }

    /** Artykuł korekty: jego kody bez zer wiodących i ilość z wierszy „przed korektą” ({@code null} bez nich). */
    private record CorrectionArticle(Set<String> codes, BigDecimal before) {
    }

    private record Scored(Document document, int common, int quantities, long days) {
    }

    /**
     * Przyjęcia dostawcy korekty z okolic daty faktury korygowanej, w których są artykuły korekty. Najpierw te, w których
     * ilości zgadzają się z fakturą „przed korektą”, potem z największą liczbą artykułów, potem najbliższe datą: tak
     * pracownik szukał bonu w statystykach artykułu (faktura z 12.06, artykuł w dostawie z 14.06 w tej samej ilości).
     */
    private List<CorrectionDelivery> suggestions(UUID storeId, UUID invoiceId, KsefInvoiceSummary invoice,
            Set<String> sellerCodes) {
        List<CorrectionArticle> articles = articleQuantities(storeId, invoiceId);
        if (articles.isEmpty()) {
            return List.of();
        }
        LocalDate correctedDate = correctedDate(storeId, invoiceId);
        LocalDate correctionDate = invoice.issueDate() != null ? invoice.issueDate() : LocalDate.now();
        LocalDate from = correctedDate != null ? correctedDate.minusDays(SUGGEST_DAYS_BEFORE)
                : correctionDate.minusDays(SUGGEST_DAYS_WITHOUT_DATE);
        LocalDate to = correctedDate != null ? correctedDate.plusDays(SUGGEST_DAYS_AFTER) : correctionDate;
        LocalDate anchor = correctedDate != null ? correctedDate : correctionDate;

        Set<String> codes = new HashSet<>(sellerCodes);
        if (KsefCorrectedInvoiceService.IGNORED_SELLER_NIPS.contains(invoice.sellerNip())) {
            codes.addAll(BASE_SUPPLIER_CODES);
        }
        Specification<Document> spec = DocumentSearch.inStoreMatching(storeId, DocumentType.PZ, List.of())
                .and(DocumentSearch.withoutZeroDeliveries())
                .and(DocumentSearch.dated(from.atStartOfDay(), to.plusDays(1).atStartOfDay()));
        if (!codes.isEmpty()) {
            spec = spec.and((root, criteria, cb) -> root.get("supplierCode").in(codes));
        }
        List<UUID> ids = documents.findAll(spec, PageRequest.of(0, SCANNED, Sort.by(Sort.Order.desc("documentDate"))))
                .getContent().stream().map(Document::getId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        int quantityArticles = (int) articles.stream().filter(article -> article.before() != null).count();
        List<Scored> scored = new ArrayList<>();
        for (Document document : documents.findWithLines(storeId, ids)) {
            int common = 0;
            int quantities = 0;
            for (CorrectionArticle article : articles) {
                BigDecimal quantity = null;
                for (var line : document.getLines()) {
                    if (article.codes().contains(DataEntryService.key(line.getItm8()))
                            || article.codes().contains(DataEntryService.key(line.getEan()))) {
                        BigDecimal lineQuantity = line.getQuantity() != null ? line.getQuantity() : BigDecimal.ZERO;
                        quantity = quantity == null ? lineQuantity : quantity.add(lineQuantity);
                    }
                }
                if (quantity == null) {
                    continue;
                }
                common++;
                if (article.before() != null
                        && quantity.subtract(article.before()).abs().compareTo(QUANTITY_TOLERANCE) < 0) {
                    quantities++;
                }
            }
            if (common > 0) {
                long days = Math.abs(ChronoUnit.DAYS.between(anchor, document.getDocumentDate().toLocalDate()));
                scored.add(new Scored(document, common, quantities, days));
            }
        }
        return scored.stream()
                .sorted(Comparator.comparingInt(Scored::quantities).reversed()
                        .thenComparing(Comparator.comparingInt(Scored::common).reversed())
                        .thenComparingLong(Scored::days))
                .limit(SUGGESTIONS)
                .map(item -> new CorrectionDelivery(item.document().getId(), item.document().getNumber(),
                        item.document().getDocumentDate(), item.document().getSupplierCode(),
                        item.document().getSupplierName(), item.document().getSupplierDocumentNumber(),
                        item.document().getPurchaseValue(), false, item.common(), true, item.quantities(),
                        quantityArticles))
                .toList();
    }

    /**
     * Artykuły korekty bez kaucji, z kodami (ITM8 i EAN artykułu z kartoteki, EAN i GTIN pozycji) i ilością z wierszy
     * „przed korektą”. Pozycje jednego artykułu (wspólny kod) łączą się w jeden.
     */
    private List<CorrectionArticle> articleQuantities(UUID storeId, UUID invoiceId) {
        Map<String, Set<String>> codes = new LinkedHashMap<>();
        Map<String, BigDecimal> before = new HashMap<>();
        for (DataEntryService.InvoiceLine line : dataEntry.invoiceLines(storeId, invoiceId)) {
            if (line.source().deposit()) {
                continue;
            }
            Set<String> lineCodes = new LinkedHashSet<>();
            CatalogProduct product = line.row().product();
            if (product != null) {
                lineCodes.add(DataEntryService.key(product.itm8()));
                lineCodes.add(DataEntryService.key(product.catalogEan()));
                lineCodes.add(DataEntryService.key(product.ean()));
            }
            lineCodes.add(DataEntryService.key(line.row().ean()));
            lineCodes.add(DataEntryService.key(line.source().gtin()));
            lineCodes.removeIf(code -> code == null || code.isBlank());
            if (lineCodes.isEmpty()) {
                continue;
            }
            String id = codes.entrySet().stream()
                    .filter(entry -> entry.getValue().stream().anyMatch(lineCodes::contains))
                    .map(Map.Entry::getKey).findFirst().orElse(lineCodes.iterator().next());
            codes.computeIfAbsent(id, key -> new LinkedHashSet<>()).addAll(lineCodes);
            if (line.source().beforeCorrection() && line.source().quantity() != null) {
                before.merge(id, line.source().quantity(), BigDecimal::add);
            }
        }
        return codes.entrySet().stream()
                .map(entry -> new CorrectionArticle(entry.getValue(), before.get(entry.getKey()))).toList();
    }

    /** Najwcześniejsza data wystawienia faktury korygowanej z XML korekty ({@code null} bez niej). */
    private LocalDate correctedDate(UUID storeId, UUID invoiceId) {
        try {
            return reader.correctedInvoices(ksefInvoices.xml(storeId, invoiceId).content()).stream()
                    .map(KsefInvoiceReader.CorrectedInvoice::issueDate).filter(Objects::nonNull)
                    .min(Comparator.naturalOrder()).orElse(null);
        } catch (KsefInvoiceReader.InvalidInvoiceException e) {
            return null;
        }
    }

    private static CorrectionDelivery delivery(Document document, boolean matched, int commonArticles) {
        return new CorrectionDelivery(document.getId(), document.getNumber(), document.getDocumentDate(),
                document.getSupplierCode(), document.getSupplierName(), document.getSupplierDocumentNumber(),
                document.getPurchaseValue(), matched, commonArticles, false, 0, 0);
    }
}
