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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
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
import com.intermanage.backend.document.service.DeliveryCorrectionService;
import com.intermanage.backend.nomenclature.service.NomenclatureMarginService;
import com.intermanage.backend.pricecheck.component.ExcludedSuppliers;
import com.intermanage.backend.pricecheck.dto.LowMarginReport;
import com.intermanage.backend.pricecheck.dto.LowMarginReport.Delivery;
import com.intermanage.backend.pricecheck.dto.LowMarginReport.Line;
import com.intermanage.backend.pricecheck.dto.LowMarginReport.PromotionSource;
import com.intermanage.backend.pricecheck.dto.PriceCheckReviewResponse;
import com.intermanage.backend.pricecheck.entity.LowMarginExcludedSupplier;
import com.intermanage.backend.pricecheck.entity.PriceCheckReview;
import com.intermanage.backend.pricecheck.entity.ReviewedReport;
import com.intermanage.backend.pricecheck.mapper.PriceCheckMapper;
import com.intermanage.backend.pricecheck.repository.LowMarginExcludedSupplierRepository;
import com.intermanage.backend.pricecheck.repository.PriceCheckReviewRepository;
import com.intermanage.backend.promotion.service.PromotionReportService;
import com.intermanage.backend.sales.repository.SaleRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Za niskie marże. Każde przyjęcie PZ z ostatnich {@value #PERIOD_DAYS} dni: artykuł trafia do raportu, gdy jego marża
 * (% od sprzedaży netto, zakup po korektach) jest o co najmniej {@value #MARGIN_GAP} punktów procentowych niższa niż
 * marża wymagana na jego nomenklaturze ({@link NomenclatureMarginService}).
 * <p>
 * Marżę liczymy z ceny katalogowej, a nie promocyjnej: w promocji cena sprzedaży z dokumentu jest ceną promocyjną, więc
 * zastępuje ją cena zwykła w dniu przyjęcia: z listy promocji i z plików kasy (promocje założone ręcznie są tylko tam),
 * a dla gazetki i promocji rozpoznanej ze sprzedaży ({@link PromotionReportService#PRICE_FACTOR}) cena zwykła ze
 * sprzedaży. Lista promocji ma tylko bieżące i przyszłe, a pliki kasy mówią o każdej promocji w sklepie. Dostawców z {@link ExcludedSuppliers} i tych, których nie
 * dotyczy marża ({@link LowMarginExcludedSupplier}), raport nie sprawdza.
 */
@Service
public class LowMarginService {

    public static final int PERIOD_DAYS = 30;
    static final int MARGIN_GAP = 10;
    /** Kody nomenklatury od podrodziny do rejonu: obowiązuje najbardziej szczegółowy. */
    private static final int[] CODE_LENGTHS = {6, 4, 2};
    private static final String SUPPLIERS_LOCK_PREFIX = "low-margin-suppliers:";

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
    private final LowMarginExcludedSupplierRepository marginExcludedSuppliers;
    private final SupplierRepository suppliers;
    private final StoreRepository stores;
    private final AppUserRepository users;

    public LowMarginService(DocumentRepository documents, DocumentLineRepository documentLines,
            DocumentImportRepository documentImports, DeliveryCorrectionService corrections,
            NomenclatureMarginService margins, PriceCheckReviewRepository reviews, PriceCheckMapper mapper,
            SaleRepository sales, ExcludedSuppliers excludedSuppliers,
            LowMarginExcludedSupplierRepository marginExcludedSuppliers, SupplierRepository suppliers,
            StoreRepository stores, AppUserRepository users) {
        this.documents = documents;
        this.documentLines = documentLines;
        this.documentImports = documentImports;
        this.corrections = corrections;
        this.margins = margins;
        this.reviews = reviews;
        this.mapper = mapper;
        this.sales = sales;
        this.excludedSuppliers = excludedSuppliers;
        this.marginExcludedSuppliers = marginExcludedSuppliers;
        this.suppliers = suppliers;
        this.stores = stores;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public LowMarginReport report(UUID storeId) {
        sales.tuneForReport();
        LocalDate to = LocalDate.now(StoreTime.ZONE);
        LocalDate from = to.minusDays(PERIOD_DAYS - 1L);
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime until = to.plusDays(1).atStartOfDay();

        Map<UUID, List<PriceCheckReview>> reviewsByDelivery = reviews.findActive(storeId, ReviewedReport.LOW_MARGIN)
                .stream().collect(Collectors.groupingBy(review -> review.getDocument().getId(), LinkedHashMap::new,
                        Collectors.toList()));
        Set<UUID> reviewed = reviewsByDelivery.keySet();
        LocalDate oldest = reviewsByDelivery.values().stream().flatMap(List::stream)
                .map(review -> review.getDocument().getDocumentDate().toLocalDate()).min(Comparator.naturalOrder())
                .filter(date -> date.isBefore(from)).orElse(from);

        Map<String, BigDecimal> required = margins.effectiveByCode(storeId);
        List<LowMarginExcludedSupplier> withoutMargin = marginExcludedSuppliers.findActive(storeId);
        Set<String> excluded = new HashSet<>(excludedSuppliers.codes(storeId));
        withoutMargin.forEach(supplier -> excluded.add(supplier.getSupplierCode()));
        Map<UUID, List<Line>> linesByDelivery = required.isEmpty() ? Map.of()
                : lowMargins(documentLines.findDeliveryMargins(storeId, start, until,
                        reviewed.stream().map(UUID::toString).toArray(String[]::new), oldest, to,
                        oldest.withDayOfMonth(1).minusMonths(PromotionReportService.REFERENCE_MONTHS),
                        PromotionReportService.MIN_REFERENCE_DAYS), required, excluded);

        Set<UUID> ids = new LinkedHashSet<>(linesByDelivery.keySet());
        ids.addAll(reviewed);
        List<Document> deliveries = ids.isEmpty() ? List.of() : documents.findAllById(ids).stream()
                .filter(delivery -> delivery.getType() == DocumentType.PZ).toList();
        Map<UUID, List<DeliveryCorrection>> links = corrections.correctionsOf(deliveries);

        List<Delivery> result = new ArrayList<>();
        for (Document delivery : deliveries) {
            List<Line> lines = linesByDelivery.getOrDefault(delivery.getId(), List.of());
            List<PriceCheckReviewResponse> responses = reviewsByDelivery.getOrDefault(delivery.getId(), List.of())
                    .stream().map(mapper::toResponse).toList();
            result.add(new Delivery(delivery.getId(), delivery.getNumber(), delivery.getDocumentDate(),
                    delivery.getSupplierCode(), delivery.getSupplierName(), delivery.getSupplierDocumentNumber(),
                    links.getOrDefault(delivery.getId(), List.of()).size(),
                    OverpaymentService.sum(lines, Line::purchaseValue),
                    OverpaymentService.sum(lines, Line::excessValue), responses.isEmpty() ? null : responses.getFirst(),
                    responses, lines));
        }
        result.sort(Comparator.<Delivery>comparingInt(delivery -> delivery.review() == null ? 0
                        : OverpaymentService.STATUS_ORDER.get(delivery.review().status()))
                .thenComparing(Delivery::documentDate, Comparator.reverseOrder())
                .thenComparing(Delivery::number, Comparator.reverseOrder()));

        return new LowMarginReport(from, to, PERIOD_DAYS, MARGIN_GAP, !required.isEmpty(),
                documentImports.findLastPeriodTo(storeId).orElse(null),
                documentImports.findNewestReportGeneratedAt(storeId).orElse(null),
                withoutMargin.stream().map(mapper::toResponse).toList(),
                suppliers.findImported(storeId).stream().map(mapper::toResponse)
                        .sorted(Comparator.comparing(LowMarginReport.Supplier::name, String.CASE_INSENSITIVE_ORDER))
                        .toList(),
                result);
    }

    /**
     * Zastępuje listę dostawców, których nie dotyczy marża: nowych dopisuje, a tych spoza listy oznacza jako usuniętych.
     * Zwraca raport po zmianie.
     */
    @Transactional
    public LowMarginReport setExcludedSuppliers(UUID storeId, List<String> supplierCodes, UUID userId) {
        documents.lockForImport(SUPPLIERS_LOCK_PREFIX + storeId);
        Set<String> wanted = supplierCodes.stream().map(String::strip)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        AppUser user = userId != null ? users.getReferenceById(userId) : null;
        Set<String> present = new HashSet<>();
        for (LowMarginExcludedSupplier item : marginExcludedSuppliers.findActive(storeId)) {
            if (wanted.contains(item.getSupplierCode())) {
                present.add(item.getSupplierCode());
            } else {
                item.markRemoved(user);
            }
        }
        List<String> added = wanted.stream().filter(code -> !present.contains(code)).toList();
        if (!added.isEmpty()) {
            Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
            Map<String, String> names = suppliers.findImported(storeId).stream()
                    .collect(Collectors.toMap(Supplier::getCode, Supplier::getName, (a, b) -> a));
            for (String code : added) {
                String name = names.get(code);
                if (name == null) {
                    throw ApiException.badRequest("Nie znaleziono dostawcy o kodzie " + code);
                }
                marginExcludedSuppliers.save(new LowMarginExcludedSupplier(store, code, name.strip(), user));
            }
        }
        marginExcludedSuppliers.flush();
        return report(storeId);
    }

    /** Artykuły z za niską marżą po przyjęciach, od największej straty. */
    private static Map<UUID, List<Line>> lowMargins(List<DeliveryMargin> articles, Map<String, BigDecimal> required,
            Set<String> excluded) {
        Map<UUID, List<Line>> lines = new HashMap<>();
        for (DeliveryMargin article : articles) {
            Line line = excluded.contains(article.getSupplierCode()) ? null
                    : lowMarginLine(article, requiredMargin(article.getNomenclatureCode(), required));
            if (line != null) {
                lines.computeIfAbsent(article.getDocumentId(), id -> new ArrayList<>()).add(line);
            }
        }
        lines.values().forEach(list -> list.sort(Comparator.comparing(Line::excessValue, Comparator.reverseOrder())));
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

    /** Linia raportu, gdy marża z ceny katalogowej jest co najmniej o próg niższa od wymaganej, inaczej {@code null}. */
    private static Line lowMarginLine(DeliveryMargin article, BigDecimal requiredMargin) {
        BigDecimal quantity = article.getCorrectedQuantity();
        BigDecimal value = article.getCorrectedValue();
        BigDecimal documentPrice = article.getSalePrice();
        if (requiredMargin == null || quantity.signum() <= 0 || value.signum() <= 0 || documentPrice == null
                || documentPrice.signum() <= 0 || article.getVatRate() == null) {
            return null;
        }
        BigDecimal regular = regularPrice(article);
        BigDecimal net = regular.multiply(HUNDRED)
                .divide(HUNDRED.add(BigDecimal.valueOf(article.getVatRate())), 6, RoundingMode.HALF_UP);
        BigDecimal price = value.divide(quantity, 4, RoundingMode.HALF_UP);
        BigDecimal margin = net.subtract(price).multiply(HUNDRED).divide(net, 2, RoundingMode.HALF_UP);
        if (margin.compareTo(requiredMargin.subtract(BigDecimal.valueOf(MARGIN_GAP))) > 0) {
            return null;
        }
        BigDecimal target = net.multiply(HUNDRED.subtract(requiredMargin)).divide(HUNDRED, 4, RoundingMode.HALF_UP);
        BigDecimal excess = price.subtract(target).multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        return new Line(article.getItm8(), article.getEan(), article.getName(), article.getBrand(),
                OverpaymentService.regionOf(article.getNomenclatureCode()), article.getSupplierCode(), quantity, price,
                value, regular, documentPrice, regular.compareTo(documentPrice) > 0 ? promotionOf(article) : null,
                margin, requiredMargin, target, excess, article.getCorrected());
    }

    /** Cena katalogowa w dniu przyjęcia: cena z dokumentu, a w promocji cena zwykła, nigdy niższa niż z dokumentu. */
    private static BigDecimal regularPrice(DeliveryMargin article) {
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
