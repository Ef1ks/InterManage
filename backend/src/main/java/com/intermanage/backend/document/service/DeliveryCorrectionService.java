package com.intermanage.backend.document.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.dto.CorrectedDelivery.CorrectedLine;
import com.intermanage.backend.document.dto.CorrectedDelivery;
import com.intermanage.backend.document.dto.CorrectionCandidate;
import com.intermanage.backend.document.dto.CorrectionTotals;
import com.intermanage.backend.document.entity.DeliveryCorrection;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentLine;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.repository.DeliveryCorrectionRepository;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.document.repository.DocumentSearch;

/**
 * Korekty błędów sklepu (PK) i od dostawcy (WZ) przypięte do przyjęć PZ, których dotyczą. Raport nie mówi, którą
 * dostawę poprawia korekta, więc przypina się ją ręcznie z obu stron; podpowiedzi są od tego samego dostawcy i od
 * największej liczby wspólnych artykułów. Ilości i kwoty w korektach są ze znakiem, więc przyjęcie po korektach to
 * linie przyjęcia z dodanymi liniami korekt o tym samym ITM8 ({@link #corrected}).
 */
@Service
public class DeliveryCorrectionService {

    /** Przyjęcie do korekty szukamy najwyżej tyle dni przed nią. */
    static final int DELIVERY_LOOKBACK_DAYS = 120;
    /** Korekty do przyjęcia szukamy najwyżej tyle dni po nim. */
    static final int CORRECTION_LOOKAHEAD_DAYS = 365;
    private static final int CANDIDATES = 30;
    private static final List<DocumentType> CORRECTION_TYPES = List.of(DocumentType.PK, DocumentType.WZ);

    private final DeliveryCorrectionRepository links;
    private final DocumentRepository documents;
    private final AppUserRepository users;

    public DeliveryCorrectionService(DeliveryCorrectionRepository links, DocumentRepository documents,
            AppUserRepository users) {
        this.links = links;
        this.documents = documents;
        this.users = users;
    }

    /** Korekty przyjęć PZ spośród podanych dokumentów (same nagłówki korekt), od najstarszej. */
    @Transactional(readOnly = true)
    public Map<UUID, List<DeliveryCorrection>> correctionsOf(Collection<Document> page) {
        List<UUID> deliveries = page.stream().filter(document -> document.getType() == DocumentType.PZ)
                .map(Document::getId).toList();
        if (deliveries.isEmpty()) {
            return Map.of();
        }
        return links.findActiveByDeliveryIds(deliveries).stream()
                .collect(Collectors.groupingBy(link -> link.getDelivery().getId(), LinkedHashMap::new,
                        Collectors.toList()));
    }

    /** Przyjęcia, do których przypięto korekty spośród podanych dokumentów (klucz: id korekty). */
    @Transactional(readOnly = true)
    public Map<UUID, Document> deliveriesOf(Collection<Document> page) {
        List<UUID> corrections = page.stream().filter(document -> document.getType().isDeliveryCorrection())
                .map(Document::getId).toList();
        if (corrections.isEmpty()) {
            return Map.of();
        }
        return links.findActiveByCorrectionIds(corrections).stream()
                .collect(Collectors.toMap(link -> link.getCorrection().getId(), DeliveryCorrection::getDelivery));
    }

    /** Liczba korekt i wartości przyjęcia razem z nimi; {@code null}, gdy przyjęcie nie ma korekt. */
    public CorrectionTotals totals(Document delivery, List<DeliveryCorrection> corrections) {
        if (corrections == null || corrections.isEmpty()) {
            return null;
        }
        BigDecimal purchase = delivery.getPurchaseValue();
        BigDecimal sale = delivery.getSaleValueGross();
        for (DeliveryCorrection link : corrections) {
            purchase = purchase.add(link.getCorrection().getPurchaseValue());
            sale = sale.add(link.getCorrection().getSaleValueGross());
        }
        return new CorrectionTotals(corrections.size(), purchase, sale);
    }

    /** Korekty przyjęcia razem z liniami, w kolejności przypięcia ({@link #correctionsOf}). */
    @Transactional(readOnly = true)
    public List<Document> correctionDocuments(UUID storeId, List<DeliveryCorrection> corrections) {
        if (corrections.isEmpty()) {
            return List.of();
        }
        Map<UUID, Document> withLines = documents.findWithLines(storeId,
                        corrections.stream().map(link -> link.getCorrection().getId()).toList())
                .stream().collect(Collectors.toMap(Document::getId, Function.identity()));
        return corrections.stream().map(link -> withLines.get(link.getCorrection().getId())).toList();
    }

    /**
     * Dokumenty do przypięcia: do przyjęcia jego korekty z okresu {@link #CORRECTION_LOOKAHEAD_DAYS} dni po nim, do
     * korekty przyjęcia z {@link #DELIVERY_LOOKBACK_DAYS} dni przed nią. Zawsze od tego samego dostawcy, najpierw te
     * z największą liczbą wspólnych artykułów, potem najbliższe w czasie. Korekty przypięte do innego przyjęcia
     * odpadają. {@code query}: słowa z numeru dokumentu albo numeru dokumentu dostawcy.
     */
    @Transactional(readOnly = true)
    public List<CorrectionCandidate> candidates(UUID storeId, String typeCode, UUID id, String query) {
        Document document = documents.findWithLines(storeId, type(typeCode), id)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu"));
        String supplierCode = supplierCode(document);
        if (supplierCode == null) {
            return List.of();
        }
        boolean delivery = requireCorrectable(document);
        LocalDateTime day = document.getDocumentDate().toLocalDate().atStartOfDay();
        List<Document> found = delivery
                ? documents.findBySupplierWithLines(storeId, CORRECTION_TYPES, supplierCode, day,
                        day.plusDays(CORRECTION_LOOKAHEAD_DAYS + 1L))
                : documents.findBySupplierWithLines(storeId, List.of(DocumentType.PZ), supplierCode,
                        day.minusDays(DELIVERY_LOOKBACK_DAYS), day.plusDays(1));
        Set<UUID> taken = new HashSet<>();
        if (delivery && !found.isEmpty()) {
            links.findActiveByCorrectionIds(found.stream().map(Document::getId).toList())
                    .forEach(link -> taken.add(link.getCorrection().getId()));
        }
        Set<String> items = itm8s(document);
        List<String> words = DocumentSearch.words(query);
        Comparator<CorrectionCandidate> closest = Comparator.comparing(CorrectionCandidate::documentDate);
        return found.stream()
                .filter(candidate -> !taken.contains(candidate.getId()) && matches(candidate, words))
                .map(candidate -> new CorrectionCandidate(candidate.getId(), candidate.getType().name(),
                        candidate.getType().singular(), candidate.getNumber(), candidate.getDocumentDate(),
                        candidate.getSupplierName(), candidate.getSupplierDocumentNumber(), candidate.getLineCount(),
                        candidate.getPurchaseValue(),
                        (int) itm8s(candidate).stream().filter(items::contains).count()))
                .sorted(Comparator.comparing(CorrectionCandidate::commonItems).reversed()
                        .thenComparing(delivery ? closest : closest.reversed()))
                .limit(CANDIDATES)
                .toList();
    }

    /**
     * Przypina korektę do przyjęcia; {@code id} to dokument ze ścieżki (przyjęcie albo korekta), {@code otherId} -
     * druga strona. Korekta przypięta już do tego przyjęcia zostaje bez zmian, do innego daje 409.
     */
    @Transactional
    public void attach(UUID storeId, String typeCode, UUID id, UUID otherId, UUID userId) {
        Document[] pair = pair(storeId, typeCode, id, otherId);
        Document delivery = pair[0];
        Document correction = pair[1];
        String deliverySupplier = supplierCode(delivery);
        String correctionSupplier = supplierCode(correction);
        if (deliverySupplier != null && correctionSupplier != null && !deliverySupplier.equals(correctionSupplier)) {
            throw ApiException.badRequest(correction.getType().singular() + " " + correction.getNumber()
                    + " jest od innego dostawcy niż przyjęcie (bon " + delivery.bonNumber() + ").");
        }
        DeliveryCorrection current = links.findActiveByCorrection(correction.getId()).orElse(null);
        if (current != null) {
            if (current.getDelivery().getId().equals(delivery.getId())) {
                return;
            }
            throw ApiException.conflict(correction.getType().singular() + " " + correction.getNumber()
                    + " jest już przypięta do przyjęcia (bon " + current.getDelivery().bonNumber()
                    + "). Najpierw ją odepnij.");
        }
        links.save(new DeliveryCorrection(delivery, correction, userId != null ? users.getReferenceById(userId) : null));
    }

    /** Odpina korektę od przyjęcia (znacznik, wpis zostaje); ponowne przypięcie to „Przywróć”. */
    @Transactional
    public void detach(UUID storeId, String typeCode, UUID id, UUID otherId, UUID userId) {
        Document[] pair = pair(storeId, typeCode, id, otherId);
        links.findActiveByCorrection(pair[1].getId())
                .filter(link -> link.getDelivery().getId().equals(pair[0].getId()))
                .orElseThrow(() -> ApiException.notFound("Ta korekta nie jest przypięta do przyjęcia"))
                .unlink(userId != null ? users.getReferenceById(userId) : null);
    }

    /**
     * Przyjęcie po korektach: do każdej linii przyjęcia dochodzą linie korekt z tym samym ITM8 (ilości i kwoty
     * w korektach są ze znakiem: zamiana artykułów w korekcie błędu sklepu, obniżka ceny w korekcie od dostawcy),
     * a artykuły, których w przyjęciu nie było, są dopisane na końcu. Cena zakupu to wartość / ilość.
     */
    public CorrectedDelivery corrected(Document delivery, List<Document> corrections) {
        Map<String, List<DocumentLine>> byItm8 = new LinkedHashMap<>();
        for (Document correction : corrections) {
            for (DocumentLine line : correction.getLines()) {
                byItm8.computeIfAbsent(line.getItm8(), itm8 -> new ArrayList<>()).add(line);
            }
        }
        List<CorrectedLine> lines = new ArrayList<>();
        Set<String> applied = new HashSet<>();
        int lastNumber = 0;
        for (DocumentLine line : delivery.getLines()) {
            List<DocumentLine> extra = applied.add(line.getItm8())
                    ? byItm8.getOrDefault(line.getItm8(), List.of()) : List.of();
            lines.add(merge(line.getId().toString(), line.getLineNumber(), line, extra, true));
            lastNumber = Math.max(lastNumber, line.getLineNumber());
        }
        for (Map.Entry<String, List<DocumentLine>> entry : byItm8.entrySet()) {
            if (!applied.contains(entry.getKey())) {
                List<DocumentLine> added = entry.getValue();
                lines.add(merge(added.getFirst().getId().toString(), ++lastNumber, added.getFirst(),
                        added.subList(1, added.size()), false));
            }
        }
        return new CorrectedDelivery(
                lines.stream().map(CorrectedLine::purchaseValue).filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                lines.stream().map(CorrectedLine::saleValueGross).filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                lines);
    }

    /** {@code base} z linii przyjęcia (albo pierwszej linii korekty przy artykule dopisanym), plus {@code extra}. */
    private static CorrectedLine merge(String key, int lineNumber, DocumentLine base, List<DocumentLine> extra,
            boolean fromDelivery) {
        boolean corrected = !fromDelivery || !extra.isEmpty();
        if (!corrected) {
            return new CorrectedLine(key, lineNumber, base.getItm8(), base.getEan(), base.getName(), base.getBrand(),
                    base.getUnit(), base.getNomenclatureCode(), base.getQuantity(), base.getPurchasePrice(),
                    base.getSalePriceDocument(), base.getPurchaseValue(), base.getSaleValueGross(),
                    base.getSaleValueNet(), base.getVatRate(), base.getVatValue(), base.getMarginValue(),
                    base.getMarginPercent(), base.getQuantity(), base.getPurchaseValue(), false);
        }
        List<DocumentLine> all = new ArrayList<>(extra.size() + 1);
        all.add(base);
        all.addAll(extra);
        BigDecimal quantity = sum(all, DocumentLine::getQuantity);
        BigDecimal purchaseValue = sum(all, DocumentLine::getPurchaseValue);
        BigDecimal saleValueGross = sum(all, DocumentLine::getSaleValueGross);
        BigDecimal marginValue = sum(all, DocumentLine::getMarginValue);
        BigDecimal purchasePrice = quantity != null && quantity.signum() != 0 && purchaseValue != null
                ? purchaseValue.divide(quantity, 4, RoundingMode.HALF_UP)
                : all.reversed().stream().map(DocumentLine::getPurchasePrice).filter(Objects::nonNull).findFirst()
                        .orElse(null);
        BigDecimal marginPercent = saleValueGross != null && saleValueGross.signum() != 0 && marginValue != null
                ? marginValue.multiply(BigDecimal.valueOf(100)).divide(saleValueGross, 2, RoundingMode.HALF_UP)
                : null;
        return new CorrectedLine(key, lineNumber, base.getItm8(), base.getEan(), base.getName(), base.getBrand(),
                base.getUnit(), base.getNomenclatureCode(), quantity, purchasePrice, base.getSalePriceDocument(),
                purchaseValue, saleValueGross, sum(all, DocumentLine::getSaleValueNet), base.getVatRate(),
                sum(all, DocumentLine::getVatValue), marginValue, marginPercent,
                fromDelivery ? base.getQuantity() : null, fromDelivery ? base.getPurchaseValue() : null, true);
    }

    private static BigDecimal sum(List<DocumentLine> lines, Function<DocumentLine, BigDecimal> value) {
        return lines.stream().map(value).filter(Objects::nonNull).reduce(BigDecimal::add).orElse(null);
    }

    /** [przyjęcie, korekta] z dokumentu ze ścieżki i drugiej strony. */
    private Document[] pair(UUID storeId, String typeCode, UUID id, UUID otherId) {
        Document document = documents.findHeader(storeId, type(typeCode), id)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu"));
        boolean delivery = requireCorrectable(document);
        Document other = documents.findHeader(storeId, otherId)
                .filter(found -> delivery ? found.getType().isDeliveryCorrection() : found.getType() == DocumentType.PZ)
                .orElseThrow(() -> ApiException.notFound(delivery ? "Nie znaleziono korekty"
                        : "Nie znaleziono przyjęcia towaru"));
        return delivery ? new Document[] {document, other} : new Document[] {other, document};
    }

    /** {@code true} dla przyjęcia, {@code false} dla korekty; inne typy dają 400. */
    private static boolean requireCorrectable(Document document) {
        if (document.getType() == DocumentType.PZ) {
            return true;
        }
        if (document.getType().isDeliveryCorrection()) {
            return false;
        }
        throw ApiException.badRequest("Korekty przypina się do przyjęć towaru");
    }

    /** Wspólny dostawca dokumentu, a gdy linie mają różnych - dostawca większości linii. */
    private static String supplierCode(Document document) {
        if (document.getSupplierCode() != null) {
            return document.getSupplierCode();
        }
        return document.getLines().stream().map(DocumentLine::getSupplierCode).filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private static Set<String> itm8s(Document document) {
        return document.getLines().stream().map(DocumentLine::getItm8).collect(Collectors.toSet());
    }

    private static boolean matches(Document document, List<String> words) {
        String text = (document.getNumber() + " " + document.bonNumber() + " "
                + Objects.toString(document.getSupplierDocumentNumber(), "")).toLowerCase(Locale.ROOT);
        return words.stream().allMatch(text::contains);
    }

    private static DocumentType type(String code) {
        return DocumentType.fromCode(code).orElseThrow(() -> ApiException.notFound("Nieznany typ dokumentu"));
    }
}
