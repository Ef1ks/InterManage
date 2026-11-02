package com.intermanage.backend.meat.service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.service.DataEntryLinkService;
import com.intermanage.backend.document.service.DocumentService;
import com.intermanage.backend.meat.component.MeatScanReader;
import com.intermanage.backend.meat.dto.MeatArticle;
import com.intermanage.backend.meat.dto.MeatDeliveryRequest;
import com.intermanage.backend.meat.dto.MeatDeliveryResponse;
import com.intermanage.backend.meat.dto.MeatDeliverySummary;
import com.intermanage.backend.meat.dto.MeatScanResult;
import com.intermanage.backend.meat.entity.MeatDelivery;
import com.intermanage.backend.meat.entity.MeatDeliveryLine;
import com.intermanage.backend.meat.entity.MeatDeliveryScan;
import com.intermanage.backend.meat.entity.MeatProductLink;
import com.intermanage.backend.meat.entity.MeatSupplierMark;
import com.intermanage.backend.meat.entity.MeatSupplierMarkKind;
import com.intermanage.backend.meat.mapper.MeatMapper;
import com.intermanage.backend.meat.repository.MeatCatalogRepository;
import com.intermanage.backend.meat.repository.MeatDeliveryRepository;
import com.intermanage.backend.meat.repository.MeatDeliveryScanRepository;
import com.intermanage.backend.meat.repository.MeatProductLinkRepository;
import com.intermanage.backend.meat.repository.MeatSupplierMarkRepository;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.store.repository.StoreRepository;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Dokumenty WZ dostawców mięsa: odczyt skanu przez model (nic poza skanem nie zapisuje), zapis po sprawdzeniu przez
 * pracownika i nauka z każdego zapisu: znaki dostawcy (numer weterynaryjny, BDO, NIP) i przypisania pozycji z WZ do
 * artykułów, per dostawca. Zapamiętane mają pierwszeństwo przed propozycją modelu.
 */
@Service
public class MeatDeliveryService {

    static final long MAX_FILE_BYTES = 15L * 1024 * 1024;
    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    private static final int LIST_DAYS = 366;
    /** SCA PR (fakturuje) i odbiorca TRYTOMA: nigdy nie są dostawcą mięsa. */
    private static final Set<String> NOT_SUPPLIERS = Set.of("7821977018", "7812022538");
    private static final int[] NIP_WEIGHTS = {6, 5, 7, 2, 3, 4, 5, 6, 7};

    private final MeatDeliveryRepository deliveries;
    private final MeatDeliveryScanRepository scans;
    private final MeatSupplierMarkRepository marks;
    private final MeatProductLinkRepository links;
    private final MeatCatalogRepository catalog;
    private final SupplierRepository suppliers;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final MeatScanReader reader;
    private final MeatMapper mapper;
    private final TransactionTemplate transaction;

    public MeatDeliveryService(MeatDeliveryRepository deliveries, MeatDeliveryScanRepository scans,
            MeatSupplierMarkRepository marks, MeatProductLinkRepository links, MeatCatalogRepository catalog,
            SupplierRepository suppliers, StoreRepository stores, AppUserRepository users, MeatScanReader reader,
            MeatMapper mapper, PlatformTransactionManager transactionManager) {
        this.deliveries = deliveries;
        this.scans = scans;
        this.marks = marks;
        this.links = links;
        this.catalog = catalog;
        this.suppliers = suppliers;
        this.stores = stores;
        this.users = users;
        this.reader = reader;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Mięso z kartoteki sklepu do wyboru przy pozycji. */
    @Transactional(readOnly = true)
    public List<MeatArticle> articles(UUID storeId) {
        return mapper.toArticles(catalog.findMeat(storeId));
    }

    /**
     * Zapisuje skan, odczytuje go modelem i dopasowuje dostawcę i artykuły. Model działa poza transakcją (kilka
     * sekund); dokument zapisuje dopiero {@link #create}.
     */
    public MeatScanResult scan(UUID storeId, MultipartFile file, UUID userId) {
        byte[] content = content(file);
        String contentType = contentType(content);
        if (contentType == null) {
            throw ApiException.badRequest("Wgraj zdjęcie (JPG, PNG, WEBP) albo PDF dokumentu");
        }
        Catalog lists = transaction.execute(status -> catalog(storeId));
        MeatScanReader.Reading reading;
        try {
            reading = reader.read(content, contentType, lists.articleLabels(), lists.supplierLabels());
        } catch (MeatScanReader.Unavailable e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Nie udało się odczytać dokumentu: " + e.getMessage() + ". Spróbuj ponownie za chwilę.");
        }
        return transaction.execute(status -> {
            MeatDeliveryScan scan = scans.save(new MeatDeliveryScan(storeId, fileName(file, contentType),
                    contentType, content, users.getReferenceById(userId)));
            return result(storeId, scan, reading, lists);
        });
    }

    @Transactional(readOnly = true)
    public DocumentService.ExportFile scanFile(UUID storeId, UUID scanId) {
        MeatDeliveryScan scan = scans.findByIdAndStoreId(scanId, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono skanu"));
        return new DocumentService.ExportFile(scan.getFileName(), scan.getContentType(), scan.getContent());
    }

    /** Dokumenty sklepu z okresu (data dostawy), od najnowszego. */
    @Transactional(readOnly = true)
    public List<MeatDeliverySummary> list(UUID storeId, LocalDate from, LocalDate to) {
        if (from.isAfter(to) || from.plusDays(LIST_DAYS).isBefore(to)) {
            throw ApiException.badRequest("Wybierz okres do roku");
        }
        return deliveries.findInPeriod(storeId, from, to).stream()
                .map(delivery -> mapper.toSummary(delivery, deliveries.countLines(delivery.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public MeatDeliveryResponse get(UUID storeId, UUID id) {
        return mapper.toResponse(find(storeId, id));
    }

    @Transactional
    public MeatDeliveryResponse create(UUID storeId, MeatDeliveryRequest request, UUID userId) {
        AppUser user = users.getReferenceById(userId);
        MeatDeliveryScan scan = request.scanId() == null ? null : scans.findByIdAndStoreId(request.scanId(), storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono skanu"));
        MeatDelivery delivery = new MeatDelivery(storeId, scan, user);
        apply(storeId, delivery, request, user);
        return mapper.toResponse(deliveries.save(delivery));
    }

    @Transactional
    public MeatDeliveryResponse update(UUID storeId, UUID id, MeatDeliveryRequest request, UUID userId) {
        MeatDelivery delivery = find(storeId, id);
        if (delivery.isDeleted()) {
            throw ApiException.conflict("Dokument jest usunięty. Przywróć go, żeby go zmienić.");
        }
        apply(storeId, delivery, request, users.getReferenceById(userId));
        return mapper.toResponse(deliveries.saveAndFlush(delivery));
    }

    /** Usunięcie to znacznik; „Przywróć” to {@link #restore}. */
    @Transactional
    public void delete(UUID storeId, UUID id, UUID userId) {
        MeatDelivery delivery = find(storeId, id);
        if (!delivery.isDeleted()) {
            delivery.delete(users.getReferenceById(userId));
        }
    }

    @Transactional
    public MeatDeliveryResponse restore(UUID storeId, UUID id) {
        MeatDelivery delivery = find(storeId, id);
        if (delivery.isDeleted()) {
            deliveries.findActiveDocument(storeId, delivery.getSupplier().getId(), delivery.getDocumentNumber(),
                    delivery.getId()).ifPresent(other -> {
                        throw ApiException.conflict("Ten dokument dostawcy jest już wprowadzony ponownie");
                    });
            delivery.restore();
        }
        return mapper.toResponse(delivery);
    }

    private MeatDelivery find(UUID storeId, UUID id) {
        return deliveries.findInStore(storeId, id).orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu"));
    }

    private void apply(UUID storeId, MeatDelivery delivery, MeatDeliveryRequest request, AppUser user) {
        Supplier supplier = suppliers.findInStore(storeId, request.supplierId())
                .orElseThrow(() -> ApiException.badRequest("Wybierz dostawcę z listy dostawców sklepu"));
        String number = request.documentNumber().strip();
        deliveries.findActiveDocument(storeId, supplier.getId(), number, delivery.getId()).ifPresent(other -> {
            throw ApiException.conflict("Dokument " + other.getDocumentNumber() + " od " + supplier.getName()
                    + " jest już wprowadzony").withProperty("duplicateId", other.getId());
        });
        List<UUID> productIds = request.lines().stream().map(MeatDeliveryRequest.Line::productId)
                .filter(Objects::nonNull).distinct().toList();
        Map<UUID, Product> products = productIds.isEmpty() ? Map.of()
                : catalog.findInStore(storeId, productIds).stream()
                        .collect(Collectors.toMap(Product::getId, Function.identity()));
        if (products.size() != productIds.size()) {
            throw ApiException.badRequest("Nie znaleziono artykułu w kartotece sklepu");
        }
        List<MeatDeliveryLine> lines = new ArrayList<>();
        for (MeatDeliveryRequest.Line line : request.lines()) {
            lines.add(new MeatDeliveryLine(line.documentName().strip(), blankToNull(line.documentCode()),
                    line.unitPrice(), line.productId() == null ? null : products.get(line.productId()),
                    line.corrected(), blankToNull(line.note())));
        }
        delivery.update(supplier, number, request.deliveryDate(), lines, user);
        rememberMarks(storeId, supplier, request, user);
        rememberLinks(storeId, supplier, request.lines(), products, user);
    }

    /** Znaki dostawcy z dokumentu: kolejny WZ z tym numerem weterynaryjnym, BDO albo NIP-em rozpozna się sam. */
    private void rememberMarks(UUID storeId, Supplier supplier, MeatDeliveryRequest request, AppUser user) {
        Map<MeatSupplierMarkKind, String> values = new LinkedHashMap<>();
        values.put(MeatSupplierMarkKind.VET, vetNumber(request.vetNumber()));
        values.put(MeatSupplierMarkKind.BDO, bdo(request.bdo()));
        values.put(MeatSupplierMarkKind.NIP, nip(request.nip()));
        values.forEach((kind, value) -> {
            if (value == null) {
                return;
            }
            MeatSupplierMark mark = marks.findActive(storeId, kind, value).orElse(null);
            if (mark != null && mark.getSupplier().getId().equals(supplier.getId())) {
                return;
            }
            if (mark != null) {
                mark.delete(user);
                marks.saveAndFlush(mark);
            }
            marks.save(new MeatSupplierMark(storeId, kind, value, supplier, user));
        });
    }

    /** Przypisania pozycji z WZ u tego dostawcy (także „bez artykułu”); zmiana to znacznik na poprzednim. */
    private void rememberLinks(UUID storeId, Supplier supplier, List<MeatDeliveryRequest.Line> lines,
            Map<UUID, Product> products, AppUser user) {
        Map<String, UUID> wanted = new LinkedHashMap<>();
        for (MeatDeliveryRequest.Line line : lines) {
            wanted.put(documentKey(line.documentCode(), line.documentName()), line.productId());
        }
        Map<String, MeatProductLink> current = links.findActive(storeId, supplier.getId(), wanted.keySet()).stream()
                .collect(Collectors.toMap(MeatProductLink::getDocumentKey, Function.identity()));
        wanted.forEach((key, productId) -> {
            MeatProductLink link = current.get(key);
            UUID linked = link == null || link.getProduct() == null ? null : link.getProduct().getId();
            if (link != null && Objects.equals(linked, productId)) {
                return;
            }
            if (link != null) {
                link.delete(user);
                links.saveAndFlush(link);
            }
            links.save(new MeatProductLink(storeId, supplier.getId(), key,
                    productId == null ? null : products.get(productId), user));
        });
    }

    private record Catalog(List<Product> articles, List<Supplier> suppliers) {

        List<String> articleLabels() {
            return articles.stream().map(p -> p.getName() + " | " + Objects.toString(p.getBrand(), "")).toList();
        }

        List<String> supplierLabels() {
            return suppliers.stream().map(s -> s.getName() + " | " + Objects.toString(s.getNip(), "")).toList();
        }
    }

    private Catalog catalog(UUID storeId) {
        String storeNip = stores.findById(storeId).map(store -> digits(store.getNip())).orElse("");
        List<Supplier> candidates = suppliers.findByStore(storeId).stream()
                .filter(supplier -> !NOT_SUPPLIERS.contains(digits(supplier.getNip()))
                        && !(storeNip.length() == 10 && storeNip.equals(digits(supplier.getNip()))))
                .toList();
        return new Catalog(catalog.findMeat(storeId), candidates);
    }

    private MeatScanResult result(UUID storeId, MeatDeliveryScan scan, MeatScanReader.Reading reading,
            Catalog lists) {
        SupplierMatch supplierMatch = supplier(storeId, reading, lists.suppliers());
        Supplier supplier = supplierMatch == null ? null : supplierMatch.supplier();

        Map<String, MeatProductLink> linked = new HashMap<>();
        if (supplier != null && !reading.lines().isEmpty()) {
            List<String> keys = reading.lines().stream().map(line -> documentKey(line.code(), line.name())).toList();
            links.findActive(storeId, supplier.getId(), keys)
                    .forEach(link -> linked.put(link.getDocumentKey(), link));
        }
        List<MeatScanResult.Line> lines = new ArrayList<>();
        for (MeatScanReader.Line line : reading.lines()) {
            MeatProductLink link = linked.get(documentKey(line.code(), line.name()));
            MeatArticle article = null;
            String match = "NONE";
            boolean noArticle = false;
            if (link != null) {
                match = "LINK";
                noArticle = link.getProduct() == null;
                article = noArticle ? null : mapper.toArticle(link.getProduct());
            } else if (line.articleNo() >= 1 && line.articleNo() <= lists.articles().size()) {
                match = "MODEL";
                article = mapper.toArticle(lists.articles().get(line.articleNo() - 1));
            }
            lines.add(new MeatScanResult.Line(line.name(), line.code(), line.unitNetPrice(),
                    line.handwrittenCorrection(), line.note(), article, match, noArticle));
        }

        LocalDate date = reading.shipDate() != null ? reading.shipDate()
                : reading.issueDate() != null ? reading.issueDate() : LocalDate.now(ZONE);
        UUID duplicateId = supplier == null || reading.documentNumber() == null ? null
                : deliveries.findActiveDocument(storeId, supplier.getId(), reading.documentNumber(), null)
                        .map(MeatDelivery::getId).orElse(null);
        return new MeatScanResult(scan.getId(), scan.getContentType(),
                supplier == null ? null : mapper.toSupplier(supplier),
                supplierMatch == null ? null : supplierMatch.how(), reading.supplierName(), nip(reading.supplierNip()),
                bdo(reading.supplierBdo()), vetNumber(reading.supplierVetNumber()), reading.documentNumber(), date,
                duplicateId, lines);
    }

    private record SupplierMatch(Supplier supplier, String how) {
    }

    /**
     * Dostawca z dokumentu: najpierw zapamiętane znaki (numer weterynaryjny, BDO, NIP), potem NIP z dokumentu
     * z poprawną sumą kontrolną, na końcu wybór modelu z listy.
     */
    private SupplierMatch supplier(UUID storeId, MeatScanReader.Reading reading, List<Supplier> candidates) {
        Map<MeatSupplierMarkKind, String> values = new LinkedHashMap<>();
        values.put(MeatSupplierMarkKind.VET, vetNumber(reading.supplierVetNumber()));
        values.put(MeatSupplierMarkKind.BDO, bdo(reading.supplierBdo()));
        values.put(MeatSupplierMarkKind.NIP, nip(reading.supplierNip()));
        for (Map.Entry<MeatSupplierMarkKind, String> value : values.entrySet()) {
            if (value.getValue() != null) {
                MeatSupplierMark mark = marks.findActive(storeId, value.getKey(), value.getValue()).orElse(null);
                if (mark != null) {
                    return new SupplierMatch(mark.getSupplier(), "MARK");
                }
            }
        }
        String nip = values.get(MeatSupplierMarkKind.NIP);
        if (nip != null) {
            List<Supplier> byNip = candidates.stream().filter(s -> nip.equals(digits(s.getNip()))).toList();
            if (byNip.size() == 1) {
                return new SupplierMatch(byNip.getFirst(), "NIP");
            }
        }
        int no = reading.supplierNo();
        return no >= 1 && no <= candidates.size() ? new SupplierMatch(candidates.get(no - 1), "MODEL") : null;
    }

    /** Klucz pozycji z WZ: kod z dokumentu, a bez niego nazwa (wielkie litery bez polskich znaków). */
    static String documentKey(String code, String name) {
        String normalizedCode = DataEntryLinkService.code(code);
        if (!normalizedCode.isEmpty() && !normalizedCode.matches("0+")) {
            return "KOD " + normalizedCode;
        }
        return DataEntryLinkService.code(name);
    }

    /** Numer weterynaryjny bez spacji, np. {@code PL08624302}; {@code null}, gdy nie ma cyfr. */
    static String vetNumber(String raw) {
        String value = raw == null ? "" : raw.toUpperCase().replaceAll("[^A-Z0-9]", "");
        return value.matches(".*\\d{6,}.*") && value.length() <= 50 ? value : null;
    }

    /** BDO jako liczba bez zer wiodących (dokumenty piszą je z różną liczbą zer). */
    static String bdo(String raw) {
        String value = digits(raw).replaceFirst("^0+", "");
        return value.length() >= 4 && value.length() <= 12 ? value : null;
    }

    /** NIP z poprawną cyfrą kontrolną, bez SCA PR i TRYTOMY; inaczej {@code null} (pieczątka zasłania cyfry). */
    static String nip(String raw) {
        String value = digits(raw);
        if (!value.matches("\\d{10}") || NOT_SUPPLIERS.contains(value)) {
            return null;
        }
        int sum = 0;
        for (int i = 0; i < NIP_WEIGHTS.length; i++) {
            sum += NIP_WEIGHTS[i] * (value.charAt(i) - '0');
        }
        return sum % 11 == value.charAt(9) - '0' ? value : null;
    }

    private static String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static byte[] content(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Wybierz plik ze skanem albo zdjęciem dokumentu");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw ApiException.badRequest("Plik jest za duży (najwyżej 15 MB)");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("Nie udało się odczytać pliku");
        }
    }

    /** Typ pliku z pierwszych bajtów, nie z nazwy; {@code null} = nieobsługiwany. */
    static String contentType(byte[] content) {
        if (startsWith(content, 0, 0x25, 0x50, 0x44, 0x46)) {
            return "application/pdf";
        }
        if (startsWith(content, 0, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(content, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (startsWith(content, 0, 0x52, 0x49, 0x46, 0x46) && startsWith(content, 8, 0x57, 0x45, 0x42, 0x50)) {
            return "image/webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] content, int offset, int... prefix) {
        if (content.length < offset + prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((content[offset + i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String fileName(MultipartFile file, String contentType) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        String extension = switch (contentType) {
            case "application/pdf" -> ".pdf";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> ".png";
        };
        if (name.isEmpty()) {
            name = "wz" + extension;
        }
        return name.length() > 255 ? name.substring(0, 255 - extension.length()) + extension : name;
    }
}
