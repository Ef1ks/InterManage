package com.intermanage.backend.bakery.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.bakery.component.BakeryScanReader;
import com.intermanage.backend.bakery.component.DeliveryNotes;
import com.intermanage.backend.bakery.dto.BakeryArticle;
import com.intermanage.backend.bakery.dto.BakeryBon;
import com.intermanage.backend.bakery.dto.BakeryCheckRequest;
import com.intermanage.backend.bakery.dto.BakeryCheckResponse;
import com.intermanage.backend.bakery.dto.BakeryCheckSummary;
import com.intermanage.backend.bakery.dto.BakeryInvoice;
import com.intermanage.backend.bakery.dto.BakeryInvoiceCandidate;
import com.intermanage.backend.bakery.dto.BakeryScanReading;
import com.intermanage.backend.bakery.dto.BakeryWzRequest;
import com.intermanage.backend.bakery.dto.BakeryWzResponse;
import com.intermanage.backend.bakery.entity.BakeryCheck;
import com.intermanage.backend.bakery.entity.BakeryCheckDocument;
import com.intermanage.backend.bakery.entity.BakeryCheckInvoice;
import com.intermanage.backend.bakery.entity.BakeryWz;
import com.intermanage.backend.bakery.entity.BakeryWzLine;
import com.intermanage.backend.bakery.entity.BakeryWzScan;
import com.intermanage.backend.bakery.mapper.BakeryMapper;
import com.intermanage.backend.bakery.repository.BakeryCatalogRepository;
import com.intermanage.backend.bakery.repository.BakeryCheckDocumentRepository;
import com.intermanage.backend.bakery.repository.BakeryCheckInvoiceRepository;
import com.intermanage.backend.bakery.repository.BakeryCheckRepository;
import com.intermanage.backend.bakery.repository.BakeryWzRepository;
import com.intermanage.backend.bakery.repository.BakeryWzScanRepository;
import com.intermanage.backend.common.component.ScanFiles;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.service.DataEntryLinkService;
import com.intermanage.backend.document.dto.CorrectedDelivery;
import com.intermanage.backend.document.dto.CorrectionTotals;
import com.intermanage.backend.document.dto.LinkedKsefInvoice;
import com.intermanage.backend.document.entity.DeliveryCorrection;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.document.service.DeliveryCorrectionService;
import com.intermanage.backend.document.service.DeliveryKsefCorrectionService;
import com.intermanage.backend.document.service.DocumentKsefLinkService;
import com.intermanage.backend.document.service.DocumentService;
import com.intermanage.backend.ksef.component.KsefInvoiceReader;
import com.intermanage.backend.ksef.dto.KsefInvoiceSummary;
import com.intermanage.backend.ksef.entity.KsefInvoice;
import com.intermanage.backend.ksef.repository.KsefInvoiceRepository;
import com.intermanage.backend.ksef.repository.KsefInvoiceSearch;
import com.intermanage.backend.ksef.service.KsefInvoiceService;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.product.repository.ProductRepository;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Weryfikacja piekarni: faktura zbiorcza obejmuje kilka WZ, więc pracownik wpisuje ilości z każdego papierowego WZ,
 * a {@link BakeryComparisonService} porównuje ich sumę z fakturą z korektami i przyjęciem po korektach. Punktem wyjścia
 * jest bon: jego faktura ma w polach WZ numery dokumentów, z których powstają kafelki. Bez bonu (jeszcze nie ma go
 * w raporcie) przypina się samą fakturę, a kafelki da się też dodawać ręcznie.
 */
@Service
public class BakeryCheckService {

    /** Bonów i faktur do wyboru szukamy tyle dni wstecz. */
    static final int LOOKBACK_DAYS = 120;
    private static final int LIST_SIZE = 50;

    private final BakeryCheckRepository checks;
    private final BakeryCheckDocumentRepository checkDocuments;
    private final BakeryCheckInvoiceRepository checkInvoices;
    private final BakeryWzRepository wzs;
    private final BakeryWzScanRepository scans;
    private final BakeryCatalogRepository catalog;
    private final BakerySupplierArticleService supplierArticles;
    private final BakeryScanReader scanReader;
    private final BakeryMapper mapper;
    private final SupplierRepository suppliers;
    private final DocumentRepository documents;
    private final DeliveryCorrectionService corrections;
    private final DeliveryKsefCorrectionService ksefCorrections;
    private final DocumentKsefLinkService ksefLinks;
    private final KsefInvoiceService ksefInvoices;
    private final KsefInvoiceRepository invoices;
    private final KsefInvoiceReader invoiceReader;
    private final ProductRepository products;
    private final AppUserRepository users;
    private final TransactionTemplate transaction;

    public BakeryCheckService(BakeryCheckRepository checks, BakeryCheckDocumentRepository checkDocuments,
            BakeryCheckInvoiceRepository checkInvoices, BakeryWzRepository wzs, BakeryWzScanRepository scans,
            BakeryCatalogRepository catalog, BakerySupplierArticleService supplierArticles, BakeryScanReader scanReader,
            BakeryMapper mapper, SupplierRepository suppliers, DocumentRepository documents, DeliveryCorrectionService corrections,
            DeliveryKsefCorrectionService ksefCorrections, DocumentKsefLinkService ksefLinks,
            KsefInvoiceService ksefInvoices, KsefInvoiceRepository invoices, KsefInvoiceReader invoiceReader,
            ProductRepository products, AppUserRepository users, PlatformTransactionManager transactionManager) {
        this.checks = checks;
        this.checkDocuments = checkDocuments;
        this.checkInvoices = checkInvoices;
        this.wzs = wzs;
        this.scans = scans;
        this.catalog = catalog;
        this.supplierArticles = supplierArticles;
        this.scanReader = scanReader;
        this.mapper = mapper;
        this.suppliers = suppliers;
        this.documents = documents;
        this.corrections = corrections;
        this.ksefCorrections = ksefCorrections;
        this.ksefLinks = ksefLinks;
        this.ksefInvoices = ksefInvoices;
        this.invoices = invoices;
        this.invoiceReader = invoiceReader;
        this.products = products;
        this.users = users;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Przyjęcia dostawcy z ostatnich {@link #LOOKBACK_DAYS} dni, od najnowszego, bez przyjęć za 0 zł. */
    @Transactional(readOnly = true)
    public List<BakeryBon> deliveries(UUID storeId, UUID supplierId) {
        Supplier supplier = supplier(storeId, supplierId);
        if (supplier.getCode() == null) {
            return List.of();
        }
        LocalDateTime tomorrow = LocalDate.now(StoreTime.ZONE).plusDays(1).atStartOfDay();
        List<Document> found = documents.findBySupplierWithLines(storeId, List.of(DocumentType.PZ), supplier.getCode(),
                        tomorrow.minusDays(LOOKBACK_DAYS), tomorrow).stream()
                .filter(document -> document.getPurchaseValue().signum() > 0)
                .sorted(Comparator.comparing(Document::getDocumentDate).reversed())
                .toList();
        return bons(storeId, found);
    }

    private List<BakeryBon> bons(UUID storeId, List<Document> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<DeliveryCorrection>> correctionsOf = corrections.correctionsOf(found);
        Map<UUID, List<LinkedKsefInvoice>> invoicesOf = ksefLinks.invoicesOf(storeId, found);
        Map<UUID, UUID> checkOf = checks.findActiveByDocuments(found.stream().map(Document::getId).toList()).stream()
                .collect(Collectors.toMap(link -> link.getDocument().getId(), link -> link.getCheck().getId(),
                        (first, second) -> first));
        return found.stream().map(document -> {
            CorrectionTotals totals = corrections.totals(document, correctionsOf.get(document.getId()));
            return new BakeryBon(document.getId(), document.getNumber(), document.bonNumber(),
                    document.getDocumentDate(), document.getSupplierDocumentNumber(), document.getPurchaseValue(),
                    totals != null ? totals.purchaseValue() : null, totals != null ? totals.count() : 0,
                    invoicesOf.getOrDefault(document.getId(), List.of()).stream()
                            .map(LinkedKsefInvoice::invoiceNumber).toList(),
                    checkOf.get(document.getId()));
        }).toList();
    }

    /** Ostatnie weryfikacje dostawcy, od najnowszej. */
    @Transactional(readOnly = true)
    public List<BakeryCheckSummary> list(UUID storeId, UUID supplierId) {
        List<BakeryCheck> found = checks.findBySupplier(storeId, supplierId, Limit.of(LIST_SIZE));
        if (found.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = found.stream().map(BakeryCheck::getId).toList();
        Map<UUID, List<String>> bons = checks.findDocuments(ids).stream()
                .collect(Collectors.groupingBy(link -> link.getCheck().getId(),
                        Collectors.mapping(link -> link.getDocument().bonNumber(), Collectors.toList())));
        Map<UUID, List<String>> numbers = checks.findInvoices(ids).stream()
                .collect(Collectors.groupingBy(link -> link.getCheck().getId(),
                        Collectors.mapping(link -> link.getInvoice().getInvoiceNumber(), Collectors.toList())));
        Map<UUID, List<BakeryWz>> notes = wzs.findWithLines(ids).stream()
                .collect(Collectors.groupingBy(wz -> wz.getCheck().getId()));
        return found.stream().map(check -> {
            List<BakeryWz> wz = notes.getOrDefault(check.getId(), List.of());
            List<LocalDate> dates = wz.stream().map(BakeryWz::getDeliveryDate).filter(Objects::nonNull).sorted().toList();
            return new BakeryCheckSummary(check.getId(), check.getCreatedAt(),
                    check.getCreatedBy() != null ? check.getCreatedBy().getDisplayName() : null,
                    bons.getOrDefault(check.getId(), List.of()), numbers.getOrDefault(check.getId(), List.of()),
                    wz.size(), (int) wz.stream().filter(BakeryCheckService::filled).count(),
                    dates.isEmpty() ? null : dates.getFirst(), dates.isEmpty() ? null : dates.getLast());
        }).toList();
    }

    private static boolean filled(BakeryWz wz) {
        return wz.getLines().stream().anyMatch(line -> line.getQuantity() != null);
    }

    @Transactional(readOnly = true)
    public BakeryCheckResponse get(UUID storeId, UUID id) {
        return response(storeId, check(storeId, id), List.of());
    }

    /**
     * Nowa weryfikacja. Bon przypięty już do innej weryfikacji otwiera ją zamiast tworzyć drugą, bo te same WZ wpisane
     * dwa razy dawałyby dwie różne odpowiedzi na jedno pytanie.
     */
    public BakeryCheckResponse create(UUID storeId, BakeryCheckRequest request, UUID userId) {
        UUID existing = request.documentId() == null ? null : transaction.execute(status -> {
            bon(storeId, request.documentId());
            return activeCheckOf(request.documentId());
        });
        if (existing != null) {
            return read(storeId, existing);
        }
        UUID id = transaction.execute(status -> {
            Supplier supplier = supplier(storeId, request.supplierId());
            return checks.save(new BakeryCheck(storeId, supplier, users.getReferenceById(userId))).getId();
        });
        return request.documentId() == null ? read(storeId, id)
                : attachDocument(storeId, id, request.documentId(), userId);
    }

    private BakeryCheckResponse read(UUID storeId, UUID id) {
        return transaction.execute(status -> response(storeId, check(storeId, id), List.of()));
    }

    private UUID activeCheckOf(UUID documentId) {
        return checks.findActiveByDocuments(List.of(documentId)).stream().findFirst()
                .map(link -> link.getCheck().getId()).orElse(null);
    }

    @Transactional
    public void delete(UUID storeId, UUID id, UUID userId) {
        BakeryCheck check = check(storeId, id);
        if (!check.isDeleted()) {
            check.delete(users.getReferenceById(userId));
        }
    }

    @Transactional
    public BakeryCheckResponse restore(UUID storeId, UUID id) {
        BakeryCheck check = find(storeId, id);
        check.restore();
        return response(storeId, check, List.of());
    }

    /** Przypina bon i jego faktury; każda faktura dokłada kafelki z numerami swoich WZ. */
    public BakeryCheckResponse attachDocument(UUID storeId, UUID id, UUID documentId, UUID userId) {
        List<UUID> invoiceIds = transaction.execute(status -> {
            BakeryCheck check = check(storeId, id);
            Document document = bon(storeId, documentId);
            UUID other = activeCheckOf(documentId);
            if (other != null && !other.equals(id)) {
                throw ApiException.conflict("Bon " + document.bonNumber() + " jest już w innej weryfikacji");
            }
            if (other == null) {
                checkDocuments.saveAndFlush(new BakeryCheckDocument(check, document, users.getReferenceById(userId)));
            }
            return ksefLinks.invoicesOf(storeId, List.of(document)).getOrDefault(documentId, List.of()).stream()
                    .filter(invoice -> !invoice.correction()).map(LinkedKsefInvoice::id).toList();
        });
        return attachInvoices(storeId, id, invoiceIds, userId);
    }

    @Transactional
    public void detachDocument(UUID storeId, UUID id, UUID documentId, UUID userId) {
        check(storeId, id);
        checks.findDocuments(List.of(id)).stream().filter(link -> link.getDocument().getId().equals(documentId))
                .forEach(link -> link.remove(users.getReferenceById(userId)));
    }

    public BakeryCheckResponse attachInvoice(UUID storeId, UUID id, UUID invoiceId, UUID userId) {
        return attachInvoices(storeId, id, List.of(invoiceId), userId);
    }

    @Transactional
    public void detachInvoice(UUID storeId, UUID id, UUID invoiceId, UUID userId) {
        check(storeId, id);
        checks.findInvoices(List.of(id)).stream().filter(link -> link.getInvoice().getId().equals(invoiceId))
                .forEach(link -> link.remove(users.getReferenceById(userId)));
    }

    /** Ponowny odczyt numerów WZ z przypiętych faktur (np. gdy KSeF nie oddał XML przy przypinaniu). */
    public BakeryCheckResponse syncWz(UUID storeId, UUID id, UUID userId) {
        List<UUID> invoiceIds = transaction.execute(status -> {
            check(storeId, id);
            return checks.findInvoices(List.of(id)).stream().map(link -> link.getInvoice().getId()).toList();
        });
        return attachInvoices(storeId, id, invoiceIds, userId);
    }

    private record InvoiceNotes(UUID invoiceId, List<DeliveryNotes.Note> notes) {
    }

    /**
     * Przypina faktury i dokłada kafelki WZ, których jeszcze nie ma. XML idzie poza transakcją, bo przy pierwszym
     * odczycie pobiera się z KSeF w limicie: niedostępny nie cofa przypięcia, tylko trafia do {@code notices}.
     */
    private BakeryCheckResponse attachInvoices(UUID storeId, UUID id, List<UUID> invoiceIds, UUID userId) {
        List<String> notices = new ArrayList<>();
        List<InvoiceNotes> read = new ArrayList<>();
        for (UUID invoiceId : invoiceIds) {
            KsefInvoiceSummary summary = ksefInvoices.summary(storeId, invoiceId);
            if (summary.correction()) {
                continue;
            }
            try {
                byte[] xml = ksefInvoices.xml(storeId, invoiceId).content();
                KsefInvoiceReader.Invoice invoice = invoiceReader.read(xml);
                read.add(new InvoiceNotes(invoiceId, DeliveryNotes.parse(invoiceReader.deliveryNotes(xml),
                        invoice.periodFrom(), invoice.periodTo())));
            } catch (ApiException e) {
                notices.add("Numery WZ z faktury " + summary.invoiceNumber() + " będą później: " + e.getMessage());
            } catch (KsefInvoiceReader.InvalidInvoiceException e) {
                notices.add("Nie udało się odczytać faktury " + summary.invoiceNumber() + ": " + e.getMessage());
            }
        }
        return transaction.execute(status -> {
            BakeryCheck check = check(storeId, id);
            AppUser user = users.getReferenceById(userId);
            Set<UUID> pinned = checks.findInvoices(List.of(id)).stream().map(link -> link.getInvoice().getId())
                    .collect(Collectors.toSet());
            for (UUID invoiceId : invoiceIds) {
                if (pinned.add(invoiceId)) {
                    checkInvoices.save(new BakeryCheckInvoice(check, invoices.getReferenceById(invoiceId), user));
                }
            }
            Set<String> known = new HashSet<>(wzs.findNumbers(id));
            int position = wzs.lastPosition(id);
            for (InvoiceNotes notes : read) {
                for (DeliveryNotes.Note note : notes.notes()) {
                    if (known.add(note.number())) {
                        wzs.save(new BakeryWz(check, ++position, note.number(), note.date(), note.estimated(),
                                invoices.getReferenceById(notes.invoiceId()), user));
                    }
                }
            }
            checks.flush();
            return response(storeId, check, notices);
        });
    }

    /** Faktury i korekty dostawcy do przypięcia, od najnowszej. */
    @Transactional(readOnly = true)
    public List<BakeryInvoiceCandidate> invoiceCandidates(UUID storeId, UUID id, String query) {
        Supplier supplier = check(storeId, id).getSupplier();
        String nip = DataEntryLinkService.digits(supplier.getNip());
        String words = (nip + " " + (query == null ? "" : query)).strip();
        LocalDate today = LocalDate.now(StoreTime.ZONE);
        return ksefInvoices.list(storeId, 0, new KsefInvoiceSearch.Filter(words, today.minusDays(LOOKBACK_DAYS), today,
                        KsefInvoiceSearch.Entered.ALL, KsefInvoiceSearch.Kind.ALL, null, true), "issueDate", "desc")
                .invoices().stream()
                .map(invoice -> new BakeryInvoiceCandidate(invoice.id(), invoice.invoiceNumber(), invoice.issueDate(),
                        invoice.netAmount(), invoice.correction(), invoice.correctedInvoices()))
                .toList();
    }

    /** Artykuł z kartoteki po ITM8: pozycja dopisana do WZ z listy towarów. */
    @Transactional(readOnly = true)
    public BakeryArticle article(UUID storeId, String itm8) {
        return products.findByStoreIdAndItm8(storeId, itm8).map(mapper::toArticle)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono artykułu w kartotece"));
    }

    @Transactional
    public BakeryWzResponse addWz(UUID storeId, UUID id, UUID userId) {
        BakeryCheck check = check(storeId, id);
        BakeryWz wz = new BakeryWz(check, wzs.lastPosition(id) + 1, null, null, false, null,
                users.getReferenceById(userId));
        return mapper.toResponse(wzs.saveAndFlush(wz));
    }

    /** Zapis ilości kafelka; idzie sam, zaraz po wpisaniu, więc znane wiersze są aktualizowane w miejscu. */
    @Transactional
    public BakeryWzResponse updateWz(UUID storeId, UUID id, UUID wzId, BakeryWzRequest request) {
        BakeryWz wz = wz(storeId, id, wzId);
        if (wz.isDeleted()) {
            throw ApiException.conflict("Ten dokument WZ jest usunięty");
        }
        wz.describe(blankToNull(request.number()), request.deliveryDate());
        List<BakeryWzRequest.Line> kept = request.lines().stream()
                .filter(line -> line.manual() || (line.quantity() != null && line.quantity().signum() != 0))
                .toList();
        Set<UUID> productIds = kept.stream().map(BakeryWzRequest.Line::productId).collect(Collectors.toSet());
        if (productIds.size() != kept.size()) {
            throw ApiException.badRequest("Artykuł może być w WZ tylko raz");
        }
        Map<UUID, Product> found = productIds.isEmpty() ? Map.of()
                : catalog.findInStore(storeId, productIds).stream()
                        .collect(Collectors.toMap(Product::getId, Function.identity()));
        if (found.size() != productIds.size()) {
            throw ApiException.badRequest("Nie znaleziono artykułu w kartotece sklepu");
        }
        wz.getLines().removeIf(line -> !productIds.contains(line.getProduct().getId()));
        Map<UUID, BakeryWzLine> current = wz.getLines().stream()
                .collect(Collectors.toMap(line -> line.getProduct().getId(), Function.identity()));
        for (BakeryWzRequest.Line line : kept) {
            BakeryWzLine target = current.get(line.productId());
            if (target == null) {
                target = new BakeryWzLine(wz, found.get(line.productId()));
                wz.getLines().add(target);
            }
            target.set(line.quantity(), line.scannedQuantity(), line.manual());
        }
        return mapper.toResponse(wzs.saveAndFlush(wz));
    }

    @Transactional
    public void deleteWz(UUID storeId, UUID id, UUID wzId, UUID userId) {
        BakeryWz wz = wz(storeId, id, wzId);
        if (!wz.isDeleted()) {
            wz.delete(users.getReferenceById(userId));
        }
    }

    @Transactional
    public BakeryWzResponse restoreWz(UUID storeId, UUID id, UUID wzId) {
        BakeryWz wz = wz(storeId, id, wzId);
        wz.restore();
        return mapper.toResponse(wz);
    }

    /** Skan WZ; poprzedni zostaje w bazie, a kafelek wskazuje nowy. */
    @Transactional
    public BakeryWzResponse attachScan(UUID storeId, UUID id, UUID wzId, MultipartFile file, UUID userId) {
        BakeryWz wz = wz(storeId, id, wzId);
        byte[] content = ScanFiles.content(file);
        String contentType = ScanFiles.contentType(content);
        if (contentType == null) {
            throw ApiException.badRequest("Wgraj zdjęcie (JPG, PNG, WEBP) albo PDF dokumentu");
        }
        wz.setScan(scans.save(new BakeryWzScan(storeId, ScanFiles.fileName(file, contentType), contentType, content,
                users.getReferenceById(userId))));
        return mapper.toResponse(wzs.saveAndFlush(wz));
    }

    /** Odpięcie skanu: plik zostaje, „Przywróć” to {@link #restoreScan}. */
    @Transactional
    public void detachScan(UUID storeId, UUID id, UUID wzId) {
        wz(storeId, id, wzId).setScan(null);
    }

    @Transactional
    public BakeryWzResponse restoreScan(UUID storeId, UUID id, UUID wzId, UUID scanId) {
        BakeryWz wz = wz(storeId, id, wzId);
        wz.setScan(scans.findByIdAndStoreId(scanId, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono skanu")));
        return mapper.toResponse(wzs.saveAndFlush(wz));
    }

    @Transactional(readOnly = true)
    public DocumentService.ExportFile scanFile(UUID storeId, UUID id, UUID wzId) {
        BakeryWzScan scan = wz(storeId, id, wzId).getScan();
        if (scan == null) {
            throw ApiException.notFound("Ten dokument WZ nie ma skanu");
        }
        return new DocumentService.ExportFile(scan.getFileName(), scan.getContentType(), scan.getContent());
    }

    private record ScanInput(byte[] content, String contentType, List<BakeryArticle> articles) {
    }

    /** Odczyt przypiętego skanu przez model, poza transakcją (kilka sekund); wynik idzie do tabeli, nie do bazy. */
    public BakeryScanReading readScan(UUID storeId, UUID id, UUID wzId) {
        ScanInput input = transaction.execute(status -> {
            BakeryWz wz = wz(storeId, id, wzId);
            if (wz.getScan() == null) {
                throw ApiException.badRequest("Najpierw wgraj skan tego dokumentu WZ");
            }
            return new ScanInput(wz.getScan().getContent(), wz.getScan().getContentType(),
                    articles(storeId, wz.getCheck()));
        });
        BakeryScanReader.Reading reading;
        try {
            reading = scanReader.read(input.content(), input.contentType(), input.articles().stream()
                    .map(article -> article.name() + (article.ean() != null ? " (EAN " + article.ean() + ")" : ""))
                    .toList());
        } catch (BakeryScanReader.Unavailable e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Nie udało się odczytać skanu: " + e.getMessage());
        }
        List<BakeryScanReading.Line> lines = reading.lines().stream().map(line -> {
            int no = line.articleNo();
            UUID productId = no >= 1 && no <= input.articles().size() ? input.articles().get(no - 1).productId() : null;
            return new BakeryScanReading.Line(line.name(), line.quantity(), line.note(), productId);
        }).toList();
        return new BakeryScanReading(reading.documentNumber(), reading.deliveryDate(), lines);
    }

    private BakeryCheckResponse response(UUID storeId, BakeryCheck check, List<String> notices) {
        Supplier supplier = check.getSupplier();
        List<Document> pinned = checks.findDocuments(List.of(check.getId())).stream()
                .map(BakeryCheckDocument::getDocument).toList();
        return new BakeryCheckResponse(check.getId(), supplier.getId(), supplier.getName(), supplier.getNip(),
                bons(storeId, pinned), invoices(storeId, check),
                wzs.findWithLines(List.of(check.getId())).stream().map(mapper::toResponse).toList(),
                articles(storeId, check), scanReader.available(), notices);
    }

    /** Przypięte faktury i ich korekty, które dołączają się same. */
    private List<BakeryInvoice> invoices(UUID storeId, BakeryCheck check) {
        Map<UUID, BakeryInvoice> result = new LinkedHashMap<>();
        List<KsefInvoice> pinned = checks.findInvoices(List.of(check.getId())).stream()
                .map(BakeryCheckInvoice::getInvoice).toList();
        pinned.forEach(invoice -> result.put(invoice.getId(), invoice(invoice, true)));
        ksefCorrections.correctionsOfInvoices(storeId, pinned.stream().map(KsefInvoice::getId).toList())
                .forEach(invoice -> result.putIfAbsent(invoice.getId(), invoice(invoice, false)));
        return List.copyOf(result.values());
    }

    private static BakeryInvoice invoice(KsefInvoice invoice, boolean pinned) {
        return new BakeryInvoice(invoice.getId(), invoice.getInvoiceNumber(), invoice.getIssueDate(),
                invoice.getNetAmount(), invoice.isCorrection(), pinned);
    }

    /**
     * Artykuły dostawcy z kartoteki bez odznaczonych i artykuły z przypiętych przyjęć, po nazwie. Przyjęcie ma
     * pierwszeństwo przed odznaczeniem, bo pokazuje, że towar przyjechał (a kartoteka bywa nieaktualna).
     */
    private List<BakeryArticle> articles(UUID storeId, BakeryCheck check) {
        Map<UUID, Product> found = new HashMap<>();
        supplierArticles.shownInCatalog(storeId, check.getSupplier())
                .forEach(product -> found.put(product.getId(), product));
        List<Document> pinned = checks.findDocuments(List.of(check.getId())).stream()
                .map(BakeryCheckDocument::getDocument).toList();
        if (!pinned.isEmpty()) {
            Set<String> itm8s = new HashSet<>();
            for (Document document : pinned) {
                corrected(storeId, document).lines().forEach(line -> itm8s.add(line.itm8()));
            }
            products.findByStoreIdAndItm8In(storeId, itm8s).forEach(product -> found.put(product.getId(), product));
        }
        return found.values().stream()
                .sorted(Comparator.comparing(Product::getName, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(mapper::toArticle).toList();
    }

    /** Przyjęcie po korektach ({@code delivery} może być bez wczytanych linii). */
    public CorrectedDelivery corrected(UUID storeId, Document delivery) {
        Document withLines = documents.findWithLines(storeId, DocumentType.PZ, delivery.getId())
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono przyjęcia"));
        List<DeliveryCorrection> links = corrections.correctionsOf(List.of(withLines))
                .getOrDefault(withLines.getId(), List.of());
        return corrections.corrected(withLines, corrections.correctionDocuments(storeId, links));
    }

    private Supplier supplier(UUID storeId, UUID id) {
        return suppliers.findInStore(storeId, id)
                .orElseThrow(() -> ApiException.badRequest("Wybierz dostawcę z listy dostawców sklepu"));
    }

    private Document bon(UUID storeId, UUID documentId) {
        return documents.findHeader(storeId, DocumentType.PZ, documentId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono przyjęcia"));
    }

    private BakeryCheck find(UUID storeId, UUID id) {
        return checks.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono weryfikacji"));
    }

    private BakeryCheck check(UUID storeId, UUID id) {
        BakeryCheck check = find(storeId, id);
        if (check.isDeleted()) {
            throw ApiException.notFound("Ta weryfikacja jest usunięta");
        }
        return check;
    }

    private BakeryWz wz(UUID storeId, UUID id, UUID wzId) {
        check(storeId, id);
        return wzs.findInCheck(id, wzId).orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu WZ"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
