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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.entity.DeliveryCorrection;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.repository.DocumentImportRepository;
import com.intermanage.backend.document.repository.DocumentLineRepository;
import com.intermanage.backend.document.repository.DocumentLineRepository.DeliveryMargin;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.document.dto.LinkedKsefInvoice;
import com.intermanage.backend.document.service.DeliveryCorrectionService;
import com.intermanage.backend.document.service.DocumentKsefLinkService;
import com.intermanage.backend.nomenclature.service.NomenclatureMarginService;
import com.intermanage.backend.pricecheck.component.ExcludedSuppliers;
import com.intermanage.backend.pricecheck.dto.DeliveryLineHint;
import com.intermanage.backend.pricecheck.dto.DeliveryLineHints;
import com.intermanage.backend.pricecheck.dto.LowMarginReport;
import com.intermanage.backend.pricecheck.dto.LowMarginReport.Delivery;
import com.intermanage.backend.pricecheck.dto.LowMarginReport.Line;
import com.intermanage.backend.pricecheck.dto.LowMarginReport.MarginDeviation;
import com.intermanage.backend.pricecheck.dto.LowMarginReport.PromotionSource;
import com.intermanage.backend.pricecheck.dto.PriceCheckReviewResponse;
import com.intermanage.backend.pricecheck.dto.ReportSupplier;
import com.intermanage.backend.pricecheck.entity.LowMarginSettings;
import com.intermanage.backend.pricecheck.entity.PriceCheckReview;
import com.intermanage.backend.pricecheck.entity.ReviewedReport;
import com.intermanage.backend.pricecheck.mapper.PriceCheckMapper;
import com.intermanage.backend.pricecheck.repository.CadencierImportRepository;
import com.intermanage.backend.pricecheck.repository.LowMarginSettingsRepository;
import com.intermanage.backend.pricecheck.repository.PriceCheckReviewRepository;
import com.intermanage.backend.pricecheck.service.PriceCheckService.Cadencier;
import com.intermanage.backend.promotion.service.PromotionReportService;
import com.intermanage.backend.sales.repository.SaleRepository;
import com.intermanage.backend.store.repository.StoreRepository;


/**
 * Marże poza normą. Każde przyjęcie PZ z wybranego okresu (domyślnie {@value #PERIOD_DAYS} dni, najwyżej
 * {@value #MAX_PERIOD_DAYS}): artykuł trafia do raportu, gdy jego marża (% od sprzedaży netto, zakup po korektach) jest
 * o co najmniej próg niższa albo wyższa niż marża wymagana na jego nomenklaturze ({@link NomenclatureMarginService}).
 * Progi w punktach procentowych sklep ustawia sam ({@link LowMarginSettings}), domyślnie 5 w dół i 10 w górę.
 * <p>
 * Marżę liczymy z aktualnej ceny zwykłej na kasie (kartoteka po plikach TRACEART, bez trwającej promocji), a nie z ceny
 * z dokumentu: po zmianie ceny raport od razu liczy z nowej. Artykuł spoza kartoteki bierze cenę z dnia przyjęcia: z
 * dokumentu, a w promocji cenę zwykłą z listy promocji, z plików kasy, a dla gazetki i promocji rozpoznanej ze sprzedaży
 * ({@link PromotionReportService#PRICE_FACTOR}) ze sprzedaży. {@code promotion} linii mówi, że cena na dokumencie była
 * promocyjna. Dostawców z {@link ExcludedSuppliers} i tych, których nie
 * dotyczy marża ({@link ReportExcludedSupplierService}), raport nie sprawdza. Pomija też artykuły aktywne w cadencier (po
 * ITM8 albo EAN-ie), bo mają z góry ustaloną, rynkową marżę.
 */
@Service
public class LowMarginService {

    public static final int PERIOD_DAYS = 30;
    /** Najdłuższy okres raportu w dniach. */
    public static final int MAX_PERIOD_DAYS = 365;
    /** Domyślnie marża niższa od wymaganej o tyle punktów procentowych (i więcej) jest za niska. */
    static final BigDecimal LOW_GAP = BigDecimal.valueOf(5);
    /** Domyślnie marża wyższa od wymaganej o tyle punktów procentowych (i więcej) jest za wysoka. */
    static final BigDecimal HIGH_GAP = BigDecimal.valueOf(10);
    /** Kody nomenklatury od podrodziny do rejonu: obowiązuje najbardziej szczegółowy. */
    private static final int[] CODE_LENGTHS = {6, 4, 2};

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final DocumentRepository documents;
    private final DocumentLineRepository documentLines;
    private final DocumentImportRepository documentImports;
    private final DeliveryCorrectionService corrections;
    private final NomenclatureMarginService margins;
    private final PriceCheckReviewRepository reviews;
    private final PriceCheckMapper mapper;
    private final SaleRepository sales;
    private final ExcludedSuppliers excludedSuppliers;
    private final ReportExcludedSupplierService reportSuppliers;
    private final CadencierImportRepository cadencierImports;
    private final LowMarginSettingsRepository settings;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final DocumentKsefLinkService ksefLinks;

    public LowMarginService(DocumentRepository documents, DocumentLineRepository documentLines,
            DocumentImportRepository documentImports, DeliveryCorrectionService corrections,
            NomenclatureMarginService margins, PriceCheckReviewRepository reviews, PriceCheckMapper mapper,
            SaleRepository sales, ExcludedSuppliers excludedSuppliers, ReportExcludedSupplierService reportSuppliers,
            CadencierImportRepository cadencierImports, LowMarginSettingsRepository settings, StoreRepository stores,
            AppUserRepository users, DocumentKsefLinkService ksefLinks) {
        this.documents = documents;
        this.documentLines = documentLines;
        this.documentImports = documentImports;
        this.corrections = corrections;
        this.margins = margins;
        this.reviews = reviews;
        this.mapper = mapper;
        this.sales = sales;
        this.excludedSuppliers = excludedSuppliers;
        this.reportSuppliers = reportSuppliers;
        this.cadencierImports = cadencierImports;
        this.settings = settings;
        this.stores = stores;
        this.users = users;
        this.ksefLinks = ksefLinks;
    }

    @Transactional(readOnly = true)
    public LowMarginReport report(UUID storeId, int days) {
        if (days < 1 || days > MAX_PERIOD_DAYS) {
            throw ApiException.badRequest("Okres raportu musi mieć od 1 do " + MAX_PERIOD_DAYS + " dni.");
        }
        sales.tuneForReport();
        LocalDate to = LocalDate.now(StoreTime.ZONE);
        LocalDate from = to.minusDays(days - 1L);
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime until = to.plusDays(1).atStartOfDay();

        Map<UUID, List<PriceCheckReview>> reviewsByDelivery = reviews.findActive(storeId, ReviewedReport.LOW_MARGIN)
                .stream().collect(Collectors.groupingBy(review -> review.getDocument().getId(), LinkedHashMap::new,
                        Collectors.toList()));
        Set<UUID> reviewed = reviewsByDelivery.keySet();
        LocalDate oldest = reviewsByDelivery.values().stream().flatMap(List::stream)
                .map(review -> review.getDocument().getDocumentDate().toLocalDate()).min(Comparator.naturalOrder())
                .filter(date -> date.isBefore(from)).orElse(from);

        Optional<LowMarginSettings> saved = settings.findByStoreId(storeId);
        BigDecimal lowGap = saved.map(LowMarginSettings::getLowGap).orElse(LOW_GAP);
        BigDecimal highGap = saved.map(LowMarginSettings::getHighGap).orElse(HIGH_GAP);
        Map<String, BigDecimal> required = margins.effectiveByCode(storeId);
        List<ReportSupplier> withoutMargin = reportSuppliers.active(storeId, ReviewedReport.LOW_MARGIN);
        Set<String> excluded = new HashSet<>(excludedSuppliers.codes(storeId));
        withoutMargin.forEach(supplier -> excluded.add(supplier.code()));
        Map<UUID, List<Line>> linesByDelivery = required.isEmpty() ? Map.of()
                : lowMargins(documentLines.findDeliveryMargins(storeId, start, until,
                        reviewed.stream().map(UUID::toString).toArray(String[]::new), oldest, to,
                        oldest.withDayOfMonth(1).minusMonths(PromotionReportService.REFERENCE_MONTHS),
                        PromotionReportService.MIN_REFERENCE_DAYS), required, excluded, lowGap, highGap,
                        Cadencier.of(cadencierImports.findCurrentItm8s(storeId),
                                cadencierImports.findCurrentEans(storeId)));

        Set<UUID> ids = new LinkedHashSet<>(linesByDelivery.keySet());
        ids.addAll(reviewed);
        List<Document> deliveries = ids.isEmpty() ? List.of() : documents.findAllById(ids).stream()
                .filter(delivery -> delivery.getType() == DocumentType.PZ && !delivery.isMarkedAsError()).toList();
        Map<UUID, List<DeliveryCorrection>> links = corrections.correctionsOf(deliveries);
        Map<UUID, List<LinkedKsefInvoice>> invoices = deliveries.isEmpty() ? Map.of()
                : ksefLinks.invoicesWithCorrections(storeId, deliveries, links);

        List<Delivery> result = new ArrayList<>();
        for (Document delivery : deliveries) {
            List<Line> lines = linesByDelivery.getOrDefault(delivery.getId(), List.of());
            List<PriceCheckReviewResponse> responses = reviewsByDelivery.getOrDefault(delivery.getId(), List.of())
                    .stream().map(mapper::toResponse).toList();
            result.add(new Delivery(delivery.getId(), delivery.getNumber(), delivery.getDocumentDate(),
                    delivery.getSupplierCode(), delivery.getSupplierName(), delivery.getSupplierDocumentNumber(),
                    links.getOrDefault(delivery.getId(), List.of()).size(),
                    OverpaymentService.sum(lines, Line::purchaseValue),
                    OverpaymentService.sum(lines.stream().filter(line -> line.deviation() == MarginDeviation.LOW)
                            .toList(), Line::excessValue), responses.isEmpty() ? null : responses.getFirst(),
                    responses, lines,
                    delivery.getVerificationStatus() != null ? delivery.getVerificationStatus().name() : null,
                    delivery.getVerificationAt(),
                    delivery.getVerificationBy() != null ? delivery.getVerificationBy().getDisplayName() : null,
                    invoices.getOrDefault(delivery.getId(), List.of())));
        }
        result.sort(Comparator.<Delivery>comparingInt(delivery -> delivery.review() == null ? 0
                        : OverpaymentService.STATUS_ORDER.get(delivery.review().status()))
                .thenComparing(Delivery::documentDate, Comparator.reverseOrder())
                .thenComparing(Delivery::number, Comparator.reverseOrder()));

        return new LowMarginReport(from, to, days, lowGap, highGap, !required.isEmpty(),
                documentImports.findLastPeriodTo(storeId).orElse(null),
                documentImports.findNewestReportGeneratedAt(storeId).orElse(null),
                withoutMargin, reportSuppliers.options(storeId), result);
    }

    /**
     * Ocena wszystkich artykułów jednego przyjęcia PZ, do kolorów na bonie: marża z aktualnej ceny wobec wymaganej
     * (z progami sklepu), promocja teraz i na przyjęciu, cadencier i dostawcy pomijani w raporcie. Artykuł w normie ma
     * {@code deviation} pusty; bez marży wymaganej puste są też cel i różnica.
     */
    @Transactional(readOnly = true)
    public DeliveryLineHints lineHints(UUID storeId, UUID documentId) {
        Document delivery = documents.findById(documentId)
                .filter(document -> document.getStore().getId().equals(storeId) && document.getType() == DocumentType.PZ)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono przyjęcia"));
        sales.tuneForReport();
        LocalDate to = LocalDate.now(StoreTime.ZONE);
        LocalDate day = delivery.getDocumentDate().toLocalDate();
        LocalDateTime none = to.plusDays(1).atStartOfDay();
        Optional<LowMarginSettings> saved = settings.findByStoreId(storeId);
        BigDecimal lowGap = saved.map(LowMarginSettings::getLowGap).orElse(LOW_GAP);
        BigDecimal highGap = saved.map(LowMarginSettings::getHighGap).orElse(HIGH_GAP);
        Map<String, BigDecimal> required = margins.effectiveByCode(storeId);
        Set<String> excluded = new HashSet<>(excludedSuppliers.codes(storeId));
        reportSuppliers.active(storeId, ReviewedReport.LOW_MARGIN).forEach(supplier -> excluded.add(supplier.code()));
        Cadencier cadencier = Cadencier.of(cadencierImports.findCurrentItm8s(storeId),
                cadencierImports.findCurrentEans(storeId));
        List<DeliveryLineHint> hints = new ArrayList<>();
        for (DeliveryMargin article : documentLines.findDeliveryMargins(storeId, none, none,
                new String[] {documentId.toString()}, day.isBefore(to) ? day : to, to,
                day.withDayOfMonth(1).minusMonths(PromotionReportService.REFERENCE_MONTHS),
                PromotionReportService.MIN_REFERENCE_DAYS)) {
            Line line = evaluate(article, requiredMargin(article.getNomenclatureCode(), required), lowGap, highGap);
            if (line != null) {
                hints.add(new DeliveryLineHint(line, cadencier.itm8Of(article.getItm8(), article.getEan()) != null,
                        excluded.contains(article.getSupplierCode())));
            }
        }
        return new DeliveryLineHints(lowGap, highGap, !required.isEmpty(), hints);
    }

    /** Zapisuje progi marży sklepu; zwraca raport po zmianie. */
    @Transactional
    public LowMarginReport setGaps(UUID storeId, BigDecimal lowGap, BigDecimal highGap, int days, UUID userId) {
        LowMarginSettings saved = settings.findByStoreId(storeId)
                .orElseGet(() -> new LowMarginSettings(stores.getReferenceById(storeId)));
        saved.update(lowGap, highGap, users.getReferenceById(userId));
        settings.saveAndFlush(saved);
        return report(storeId, days);
    }

    /** Zastępuje listę dostawców, których nie dotyczy marża; zwraca raport po zmianie. */
    @Transactional
    public LowMarginReport setExcludedSuppliers(UUID storeId, List<String> supplierCodes, int days, UUID userId) {
        reportSuppliers.replace(storeId, ReviewedReport.LOW_MARGIN, supplierCodes, userId);
        return report(storeId, days);
    }

    /** Artykuły z marżą poza normą po przyjęciach: najpierw za niskie od największej straty, potem za wysokie. */
    private static Map<UUID, List<Line>> lowMargins(List<DeliveryMargin> articles, Map<String, BigDecimal> required,
            Set<String> excluded, BigDecimal lowGap, BigDecimal highGap, Cadencier cadencier) {
        Map<UUID, List<Line>> lines = new HashMap<>();
        for (DeliveryMargin article : articles) {
            boolean skipped = excluded.contains(article.getSupplierCode())
                    || cadencier.itm8Of(article.getItm8(), article.getEan()) != null;
            Line line = skipped ? null
                    : lowMarginLine(article, requiredMargin(article.getNomenclatureCode(), required), lowGap, highGap);
            if (line != null) {
                lines.computeIfAbsent(article.getDocumentId(), id -> new ArrayList<>()).add(line);
            }
        }
        lines.values().forEach(list -> list.sort(Comparator.comparing(Line::deviation)
                .thenComparing(Line::excessValue, Comparator.reverseOrder())));
        return lines;
    }

    /** Marża obowiązująca dla kodu z linii ("570101" albo "57 01 01"); {@code null}, gdy nomenklatura jej nie ma. */
    private static BigDecimal requiredMargin(String nomenclatureCode, Map<String, BigDecimal> required) {
        if (nomenclatureCode == null) {
            return null;
        }
        String digits = nomenclatureCode.replaceAll("\\D", "");
        for (int length : CODE_LENGTHS) {
            if (digits.length() >= length && required.containsKey(digits.substring(0, length))) {
                return required.get(digits.substring(0, length));
            }
        }
        return null;
    }

    /**
     * Linia raportu, gdy marża z aktualnej ceny jest co najmniej o {@code lowGap} p.p. niższa albo o {@code highGap}
     * p.p. wyższa od wymaganej, inaczej {@code null}.
     */
    private static Line lowMarginLine(DeliveryMargin article, BigDecimal requiredMargin, BigDecimal lowGap,
            BigDecimal highGap) {
        if (requiredMargin == null) {
            return null;
        }
        Line line = evaluate(article, requiredMargin, lowGap, highGap);
        return line != null && line.deviation() != null ? line : null;
    }

    /**
     * Ocena artykułu przyjęcia: marża z aktualnej ceny, a przy marży wymaganej cel, różnica i odchylenie
     * ({@code null} w normie). {@code null}, gdy linia nie ma danych do policzenia marży.
     */
    private static Line evaluate(DeliveryMargin article, BigDecimal requiredMargin, BigDecimal lowGap,
            BigDecimal highGap) {
        BigDecimal quantity = article.getCorrectedQuantity();
        BigDecimal value = article.getCorrectedValue();
        BigDecimal documentPrice = article.getSalePrice();
        if (quantity.signum() <= 0 || value.signum() <= 0 || documentPrice == null
                || documentPrice.signum() <= 0 || article.getVatRate() == null) {
            return null;
        }
        BigDecimal regular = regularPrice(article);
        BigDecimal net = regular.multiply(HUNDRED)
                .divide(HUNDRED.add(BigDecimal.valueOf(article.getVatRate())), 6, RoundingMode.HALF_UP);
        BigDecimal price = value.divide(quantity, 4, RoundingMode.HALF_UP);
        BigDecimal margin = net.subtract(price).multiply(HUNDRED).divide(net, 2, RoundingMode.HALF_UP);
        MarginDeviation deviation = null;
        BigDecimal target = null;
        BigDecimal excess = null;
        if (requiredMargin != null) {
            if (margin.compareTo(requiredMargin.subtract(lowGap)) <= 0) {
                deviation = MarginDeviation.LOW;
            } else if (margin.compareTo(requiredMargin.add(highGap)) >= 0) {
                deviation = MarginDeviation.HIGH;
            }
            target = net.multiply(HUNDRED.subtract(requiredMargin)).divide(HUNDRED, 4, RoundingMode.HALF_UP);
            excess = price.subtract(target).multiply(quantity).abs().setScale(2, RoundingMode.HALF_UP);
        }
        return new Line(article.getItm8(), article.getEan(), article.getName(), article.getBrand(),
                OverpaymentService.regionOf(article.getNomenclatureCode()), article.getSupplierCode(), quantity, price,
                value, regular, documentPrice,
                priceOnDeliveryDay(article).compareTo(documentPrice) > 0 ? promotionOf(article) : null,
                margin, requiredMargin, target, excess, article.getCorrected(), deviation, article.getCurrentPromoPrice(),
                article.getCurrentPromoTo());
    }

    /**
     * Cena, od której liczy się marża: aktualna cena zwykła na kasie, a bez artykułu w kartotece cena katalogowa w dniu
     * przyjęcia.
     */
    private static BigDecimal regularPrice(DeliveryMargin article) {
        BigDecimal current = article.getCurrentPrice();
        return current != null && current.signum() > 0 ? current : priceOnDeliveryDay(article);
    }

    /** Cena katalogowa w dniu przyjęcia: cena z dokumentu, a w promocji cena zwykła, nigdy niższa niż z dokumentu. */
    private static BigDecimal priceOnDeliveryDay(DeliveryMargin article) {
        BigDecimal regular = switch (promotionOf(article)) {
            case LIST -> article.getTillPrice() != null ? article.getListedPrice().max(article.getTillPrice())
                    : article.getListedPrice();
            case TILL -> article.getTillPrice();
            case LEAFLET -> article.getReferencePrice() != null ? article.getReferencePrice()
                    : article.getCatalogPrice();
            case SALES -> article.getReferencePrice();
            case null -> null;
        };
        return regular == null ? article.getSalePrice() : regular.max(article.getSalePrice());
    }

    /** Skąd wiadomo o promocji w dniu przyjęcia; {@code null}, gdy jej nie było. */
    private static PromotionSource promotionOf(DeliveryMargin article) {
        if (article.getListedPrice() != null) {
            return PromotionSource.LIST;
        }
        if (article.getTillPrice() != null) {
            return PromotionSource.TILL;
        }
        if (article.getLeaflet()) {
            return PromotionSource.LEAFLET;
        }
        BigDecimal reference = article.getReferencePrice();
        return reference != null
                && article.getSalePrice().compareTo(reference.multiply(PromotionReportService.PRICE_FACTOR)) < 0
                        ? PromotionSource.SALES : null;
    }
}
