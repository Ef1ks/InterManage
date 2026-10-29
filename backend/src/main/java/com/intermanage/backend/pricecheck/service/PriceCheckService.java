package com.intermanage.backend.pricecheck.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.dto.CorrectedDelivery;
import com.intermanage.backend.document.dto.CorrectedDelivery.CorrectedLine;
import com.intermanage.backend.document.entity.DeliveryCorrection;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentLine;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.repository.DocumentImportRepository;
import com.intermanage.backend.document.repository.DocumentLineRepository;
import com.intermanage.backend.document.repository.DocumentLineRepository.SupplierPrice;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.document.service.DeliveryCorrectionService;
import com.intermanage.backend.pricecheck.dto.PriceCheckReport;
import com.intermanage.backend.pricecheck.dto.PriceCheckReport.Delivery;
import com.intermanage.backend.pricecheck.dto.PriceCheckReport.Line;
import com.intermanage.backend.pricecheck.dto.PriceCheckReviewRequest;
import com.intermanage.backend.pricecheck.dto.PriceCheckReviewResponse;
import com.intermanage.backend.pricecheck.dto.PriceCheckSettingsRequest;
import com.intermanage.backend.pricecheck.dto.PriceCheckSettingsResponse;
import com.intermanage.backend.pricecheck.entity.CadencierImport;
import com.intermanage.backend.pricecheck.entity.PriceCheckReview;
import com.intermanage.backend.pricecheck.entity.PriceCheckSettings;
import com.intermanage.backend.pricecheck.entity.PriceCheckStatus;
import com.intermanage.backend.pricecheck.mapper.PriceCheckMapper;
import com.intermanage.backend.pricecheck.repository.CadencierImportRepository;
import com.intermanage.backend.pricecheck.repository.CadencierImportRepository.CadencierEan;
import com.intermanage.backend.pricecheck.repository.IncidentImportRepository;
import com.intermanage.backend.pricecheck.repository.IncidentImportRepository.IncidentItem;
import com.intermanage.backend.pricecheck.repository.PriceCheckReviewRepository;
import com.intermanage.backend.pricecheck.repository.PriceCheckSettingsRepository;
import com.intermanage.backend.product.repository.ProductRepository;
import com.intermanage.backend.product.repository.ProductRepository.CadencierPrice;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Weryfikacja zakupu bazowego poza bazą. Artykuł obecny w cadencier (formularzu zamówień do bazy, lista wgrywana
 * w Ustawieniach sklepu) powinien przychodzić z bazy (dostawcy {@link #BASE_SUPPLIER_CODES}). Przyjęcie PZ
 * z ostatnich {@value #PERIOD_DAYS} dni, w którym taki artykuł przyszedł od innego dostawcy (dostawca przy linii),
 * trafia do raportu razem z tymi artykułami. Artykuł z linii łączy się z cadencier po ITM8 albo po EAN-ie, bo dostawca
 * spoza bazy przywozi ten sam towar pod własnym ITM8. Przyjęcie ze statusem wyjaśniania zostaje w raporcie bez
 * względu na datę. Przyjęcie, w którym każdy taki artykuł był najwyżej {@value #INCIDENT_DAYS} dni wcześniej
 * w incydencie zamówienia do bazy (baza go nie dowiozła), trafia do osobnej kategorii. Minimalna marża z ustawień nie decyduje o wejściu do raportu, tylko
 * oznacza linie z marżą na bonie (po korektach, % od sprzedaży netto) poniżej niej i liczy, o ile za drogo. Raport
 * liczy się w locie, więc korekta przypięta do przyjęcia od razu go zmienia, a status wyjaśniania z dostawcą zostaje
 * przy przyjęciu.
 */
@Service
public class PriceCheckService {

    /** Kody dostawców bazy Intermarché w systemie sklepu (baza i baza alkoholi). */
    public static final List<String> BASE_SUPPLIER_CODES = List.of("538", "88110");
    /** Raport obejmuje przyjęcia z tylu ostatnich dni (z dzisiejszym). */
    public static final int PERIOD_DAYS = 30;
    /** Cenę z bazy bierzemy z dostaw najwyżej tyle dni przed przyjęciem. */
    static final int BASE_LOOKBACK_DAYS = 365;
    private static final ZoneId STORE_ZONE = ZoneId.of("Europe/Warsaw");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** Przyjęcie od innego dostawcy do tylu dni po incydencie zamówienia do bazy jest usprawiedliwione. */
    public static final int INCIDENT_DAYS = 10;
    /** Krótsze kody (po zdjęciu zer wiodących) to wewnętrzne kody sklepu, a nie EAN-y towaru: nie łączą po EAN-ie. */
    private static final int MIN_EAN_LENGTH = 8;
    /** Kolejność na liście: najpierw do wyjaśnienia, potem zgłoszone, na końcu wyjaśnione. */
    private static final Map<PriceCheckStatus, Integer> STATUS_ORDER =
            Map.of(PriceCheckStatus.REPORTED, 1, PriceCheckStatus.RESOLVED, 2);

    private final DocumentRepository documents;
    private final DocumentLineRepository documentLines;
    private final DocumentImportRepository documentImports;
    private final DeliveryCorrectionService corrections;
    private final ProductRepository products;
    private final CadencierImportRepository cadencierImports;
    private final IncidentImportRepository incidents;
    private final PriceCheckSettingsRepository settings;
    private final PriceCheckReviewRepository reviews;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final PriceCheckMapper mapper;

    public PriceCheckService(DocumentRepository documents, DocumentLineRepository documentLines,
            DocumentImportRepository documentImports, DeliveryCorrectionService corrections,
            ProductRepository products, CadencierImportRepository cadencierImports,
            IncidentImportRepository incidents, PriceCheckSettingsRepository settings, PriceCheckReviewRepository reviews, StoreRepository stores,
            AppUserRepository users, PriceCheckMapper mapper) {
        this.documents = documents;
        this.documentLines = documentLines;
        this.documentImports = documentImports;
        this.corrections = corrections;
        this.products = products;
        this.cadencierImports = cadencierImports;
        this.incidents = incidents;
        this.settings = settings;
        this.reviews = reviews;
        this.stores = stores;
        this.users = users;
        this.mapper = mapper;
    }

    /**
     * Przyjęcia z ostatnich {@value #PERIOD_DAYS} dni (wg daty dokumentu) z artykułem z cadencier od dostawcy spoza
     * bazy oraz wszystkie przyjęcia ze statusem wyjaśniania, także starsze.
     */
    @Transactional(readOnly = true)
    public PriceCheckReport report(UUID storeId) {
        LocalDate to = LocalDate.now(STORE_ZONE);
        LocalDate from = to.minusDays(PERIOD_DAYS - 1L);
        BigDecimal minMargin = settings.findByStoreId(storeId).map(PriceCheckSettings::getMinMarginPercent)
                .orElse(null);
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();

        CadencierImport cadencierImport = cadencierImports.findNewest(storeId).orElse(null);
        Cadencier cadencier = cadencierImport == null ? Cadencier.EMPTY
                : Cadencier.of(cadencierImports.findCurrentItm8s(storeId), cadencierImports.findCurrentEans(storeId));
        Map<UUID, List<PriceCheckReview>> reviewsByDelivery = reviews.findActive(storeId).stream()
                .collect(Collectors.groupingBy(review -> review.getDocument().getId(), LinkedHashMap::new,
                        Collectors.toList()));
        Map<String, BigDecimal> cadencierPrices = products.findCadencierPrices(storeId).stream()
                .collect(Collectors.toMap(CadencierPrice::getItm8, CadencierPrice::getCadencierPrice));
        List<Document> deliveries = new ArrayList<>(
                documents.findWithLinesBetween(storeId, DocumentType.PZ, start, end).stream()
                        .filter(delivery -> reviewsByDelivery.containsKey(delivery.getId())
                                || hasCadencierLineFromOtherSupplier(delivery, cadencier))
                        .toList());
        Set<UUID> inPeriod = deliveries.stream().map(Document::getId).collect(Collectors.toSet());
        List<UUID> reviewedEarlier = reviewsByDelivery.keySet().stream().filter(id -> !inPeriod.contains(id)).toList();
        if (!reviewedEarlier.isEmpty()) {
            documents.findWithLines(storeId, reviewedEarlier).stream()
                    .filter(delivery -> delivery.getType() == DocumentType.PZ)
                    .forEach(deliveries::add);
        }
        LocalDateTime oldest = deliveries.stream().map(Document::getDocumentDate).min(Comparator.naturalOrder())
                .orElse(start);
        BasePrices basePrices = BasePrices.of(documentLines.findSupplierPrices(storeId, BASE_SUPPLIER_CODES,
                oldest.toLocalDate().atStartOfDay().minusDays(BASE_LOOKBACK_DAYS), end));
        Incidents incidentIndex = Incidents.of(
                incidents.findSince(storeId, oldest.toLocalDate().minusDays(INCIDENT_DAYS)));
        Map<UUID, List<DeliveryCorrection>> links = corrections.correctionsOf(deliveries);
        Map<UUID, Document> correctionDocuments = corrections.correctionDocuments(storeId,
                        links.values().stream().flatMap(List::stream).toList())
                .stream().filter(Objects::nonNull)
                .collect(Collectors.toMap(Document::getId, Function.identity()));

        List<Delivery> result = new ArrayList<>();
        for (Document delivery : deliveries) {
            List<Document> ofDelivery = links.getOrDefault(delivery.getId(), List.of()).stream()
                    .map(link -> correctionDocuments.get(link.getCorrection().getId()))
                    .filter(Objects::nonNull).toList();
            CorrectedDelivery corrected = corrections.corrected(delivery, ofDelivery);
            List<Line> lines = flaggedLines(delivery, corrected, minMargin, cadencier, cadencierPrices, basePrices,
                    incidentIndex);
            List<PriceCheckReview> history = reviewsByDelivery.getOrDefault(delivery.getId(), List.of());
            if (lines.isEmpty() && history.isEmpty()) {
                continue;
            }
            List<PriceCheckReviewResponse> historyResponses = history.stream().map(mapper::toResponse).toList();
            result.add(new Delivery(delivery.getId(), delivery.getNumber(), delivery.getDocumentDate(),
                    delivery.getSupplierCode(), delivery.getSupplierName(), delivery.getSupplierDocumentNumber(),
                    ofDelivery.size(),
                    !lines.isEmpty() && lines.stream().allMatch(line -> line.incidentDate() != null),
                    corrected.purchaseValue(),
                    sum(lines, Line::purchaseValue),
                    (int) lines.stream().filter(Line::belowMinimum).count(),
                    sum(lines, Line::excessValue),
                    sum(lines.stream().filter(line -> line.basePrice() != null).toList(),
                            line -> line.purchasePrice().subtract(line.basePrice()).multiply(line.quantity())
                                    .setScale(2, RoundingMode.HALF_UP)),
                    historyResponses.isEmpty() ? null : historyResponses.getFirst(), historyResponses, lines));
        }
        result.sort(Comparator.<Delivery>comparingInt(delivery -> delivery.review() == null ? 0
                        : STATUS_ORDER.get(delivery.review().status()))
                .thenComparing(Delivery::documentDate, Comparator.reverseOrder())
                .thenComparing(Delivery::number, Comparator.reverseOrder()));

        return new PriceCheckReport(from, to, PERIOD_DAYS, minMargin, BASE_SUPPLIER_CODES, cadencier.size(),
                cadencierImport != null ? cadencierImport.getReportGeneratedAt() : null,
                cadencierImport != null ? cadencierImport.getImportedAt() : null,
                documentImports.findLastPeriodTo(storeId).orElse(null),
                documentImports.findNewestReportGeneratedAt(storeId).orElse(null),
                INCIDENT_DAYS, incidents.findLastIncidentDate(storeId).orElse(null),
                result);
    }

    /** Szybki odsiew przed liczeniem korekt: czy w raporcie przyjęcia jest artykuł z cadencier od innego dostawcy. */
    private static boolean hasCadencierLineFromOtherSupplier(Document delivery, Cadencier cadencier) {
        return delivery.getLines().stream().anyMatch(line -> cadencier.itm8Of(line.getItm8(), line.getEan()) != null
                && !BASE_SUPPLIER_CODES.contains(supplierOf(line, delivery)));
    }

    /** EAN bez zer wiodących (GTIN-14 = EAN-13); kod krótszy niż {@link #MIN_EAN_LENGTH} cyfr to brak EAN-u. */
    private static String eanKey(String ean) {
        if (ean == null) {
            return null;
        }
        String trimmed = ean.strip().replaceFirst("^0+", "");
        return trimmed.length() < MIN_EAN_LENGTH ? null : trimmed;
    }

    /** Artykuły z cadencier: po ITM8 i po EAN-ach (EAN -> ITM8 z cadencier). */
    private record Cadencier(Set<String> itm8s, Map<String, String> itm8ByEan) {

        static final Cadencier EMPTY = new Cadencier(Set.of(), Map.of());

        static Cadencier of(List<String> itm8s, List<CadencierEan> eans) {
            Map<String, String> byEan = new HashMap<>();
            for (CadencierEan ean : eans) {
                String key = eanKey(ean.getEan());
                if (key != null) {
                    byEan.putIfAbsent(key, ean.getItm8());
                }
            }
            return new Cadencier(new HashSet<>(itm8s), byEan);
        }

        int size() {
            return itm8s.size();
        }

        /** ITM8 artykułu z cadencier, którym jest linia (jej ITM8 albo jej EAN), albo {@code null}. */
        String itm8Of(String itm8, String ean) {
            if (itm8s.contains(itm8)) {
                return itm8;
            }
            String key = eanKey(ean);
            return key == null ? null : itm8ByEan.get(key);
        }
    }

    /** Incydenty zamówień do bazy po ITM8 i po EAN-ie, od najstarszego dnia. */
    private record Incidents(Map<String, List<IncidentItem>> byItm8, Map<String, List<IncidentItem>> byEan) {

        static Incidents of(List<IncidentItem> items) {
            Map<String, List<IncidentItem>> byItm8 = new HashMap<>();
            Map<String, List<IncidentItem>> byEan = new HashMap<>();
            for (IncidentItem item : items) {
                byItm8.computeIfAbsent(item.getItm8(), key -> new ArrayList<>()).add(item);
                String key = eanKey(item.getEan());
                if (key != null) {
                    byEan.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
                }
            }
            return new Incidents(byItm8, byEan);
        }

        /**
         * Najnowszy incydent artykułu (ITM8 z cadencier, ITM8 linii albo EAN) z dnia przyjęcia albo najwyżej
         * {@value #INCIDENT_DAYS} dni wcześniej.
         */
        IncidentItem before(String cadencierItm8, String itm8, String ean, LocalDate deliveryDate) {
            LocalDate oldest = deliveryDate.minusDays(INCIDENT_DAYS);
            String eanKey = eanKey(ean);
            return Stream.of(byItm8.get(cadencierItm8), byItm8.get(itm8), eanKey == null ? null : byEan.get(eanKey))
                    .filter(Objects::nonNull).flatMap(List::stream)
                    .filter(item -> !item.getIncidentDate().isBefore(oldest)
                            && !item.getIncidentDate().isAfter(deliveryDate))
                    .max(Comparator.comparing(IncidentItem::getIncidentDate)).orElse(null);
        }
    }

    /** Ceny z bazy po ITM8 i po EAN-ie (baza też bywa pod innym ITM8 niż w cadencier), od najstarszej. */
    private record BasePrices(Map<String, List<SupplierPrice>> byItm8, Map<String, List<SupplierPrice>> byEan) {

        static BasePrices of(List<SupplierPrice> prices) {
            Map<String, List<SupplierPrice>> byItm8 = new HashMap<>();
            Map<String, List<SupplierPrice>> byEan = new HashMap<>();
            for (SupplierPrice price : prices) {
                byItm8.computeIfAbsent(price.getItm8(), key -> new ArrayList<>()).add(price);
                String key = eanKey(price.getEan());
                if (key != null) {
                    byEan.computeIfAbsent(key, k -> new ArrayList<>()).add(price);
                }
            }
            return new BasePrices(byItm8, byEan);
        }

        /** Najpierw dostawy artykułu z cadencier (po ITM8), a bez nich dostawy towaru o tym samym EAN-ie. */
        SupplierPrice last(String itm8, String ean, Document delivery) {
            SupplierPrice byCode = lastBasePrice(byItm8.get(itm8), delivery);
            if (byCode != null) {
                return byCode;
            }
            String key = eanKey(ean);
            return key == null ? null : lastBasePrice(byEan.get(key), delivery);
        }
    }

    private static String supplierOf(DocumentLine line, Document delivery) {
        return line.getSupplierCode() != null ? line.getSupplierCode() : delivery.getSupplierCode();
    }

    /**
     * Linie przyjęcia po korektach z artykułem z cadencier od dostawcy innego niż baza. Dostawca jest przy linii
     * (przyjęcie bez wspólnego dostawcy może mieć też linie bazy). Linie bez ilości (zwrócone w całości korektą) są
     * pomijane. Najpierw linie z marżą poniżej minimum, od największej nadwyżki.
     */
    private static List<Line> flaggedLines(Document delivery, CorrectedDelivery corrected, BigDecimal minMargin,
            Cadencier cadencier, Map<String, BigDecimal> cadencierPrices, BasePrices basePrices,
            Incidents incidents) {
        Map<String, String> supplierByItm8 = new HashMap<>();
        for (DocumentLine line : delivery.getLines()) {
            if (line.getSupplierCode() != null) {
                supplierByItm8.putIfAbsent(line.getItm8(), line.getSupplierCode());
            }
        }
        List<Line> lines = new ArrayList<>();
        for (CorrectedLine line : corrected.lines()) {
            String supplier = supplierByItm8.getOrDefault(line.itm8(), delivery.getSupplierCode());
            BigDecimal quantity = line.quantity();
            String cadencierItm8 = cadencier.itm8Of(line.itm8(), line.ean());
            if (cadencierItm8 == null || BASE_SUPPLIER_CODES.contains(supplier) || quantity == null
                    || quantity.signum() <= 0 || line.purchasePrice() == null) {
                continue;
            }
            BigDecimal purchaseValue = line.purchaseValue() != null ? line.purchaseValue()
                    : line.purchasePrice().multiply(quantity).setScale(2, RoundingMode.HALF_UP);
            BigDecimal saleNet = line.saleValueNet();
            boolean hasSale = saleNet != null && saleNet.signum() > 0;
            BigDecimal marginPercent = hasSale ? saleNet.subtract(purchaseValue).multiply(HUNDRED)
                    .divide(saleNet, 2, RoundingMode.HALF_UP) : null;
            BigDecimal salePriceNet = hasSale ? saleNet.divide(quantity, 4, RoundingMode.HALF_UP) : null;
            boolean belowMinimum = minMargin != null && marginPercent != null
                    && marginPercent.compareTo(minMargin) < 0;
            BigDecimal targetPurchasePrice = minMargin != null && salePriceNet != null
                    ? salePriceNet.multiply(HUNDRED.subtract(minMargin)).divide(HUNDRED, 4, RoundingMode.HALF_UP)
                    : null;
            BigDecimal excessValue = belowMinimum
                    ? line.purchasePrice().subtract(targetPurchasePrice).multiply(quantity)
                            .setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            SupplierPrice base = basePrices.last(cadencierItm8, line.ean(), delivery);
            BigDecimal cadencierPrice = cadencierPrices.get(cadencierItm8);
            IncidentItem incident = incidents.before(cadencierItm8, line.itm8(), line.ean(),
                    delivery.getDocumentDate().toLocalDate());
            lines.add(new Line(line.itm8(), cadencierItm8.equals(line.itm8()) ? null : cadencierItm8, line.ean(),
                    line.name(), line.brand(), supplier, quantity,
                    line.purchasePrice(), purchaseValue, line.salePriceDocument(), salePriceNet, marginPercent,
                    belowMinimum, targetPurchasePrice, excessValue,
                    cadencierPrice != null ? cadencierPrice : cadencierPrices.get(line.itm8()),
                    base != null ? base.getPurchasePrice() : null,
                    base != null ? base.getDocumentDate() : null, base != null ? base.getDocumentId() : null,
                    base != null ? base.getDocumentNumber() : null,
                    incident != null ? incident.getIncidentDate() : null,
                    incident != null ? incident.getReason() : null,
                    incident != null ? incident.getComment() : null, line.corrected()));
        }
        lines.sort(Comparator.comparing(Line::belowMinimum, Comparator.reverseOrder())
                .thenComparing(Line::excessValue, Comparator.reverseOrder())
                .thenComparing(Line::purchaseValue, Comparator.reverseOrder()));
        return lines;
    }

    /** Ostatnia cena z bazy z dnia przyjęcia albo wcześniej, najwyżej {@link #BASE_LOOKBACK_DAYS} dni wstecz. */
    private static SupplierPrice lastBasePrice(List<SupplierPrice> prices, Document delivery) {
        if (prices == null) {
            return null;
        }
        LocalDateTime day = delivery.getDocumentDate().toLocalDate().plusDays(1).atStartOfDay();
        LocalDateTime oldest = day.minusDays(BASE_LOOKBACK_DAYS + 1L);
        for (SupplierPrice price : prices.reversed()) {
            if (price.getDocumentDate().isBefore(oldest)) {
                return null;
            }
            if (price.getDocumentDate().isBefore(day)) {
                return price;
            }
        }
        return null;
    }

    private static <T> BigDecimal sum(List<T> items, Function<T, BigDecimal> value) {
        return items.stream().map(value).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional(readOnly = true)
    public PriceCheckSettingsResponse settings(UUID storeId) {
        return settings.findByStoreId(storeId).map(mapper::toResponse)
                .orElseGet(() -> new PriceCheckSettingsResponse(null, null, null));
    }

    @Transactional
    public PriceCheckSettingsResponse saveSettings(UUID storeId, PriceCheckSettingsRequest request, UUID userId) {
        PriceCheckSettings saved = settings.findByStoreId(storeId)
                .orElseGet(() -> new PriceCheckSettings(stores.getReferenceById(storeId)));
        if (saved.update(request.minMarginPercent(), userId != null ? users.getReferenceById(userId) : null)) {
            settings.saveAndFlush(saved);
        }
        return settings(storeId);
    }

    /** Nowy status wyjaśniania przyjęcia PZ; zwraca całą historię od najnowszej zmiany. */
    @Transactional
    public List<PriceCheckReviewResponse> review(UUID storeId, UUID documentId, PriceCheckReviewRequest request,
            UUID userId) {
        Document delivery = documents.findHeader(storeId, DocumentType.PZ, documentId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono przyjęcia towaru"));
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        reviews.saveAndFlush(new PriceCheckReview(stores.getReferenceById(storeId), delivery, request.status(), note,
                userId != null ? users.getReferenceById(userId) : null));
        return history(storeId, documentId);
    }

    /** Cofa zmianę statusu (znacznik); obowiązuje wtedy poprzednia. */
    @Transactional
    public List<PriceCheckReviewResponse> removeReview(UUID storeId, UUID reviewId, UUID userId) {
        PriceCheckReview review = reviews.findInStore(storeId, reviewId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono zmiany statusu"));
        if (review.getDeletedAt() == null) {
            review.markDeleted(userId != null ? users.getReferenceById(userId) : null);
            reviews.flush();
        }
        return history(storeId, review.getDocument().getId());
    }

    @Transactional
    public List<PriceCheckReviewResponse> restoreReview(UUID storeId, UUID reviewId) {
        PriceCheckReview review = reviews.findInStore(storeId, reviewId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono zmiany statusu"));
        review.restore();
        reviews.flush();
        return history(storeId, review.getDocument().getId());
    }

    private List<PriceCheckReviewResponse> history(UUID storeId, UUID documentId) {
        return reviews.findActiveByDocument(storeId, documentId).stream().map(mapper::toResponse).toList();
    }
}
