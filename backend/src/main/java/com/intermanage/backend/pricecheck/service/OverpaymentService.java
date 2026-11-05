package com.intermanage.backend.pricecheck.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
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

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.document.entity.DeliveryCorrection;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.repository.DocumentImportRepository;
import com.intermanage.backend.document.repository.DocumentLineRepository;
import com.intermanage.backend.document.repository.DocumentLineRepository.DeliveryHistory;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.document.service.DeliveryCorrectionService;
import com.intermanage.backend.pricecheck.component.ExcludedSuppliers;
import com.intermanage.backend.pricecheck.dto.OverpaymentReport;
import com.intermanage.backend.pricecheck.dto.OverpaymentReport.Delivery;
import com.intermanage.backend.pricecheck.dto.OverpaymentReport.Line;
import com.intermanage.backend.pricecheck.dto.OverpaymentReport.Reference;
import com.intermanage.backend.pricecheck.dto.PriceCheckReviewResponse;
import com.intermanage.backend.pricecheck.dto.ReportSupplier;
import com.intermanage.backend.pricecheck.entity.PriceCheckReview;
import com.intermanage.backend.pricecheck.entity.PriceCheckStatus;
import com.intermanage.backend.pricecheck.entity.ReviewedReport;
import com.intermanage.backend.pricecheck.mapper.PriceCheckMapper;
import com.intermanage.backend.pricecheck.repository.PriceCheckReviewRepository;
import com.intermanage.backend.sales.repository.SaleRepository;

/**
 * Przepłacone artykuły. Każde przyjęcie PZ z ostatnich {@value #PERIOD_DAYS} dni porównujemy z {@value #REFERENCE_COUNT}
 * poprzednimi dostawami tego artykułu (ITM8, od każdego dostawcy): artykuł jest przepłacony, gdy cena jest o ponad
 * {@value #THRESHOLD_PERCENT}% wyższa od najniższej z nich. Taniej niż poprzednio raportu nie interesuje.
 * <p>
 * Dostawa z ilością ponad {@value #LARGE_QUANTITY_FACTOR} razy większą niż mediana artykułu nie bierze udziału
 * w porównaniu: tyle towaru to zwykle krótki termin w promocji (na produkcji 36-42% takich dostaw było o ponad 10%
 * tańszych, przy zwykłej ilości 3%). Ceny odniesienia są z bonu, sprzed korekt, bo baza oddaje promocję gazetkową
 * korektą i cena po niej nie jest ceną, po której da się kupić. Sprawdzane przyjęcie liczy się po korektach: korekta od
 * dostawcy zdejmuje przepłacenie z raportu. Mięsa, drobiu, garmażerki, owoców i warzyw raport nie sprawdza, bo wahania
 * cen są tam normalne ({@link #SKIPPED_NOMENCLATURES}), a papierosów, bo kupuje się je tylko z jednego miejsca. Pomija
 * też dostawców z {@link ExcludedSuppliers} i wybranych przez sklep ({@link ReportExcludedSupplierService}): ich
 * dostawy nie są sprawdzane ani nie są cenami odniesienia.
 */
@Service
public class OverpaymentService {

    public static final int PERIOD_DAYS = 30;
    static final int REFERENCE_COUNT = 3;
    static final int THRESHOLD_PERCENT = 5;
    static final int LARGE_QUANTITY_FACTOR = 5;
    /** Ceny odniesienia szukamy w dostawach najwyżej tyle dni przed przyjęciem. */
    static final int HISTORY_DAYS = 180;
    /** Z mniejszej liczby dostaw nie widać, jaka ilość jest zwykła. */
    private static final int MIN_DELIVERIES_FOR_MEDIAN = 3;
    /** Cena do 5 gr to cena symboliczna (towar za 0,01 zł), a nie cena zakupu. */
    private static final BigDecimal SYMBOLIC_PRICE = new BigDecimal("0.05");
    /** Początki kodów: mięso samoobsługa, mięso baza, garmażerka, drób samoobsługa, owoce i warzywa, papierosy. */
    private static final List<String> SKIPPED_NOMENCLATURES = List.of("24", "26", "36", "46", "1401", "3901");

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal THRESHOLD_FACTOR = BigDecimal.ONE
            .add(BigDecimal.valueOf(THRESHOLD_PERCENT).divide(HUNDRED));
    static final Map<PriceCheckStatus, Integer> STATUS_ORDER =
            Map.of(PriceCheckStatus.REPORTED, 1, PriceCheckStatus.RESOLVED, 2);

    private final DocumentRepository documents;
    private final DocumentLineRepository documentLines;
    private final DocumentImportRepository documentImports;
    private final DeliveryCorrectionService corrections;
    private final PriceCheckReviewRepository reviews;
    private final PriceCheckMapper mapper;
    private final SaleRepository sales;
    private final ExcludedSuppliers excludedSuppliers;
    private final ReportExcludedSupplierService reportSuppliers;

    public OverpaymentService(DocumentRepository documents, DocumentLineRepository documentLines,
            DocumentImportRepository documentImports, DeliveryCorrectionService corrections,
            PriceCheckReviewRepository reviews, PriceCheckMapper mapper, SaleRepository sales,
            ExcludedSuppliers excludedSuppliers, ReportExcludedSupplierService reportSuppliers) {
        this.documents = documents;
        this.documentLines = documentLines;
        this.documentImports = documentImports;
        this.corrections = corrections;
        this.reviews = reviews;
        this.mapper = mapper;
        this.sales = sales;
        this.excludedSuppliers = excludedSuppliers;
        this.reportSuppliers = reportSuppliers;
    }

    @Transactional(readOnly = true)
    public OverpaymentReport report(UUID storeId) {
        sales.tuneForReport();
        LocalDate to = LocalDate.now(StoreTime.ZONE);
        LocalDate from = to.minusDays(PERIOD_DAYS - 1L);
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime until = to.plusDays(1).atStartOfDay();

        Map<UUID, List<PriceCheckReview>> reviewsByDelivery = reviews.findActive(storeId, ReviewedReport.OVERPAYMENT)
                .stream().collect(Collectors.groupingBy(review -> review.getDocument().getId(), LinkedHashMap::new,
                        Collectors.toList()));
        Set<UUID> reviewed = reviewsByDelivery.keySet();
        LocalDateTime oldest = reviewsByDelivery.values().stream().flatMap(List::stream)
                .map(review -> review.getDocument().getDocumentDate()).min(Comparator.naturalOrder())
                .filter(date -> date.isBefore(start)).orElse(start);

        List<DeliveryHistory> history = documentLines.findDeliveryHistory(storeId, oldest.minusDays(HISTORY_DAYS),
                start, until, reviewed.stream().map(UUID::toString).toArray(String[]::new), SYMBOLIC_PRICE,
                THRESHOLD_FACTOR);
        List<ReportSupplier> skippedSuppliers = reportSuppliers.active(storeId, ReviewedReport.OVERPAYMENT);
        Set<String> excluded = new HashSet<>(excludedSuppliers.codes(storeId));
        skippedSuppliers.forEach(supplier -> excluded.add(supplier.code()));
        Map<UUID, List<Line>> linesByDelivery = overpaid(history, start, reviewed, excluded);

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
                    links.getOrDefault(delivery.getId(), List.of()).size(), sum(lines, Line::purchaseValue),
                    sum(lines, Line::excessValue), responses.isEmpty() ? null : responses.getFirst(), responses,
                    lines));
        }
        result.sort(Comparator.<Delivery>comparingInt(delivery -> delivery.review() == null ? 0
                        : STATUS_ORDER.get(delivery.review().status()))
                .thenComparing(Delivery::documentDate, Comparator.reverseOrder())
                .thenComparing(Delivery::number, Comparator.reverseOrder()));

        return new OverpaymentReport(from, to, PERIOD_DAYS, THRESHOLD_PERCENT, REFERENCE_COUNT, LARGE_QUANTITY_FACTOR,
                documentImports.findLastPeriodTo(storeId).orElse(null),
                documentImports.findNewestReportGeneratedAt(storeId).orElse(null), skippedSuppliers,
                reportSuppliers.options(storeId), result);
    }

    /** Zastępuje listę dostawców pomijanych w raporcie; zwraca raport po zmianie. */
    @Transactional
    public OverpaymentReport setExcludedSuppliers(UUID storeId, List<String> supplierCodes, UUID userId) {
        reportSuppliers.replace(storeId, ReviewedReport.OVERPAYMENT, supplierCodes, userId);
        return report(storeId);
    }

    /** Przepłacone artykuły po przyjęciach, od największej nadpłaty. Historia od najstarszej dostawy. */
    private static Map<UUID, List<Line>> overpaid(List<DeliveryHistory> history, LocalDateTime start,
            Set<UUID> reviewed, Set<String> excluded) {
        Map<String, List<DeliveryHistory>> byArticle = history.stream()
                .filter(delivery -> !skipped(delivery.getNomenclatureCode()))
                .filter(delivery -> !excluded.contains(delivery.getSupplierCode()))
                .collect(Collectors.groupingBy(DeliveryHistory::getItm8, LinkedHashMap::new, Collectors.toList()));
        Map<UUID, List<Line>> lines = new HashMap<>();
        for (List<DeliveryHistory> article : byArticle.values()) {
            BigDecimal largeFrom = largeQuantity(article);
            Deque<DeliveryHistory> references = new ArrayDeque<>(REFERENCE_COUNT);
            for (DeliveryHistory delivery : article) {
                BigDecimal price = price(delivery);
                if ((largeFrom != null && delivery.getQuantity().compareTo(largeFrom) > 0)
                        || price.compareTo(SYMBOLIC_PRICE) <= 0) {
                    continue;
                }
                boolean checked = !delivery.getDocumentDate().isBefore(start)
                        || reviewed.contains(delivery.getDocumentId());
                Line line = checked ? overpaidLine(delivery, references) : null;
                if (line != null) {
                    lines.computeIfAbsent(delivery.getDocumentId(), id -> new ArrayList<>()).add(line);
                }
                if (references.size() == REFERENCE_COUNT) {
                    references.removeFirst();
                }
                references.addLast(delivery);
            }
        }
        lines.values().forEach(list -> list.sort(Comparator.comparing(Line::excessValue, Comparator.reverseOrder())));
        return lines;
    }

    private static boolean skipped(String nomenclatureCode) {
        return nomenclatureCode != null && SKIPPED_NOMENCLATURES.stream()
                .anyMatch(nomenclatureCode.replaceAll("\\D", "")::startsWith);
    }

    /** Ilość, ponad którą dostawa jest nienormalna; {@code null}, gdy dostaw jest za mało, żeby to ocenić. */
    private static BigDecimal largeQuantity(List<DeliveryHistory> article) {
        if (article.size() < MIN_DELIVERIES_FOR_MEDIAN) {
            return null;
        }
        List<BigDecimal> quantities = article.stream().map(DeliveryHistory::getQuantity).sorted().toList();
        int middle = quantities.size() / 2;
        BigDecimal median = quantities.size() % 2 == 1 ? quantities.get(middle)
                : quantities.get(middle - 1).add(quantities.get(middle)).divide(BigDecimal.TWO);
        return median.multiply(BigDecimal.valueOf(LARGE_QUANTITY_FACTOR));
    }

    /** Linia raportu, gdy cena po korektach przekracza próg ponad najniższą cenę odniesienia, inaczej {@code null}. */
    private static Line overpaidLine(DeliveryHistory delivery, Deque<DeliveryHistory> references) {
        BigDecimal quantity = delivery.getCorrectedQuantity();
        BigDecimal value = delivery.getCorrectedValue();
        if (references.isEmpty() || quantity.signum() <= 0 || value.signum() <= 0) {
            return null;
        }
        BigDecimal price = value.divide(quantity, 4, RoundingMode.HALF_UP);
        BigDecimal lowest = references.stream().map(OverpaymentService::price).min(Comparator.naturalOrder())
                .orElseThrow();
        if (price.compareTo(lowest.multiply(THRESHOLD_FACTOR)) <= 0) {
            return null;
        }
        BigDecimal percent = price.subtract(lowest).multiply(HUNDRED).divide(lowest, 1, RoundingMode.HALF_UP);
        BigDecimal excess = price.subtract(lowest).multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        List<Reference> shown = new ArrayList<>(references.size());
        references.descendingIterator().forEachRemaining(reference -> shown.add(new Reference(
                reference.getDocumentId(), reference.getDocumentNumber(), reference.getDocumentDate(),
                reference.getSupplierCode(), reference.getSupplierName(), reference.getQuantity(),
                price(reference))));
        return new Line(delivery.getItm8(), delivery.getEan(), delivery.getName(), delivery.getBrand(),
                regionOf(delivery.getNomenclatureCode()), delivery.getSupplierCode(), quantity, price, value, lowest,
                percent, excess, delivery.getCorrected(), shown);
    }

    /** Cena z bonu, sprzed korekt. */
    private static BigDecimal price(DeliveryHistory delivery) {
        return delivery.getPurchaseValue().divide(delivery.getQuantity(), 4, RoundingMode.HALF_UP);
    }

    /** Rejon z kodu nomenklatury linii ("570101" albo "57 01 01" -> "57"). */
    static String regionOf(String nomenclatureCode) {
        if (nomenclatureCode == null) {
            return null;
        }
        String digits = nomenclatureCode.replaceAll("\\D", "");
        return digits.length() < 2 ? null : digits.substring(0, 2);
    }

    static <T> BigDecimal sum(List<T> items, Function<T, BigDecimal> value) {
        return items.stream().map(value).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
