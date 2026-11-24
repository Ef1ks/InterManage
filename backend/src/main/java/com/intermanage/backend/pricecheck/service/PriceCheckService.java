package com.intermanage.backend.pricecheck.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

import com.intermanage.backend.cadtop.dto.CadPrices;
import com.intermanage.backend.cadtop.service.CadPriceService;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.dto.CorrectedDelivery;
import com.intermanage.backend.document.dto.CorrectedDelivery.CorrectedLine;
import com.intermanage.backend.document.entity.DeliveryCorrection;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentLine;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.repository.DocumentImportRepository;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.document.service.DeliveryCorrectionService;
import com.intermanage.backend.pricecheck.component.ExcludedSuppliers;
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
import com.intermanage.backend.pricecheck.entity.ReviewedReport;
import com.intermanage.backend.pricecheck.mapper.PriceCheckMapper;
import com.intermanage.backend.pricecheck.repository.CadencierImportRepository;
import com.intermanage.backend.pricecheck.repository.CadencierImportRepository.CadencierEan;
import com.intermanage.backend.pricecheck.repository.IncidentImportRepository;
import com.intermanage.backend.pricecheck.repository.IncidentImportRepository.IncidentItem;
import com.intermanage.backend.pricecheck.repository.PriceCheckReviewRepository;
import com.intermanage.backend.pricecheck.repository.PriceCheckSettingsRepository;
import com.intermanage.backend.sales.repository.SaleRepository;
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
 * oznacza linie z marżą na bonie (po korektach, % od sprzedaży brutto) poniżej niej i liczy, o ile za drogo. Cenę
 * zakupu porównujemy z aktualną ceną zakupu z cadencier (plik TOP obowiązujący dziś). Raport
 * liczy się w locie, więc korekta przypięta do przyjęcia od razu go zmienia, a status wyjaśniania z dostawcą zostaje
 * przy przyjęciu.
 */
@Service
public class PriceCheckService {

    /** Kody dostawców bazy Intermarché w systemie sklepu (baza i baza alkoholi). */
    public static final List<String> BASE_SUPPLIER_CODES = List.of("538", "88110");
    /** Raport obejmuje przyjęcia z tylu ostatnich dni (z dzisiejszym). */
    public static final int PERIOD_DAYS = 30;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** Przyjęcie od innego dostawcy do tylu dni po incydencie zamówienia do bazy jest usprawiedliwione. */
    public static final int INCIDENT_DAYS = 10;
    /** Krótsze kody (po zdjęciu zer wiodących) to wewnętrzne kody sklepu, a nie EAN-y towaru: nie łączą po EAN-ie. */
    private static final int MIN_EAN_LENGTH = 8;
    /** Kolejność na liście: najpierw do wyjaśnienia, potem zgłoszone, na końcu wyjaśnione. */
    private static final Map<PriceCheckStatus, Integer> STATUS_ORDER =
            Map.of(PriceCheckStatus.REPORTED, 1, PriceCheckStatus.RESOLVED, 2);

    private final DocumentRepository documents;
    private final CadPriceService cadPriceService;
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
    private final SaleRepository sales;
    private final ExcludedSuppliers excludedSuppliers;
    private final ReportExclusionService exclusions;

    public PriceCheckService(DocumentRepository documents, CadPriceService cadPriceService,
            DocumentImportRepository documentImports, DeliveryCorrectionService corrections,
            ProductRepository products, CadencierImportRepository cadencierImports,
            IncidentImportRepository incidents, PriceCheckSettingsRepository settings, PriceCheckReviewRepository reviews, StoreRepository stores,
            AppUserRepository users, PriceCheckMapper mapper, SaleRepository sales,
            ExcludedSuppliers excludedSuppliers, ReportExclusionService exclusions) {
        this.exclusions = exclusions;
        this.documents = documents;
        this.cadPriceService = cadPriceService;
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
        this.sales = sales;
        this.excludedSuppliers = excludedSuppliers;
    }

    /**
     * Przyjęcia z ostatnich {@value #PERIOD_DAYS} dni (wg daty dokumentu) z artykułem z cadencier od dostawcy spoza
     * bazy oraz wszystkie przyjęcia ze statusem wyjaśniania, także starsze.
     */
    @Transactional(readOnly = true)
    public PriceCheckReport report(UUID storeId) {
        sales.tuneForReport();
        LocalDate to = LocalDate.now(StoreTime.ZONE);
        LocalDate from = to.minusDays(PERIOD_DAYS - 1L);
        BigDecimal minMargin = settings.findByStoreId(storeId).map(PriceCheckSettings::getMinMarginPercent)
                .orElse(null);
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();

        CadencierImport cadencierImport = cadencierImports.findNewest(storeId).orElse(null);
        Map<UUID, List<PriceCheckReview>> reviewsByDelivery = reviews.findActive(storeId, ReviewedReport.BASE_PURCHASE).stream()
                .collect(Collectors.groupingBy(review -> review.getDocument().getId(), LinkedHashMap::new,
                        Collectors.toList()));
        Computed computed = compute(storeId, start, end, reviewsByDelivery.keySet(), minMargin, Set.of(), List.of());

        List<Delivery> result = new ArrayList<>();
        for (Document delivery : computed.deliveries()) {
            List<Document> ofDelivery = computed.correctionsByDelivery().get(delivery.getId());
            CorrectedDelivery corrected = computed.correctedByDelivery().get(delivery.getId());
            List<Line> lines = computed.linesByDelivery().getOrDefault(delivery.getId(), List.of());
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
                    sum(lines.stream().filter(line -> line.cadencierPurchasePrice() != null).toList(),
                            line -> line.purchasePrice().subtract(line.cadencierPurchasePrice())
                                    .multiply(line.quantity()).setScale(2, RoundingMode.HALF_UP)),
                    historyResponses.isEmpty() ? null : historyResponses.getFirst(), historyResponses, lines));
        }
        result.sort(Comparator.<Delivery>comparingInt(delivery -> delivery.review() == null ? 0
                        : STATUS_ORDER.get(delivery.review().status()))
                .thenComparing(Delivery::documentDate, Comparator.reverseOrder())
                .thenComparing(Delivery::number, Comparator.reverseOrder()));

        return new PriceCheckReport(from, to, PERIOD_DAYS, minMargin, BASE_SUPPLIER_CODES, computed.cadencierSize(),
                cadencierImport != null ? cadencierImport.getReportGeneratedAt() : null,
                cadencierImport != null ? cadencierImport.getImportedAt() : null,
                computed.cadPrices().nrCad(), computed.cadPrices().from(),
                documentImports.findLastPeriodTo(storeId).orElse(null),
                documentImports.findNewestReportGeneratedAt(storeId).orElse(null),
                INCIDENT_DAYS, incidents.findLastIncidentDate(storeId).orElse(null),
                result);
    }

    /**
     * Artykuły z cadencier od dostawcy spoza bazy w przyjęciach PZ z {@code start}..{@code end}, po przyjęciach (do
     * weryfikacji przyjęć). Minimalna marża z ustawień oznacza linie jak w raporcie. Pomija dostawców i nomenklatury
     * z list raportu zakupu poza bazą, a do tego {@code moreSuppliers} i {@code moreNomenclatures}.
     */
    Map<UUID, List<Line>> outsideBaseLines(UUID storeId, LocalDateTime start, LocalDateTime end,
            Set<String> moreSuppliers, List<String> moreNomenclatures) {
        BigDecimal minMargin = settings.findByStoreId(storeId).map(PriceCheckSettings::getMinMarginPercent)
                .orElse(null);
        return compute(storeId, start, end, Set.of(), minMargin, moreSuppliers, moreNomenclatures).linesByDelivery();
    }

    /** Przyjęcia z liniami raportu, ich korekty i linie po korektach. */
    private record Computed(List<Document> deliveries, Map<UUID, List<Document>> correctionsByDelivery,
            Map<UUID, CorrectedDelivery> correctedByDelivery, Map<UUID, List<Line>> linesByDelivery,
            int cadencierSize, CadPrices cadPrices) {
    }

    private Computed compute(UUID storeId, LocalDateTime start, LocalDateTime end, Set<UUID> reviewed,
            BigDecimal minMargin, Set<String> moreSuppliers, List<String> moreNomenclatures) {
        Set<String> excluded = new HashSet<>(excludedSuppliers.codes(storeId));
        excluded.addAll(exclusions.supplierCodes(storeId, ReviewedReport.BASE_PURCHASE));
        excluded.addAll(moreSuppliers);
        List<String> nomenclatures = new ArrayList<>(exclusions.codes(storeId, ReviewedReport.BASE_PURCHASE));
        nomenclatures.addAll(moreNomenclatures);

        CadencierImport cadencierImport = cadencierImports.findNewest(storeId).orElse(null);
        Cadencier cadencier = cadencierImport == null ? Cadencier.EMPTY
                : Cadencier.of(cadencierImports.findCurrentItm8s(storeId), cadencierImports.findCurrentEans(storeId));
        // Linie wczytujemy tylko dla przyjęć, które odsiała baza, i dla przyjęć ze statusem (także starszych).
        Set<UUID> ids = new LinkedHashSet<>(cadencierImport == null ? List.of()
                : cadencierImports.findDeliveriesWithCadencierLines(storeId, BASE_SUPPLIER_CODES, start, end));
        ids.addAll(reviewed);
        List<Document> deliveries = ids.isEmpty() ? List.of() : documents.findWithLines(storeId, ids).stream()
                .filter(delivery -> delivery.getType() == DocumentType.PZ && !delivery.isMarkedAsError())
                .filter(delivery -> reviewed.contains(delivery.getId())
                        || hasCadencierLineFromOtherSupplier(delivery, cadencier, excluded))
                .toList();
        LocalDateTime oldest = deliveries.stream().map(Document::getDocumentDate).min(Comparator.naturalOrder())
                .orElse(start);
        Incidents incidentIndex = Incidents.of(
                incidents.findSince(storeId, oldest.toLocalDate().minusDays(INCIDENT_DAYS)));
        Map<UUID, List<DeliveryCorrection>> links = corrections.correctionsOf(deliveries);
        Map<UUID, Document> correctionDocuments = corrections.correctionDocuments(storeId,
                        links.values().stream().flatMap(List::stream).toList())
                .stream().filter(Objects::nonNull)
                .collect(Collectors.toMap(Document::getId, Function.identity()));

        Map<UUID, List<Document>> correctionsByDelivery = new HashMap<>();
        Map<UUID, CorrectedDelivery> correctedByDelivery = new HashMap<>();
        Set<String> itm8Keys = new HashSet<>();
        for (Document delivery : deliveries) {
            List<Document> ofDelivery = links.getOrDefault(delivery.getId(), List.of()).stream()
                    .map(link -> correctionDocuments.get(link.getCorrection().getId()))
                    .filter(Objects::nonNull).toList();
            CorrectedDelivery corrected = corrections.corrected(delivery, ofDelivery);
            correctionsByDelivery.put(delivery.getId(), ofDelivery);
            correctedByDelivery.put(delivery.getId(), corrected);
            for (CorrectedLine line : corrected.lines()) {
                String cadencierItm8 = cadencier.itm8Of(line.itm8(), line.ean());
                if (cadencierItm8 != null) {
                    itm8Keys.add(cadencierItm8);
                    itm8Keys.add(line.itm8());
                }
            }
        }
        // Ceny cadencier tylko artykułów z raportu; CENA_CAD z kartoteki zastępuje brak artykułu w pliku TOP.
        Map<String, BigDecimal> catalogPrices = itm8Keys.isEmpty() ? Map.of()
                : products.findCadencierPrices(storeId, itm8Keys.toArray(String[]::new)).stream()
                        .collect(Collectors.toMap(CadencierPrice::getItm8, CadencierPrice::getCadencierPrice));
        CadPrices cadPrices = cadPriceService.current(storeId, itm8Keys);

        Map<UUID, List<Line>> linesByDelivery = new HashMap<>();
        for (Document delivery : deliveries) {
            List<Line> lines = flaggedLines(delivery, correctedByDelivery.get(delivery.getId()), minMargin, cadencier,
                    catalogPrices, cadPrices, incidentIndex, excluded, nomenclatures);
            if (!lines.isEmpty()) {
                linesByDelivery.put(delivery.getId(), lines);
            }
        }
        return new Computed(deliveries, correctionsByDelivery, correctedByDelivery, linesByDelivery,
                cadencier.size(), cadPrices);
    }

    /** Szybki odsiew przed liczeniem korekt: czy w raporcie przyjęcia jest artykuł z cadencier od innego dostawcy. */
    private static boolean hasCadencierLineFromOtherSupplier(Document delivery, Cadencier cadencier,
            Set<String> excluded) {
        return delivery.getLines().stream().anyMatch(line -> cadencier.itm8Of(line.getItm8(), line.getEan()) != null
                && !BASE_SUPPLIER_CODES.contains(supplierOf(line, delivery))
                && !excluded.contains(supplierOf(line, delivery)));
    }

    /** Rejon z kodu nomenklatury linii ("570101" albo "57 01 01" -> "57"). */
    private static String regionOf(String nomenclatureCode) {
        if (nomenclatureCode == null) {
            return null;
        }
        String digits = nomenclatureCode.replaceAll("\\D", "");
        return digits.length() < 2 ? null : digits.substring(0, 2);
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
    record Cadencier(Set<String> itm8s, Map<String, String> itm8ByEan) {

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

    private static String supplierOf(DocumentLine line, Document delivery) {
        return line.getSupplierCode() != null ? line.getSupplierCode() : delivery.getSupplierCode();
    }

    /**
     * Linie przyjęcia po korektach z artykułem z cadencier od dostawcy innego niż baza. Dostawca jest przy linii
     * (przyjęcie bez wspólnego dostawcy może mieć też linie bazy). Linie bez ilości (zwrócone w całości korektą) są
     * pomijane. Najpierw linie z marżą poniżej minimum, od największej nadwyżki.
     */
    private static List<Line> flaggedLines(Document delivery, CorrectedDelivery corrected, BigDecimal minMargin,
            Cadencier cadencier, Map<String, BigDecimal> catalogPrices, CadPrices cadPrices,
            Incidents incidents, Set<String> excluded, List<String> nomenclatures) {
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
            if (cadencierItm8 == null || BASE_SUPPLIER_CODES.contains(supplier) || excluded.contains(supplier)
                    || ReportExclusionService.matches(line.nomenclatureCode(), nomenclatures)
                    || quantity == null
                    || quantity.signum() <= 0 || line.purchasePrice() == null) {
                continue;
            }
            BigDecimal purchaseValue = line.purchaseValue() != null ? line.purchaseValue()
                    : line.purchasePrice().multiply(quantity).setScale(2, RoundingMode.HALF_UP);
            BigDecimal saleNet = line.saleValueNet();
            BigDecimal saleGross = line.saleValueGross();
            // Marża od sprzedaży brutto, jak w Mercalys (Margin).
            boolean hasSale = saleNet != null && saleNet.signum() > 0 && saleGross != null && saleGross.signum() > 0;
            BigDecimal marginPercent = hasSale ? saleNet.subtract(purchaseValue).multiply(HUNDRED)
                    .divide(saleGross, 2, RoundingMode.HALF_UP) : null;
            BigDecimal salePriceNet = hasSale ? saleNet.divide(quantity, 4, RoundingMode.HALF_UP) : null;
            boolean belowMinimum = minMargin != null && marginPercent != null
                    && marginPercent.compareTo(minMargin) < 0;
            BigDecimal targetPurchasePrice = minMargin != null && hasSale
                    ? saleNet.subtract(saleGross.multiply(minMargin).divide(HUNDRED, 6, RoundingMode.HALF_UP))
                            .divide(quantity, 4, RoundingMode.HALF_UP)
                    : null;
            BigDecimal excessValue = belowMinimum
                    ? line.purchasePrice().subtract(targetPurchasePrice).multiply(quantity)
                            .setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            CadPrices.Price cadPrice = cadPrices.of(cadencierItm8);
            BigDecimal cadencierPrice = cadPrice != null && cadPrice.salePrice() != null ? cadPrice.salePrice()
                    : catalogPrices.getOrDefault(cadencierItm8, catalogPrices.get(line.itm8()));
            IncidentItem incident = incidents.before(cadencierItm8, line.itm8(), line.ean(),
                    delivery.getDocumentDate().toLocalDate());
            lines.add(new Line(line.itm8(), cadencierItm8.equals(line.itm8()) ? null : cadencierItm8, line.ean(),
                    line.name(), line.brand(), regionOf(line.nomenclatureCode()), supplier, quantity,
                    line.purchasePrice(), purchaseValue, line.salePriceDocument(), salePriceNet, marginPercent,
                    belowMinimum, targetPurchasePrice, excessValue,
                    cadencierPrice, cadPrice != null ? cadPrice.purchasePrice() : null,
                    incident != null ? incident.getIncidentDate() : null,
                    incident != null ? incident.getReason() : null,
                    incident != null ? incident.getComment() : null, line.corrected()));
        }
        lines.sort(Comparator.comparing(Line::belowMinimum, Comparator.reverseOrder())
                .thenComparing(Line::excessValue, Comparator.reverseOrder())
                .thenComparing(Line::purchaseValue, Comparator.reverseOrder()));
        return lines;
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

    /** Nowy status wyjaśniania przyjęcia PZ w raporcie; zwraca całą historię od najnowszej zmiany. */
    @Transactional
    public List<PriceCheckReviewResponse> review(UUID storeId, ReviewedReport report, UUID documentId,
            PriceCheckReviewRequest request, UUID userId) {
        Document delivery = documents.findHeader(storeId, DocumentType.PZ, documentId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono przyjęcia towaru"));
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        reviews.saveAndFlush(new PriceCheckReview(stores.getReferenceById(storeId), delivery, report, request.status(),
                note, userId != null ? users.getReferenceById(userId) : null));
        return history(storeId, report, documentId);
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
        return history(storeId, review.getReport(), review.getDocument().getId());
    }

    @Transactional
    public List<PriceCheckReviewResponse> restoreReview(UUID storeId, UUID reviewId) {
        PriceCheckReview review = reviews.findInStore(storeId, reviewId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono zmiany statusu"));
        review.restore();
        reviews.flush();
        return history(storeId, review.getReport(), review.getDocument().getId());
    }

    private List<PriceCheckReviewResponse> history(UUID storeId, ReviewedReport report, UUID documentId) {
        return reviews.findActiveByDocument(storeId, report, documentId).stream().map(mapper::toResponse).toList();
    }
}
