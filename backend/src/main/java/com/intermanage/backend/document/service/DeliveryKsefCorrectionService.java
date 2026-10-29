package com.intermanage.backend.document.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.dto.DeliveryKsefCorrections;
import com.intermanage.backend.document.dto.LinkedKsefInvoice;
import com.intermanage.backend.document.entity.DeliveryCorrection;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentKsefInvoice;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.repository.DeliveryCorrectionRepository;
import com.intermanage.backend.document.repository.DocumentKsefInvoiceRepository;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.ksef.entity.KsefInternalId;
import com.intermanage.backend.ksef.entity.KsefInvoice;
import com.intermanage.backend.ksef.repository.KsefInternalIdRepository;
import com.intermanage.backend.ksef.repository.KsefInvoiceRepository;
import com.intermanage.backend.ksef.repository.KsefInvoiceSearch;
import com.intermanage.backend.ksef.service.KsefCorrectedInvoiceService;

/**
 * Korekty z KSeF przy przyjęciu PZ: faktura korygowana z XML korekty ({@link KsefCorrectedInvoiceService}) to faktura
 * przyjęcia (przypięta albo dopasowana po numerze). Łączy po numerze KSeF faktury korygowanej, a bez niego po NIP-ie
 * sprzedawcy i numerze faktury. Korekta z KSeF trafia do raportów przez korektę od dostawcy (WZ) przypiętą do
 * przyjęcia, więc przy każdej widać WZ z tą fakturą albo podpowiedzi WZ (ten sam dostawca, do
 * {@link DocumentKsefLinkService#AUTO_MATCH_DAYS} dni od faktury, kwota w granicach
 * {@link DocumentKsefLinkService#AMOUNT_TOLERANCE_PERCENT}%, najmniej {@link #MIN_TOLERANCE} zł), a jedno kliknięcie
 * przypina fakturę do WZ i WZ do przyjęcia. Korekty z KSeF nie zmieniają zgodności kwot przyjęcia z fakturą, dopóki
 * nie są przypięte do WZ.
 */
@Service
public class DeliveryKsefCorrectionService {

    static final BigDecimal MIN_TOLERANCE = BigDecimal.ONE;
    private static final int CANDIDATES = 3;

    private final DocumentRepository documents;
    private final DocumentKsefInvoiceRepository ksefInvoiceLinks;
    private final DeliveryCorrectionRepository deliveryCorrections;
    private final KsefInvoiceRepository invoices;
    private final KsefInternalIdRepository internalIds;
    private final DocumentKsefLinkService ksefLinks;
    private final DeliveryCorrectionService corrections;

    public DeliveryKsefCorrectionService(DocumentRepository documents, DocumentKsefInvoiceRepository ksefInvoiceLinks,
            DeliveryCorrectionRepository deliveryCorrections, KsefInvoiceRepository invoices,
            KsefInternalIdRepository internalIds, DocumentKsefLinkService ksefLinks,
            DeliveryCorrectionService corrections) {
        this.documents = documents;
        this.ksefInvoiceLinks = ksefInvoiceLinks;
        this.deliveryCorrections = deliveryCorrections;
        this.invoices = invoices;
        this.internalIds = internalIds;
        this.ksefLinks = ksefLinks;
        this.corrections = corrections;
    }

    @Transactional(readOnly = true)
    public DeliveryKsefCorrections of(UUID storeId, UUID deliveryId) {
        Document delivery = delivery(storeId, deliveryId);
        String nip = ksefLinks.nip(storeId);
        List<KsefInvoice> originals = originals(storeId, delivery);
        if (nip == null || originals.isEmpty()) {
            return new DeliveryKsefCorrections(List.of(), 0);
        }
        List<KsefInvoice> found = correctionsOf(storeId, nip, originals);
        Set<String> sellers = originals.stream().map(KsefInvoice::getSellerNip)
                .filter(seller -> !KsefCorrectedInvoiceService.IGNORED_SELLER_NIPS.contains(seller))
                .collect(Collectors.toSet());
        long pending = sellers.isEmpty() ? 0 : invoices.countUnreadCorrections(nip, sellers);
        if (found.isEmpty()) {
            return new DeliveryKsefCorrections(List.of(), pending);
        }

        Map<UUID, List<Document>> withInvoice = ksefInvoiceLinks
                .findActiveByInvoiceIds(storeId, found.stream().map(KsefInvoice::getId).toList()).stream()
                .collect(Collectors.groupingBy(link -> link.getInvoice().getId(),
                        Collectors.mapping(DocumentKsefInvoice::getDocument, Collectors.toList())));
        List<Document> pool = wzCandidates(storeId, delivery, found);
        List<UUID> documentIds = new ArrayList<>(pool.stream().map(Document::getId).toList());
        withInvoice.values().forEach(list -> list.forEach(document -> documentIds.add(document.getId())));
        Map<UUID, DeliveryCorrection> attachments = documentIds.isEmpty() ? Map.of()
                : deliveryCorrections.findActiveByCorrectionIds(documentIds).stream()
                        .collect(Collectors.toMap(link -> link.getCorrection().getId(), Function.identity(),
                                (first, second) -> first));
        // WZ, które mają już fakturę z KSeF, nie są podpowiadane do innej korekty.
        Set<UUID> invoiced = pool.isEmpty() ? Set.of()
                : ksefInvoiceLinks.findActiveByDocumentIds(pool.stream().map(Document::getId).toList()).stream()
                        .map(link -> link.getDocument().getId()).collect(Collectors.toSet());

        List<DeliveryKsefCorrections.Correction> result = new ArrayList<>();
        for (KsefInvoice correction : found) {
            List<Document> linked = withInvoice.getOrDefault(correction.getId(), List.of());
            List<DeliveryKsefCorrections.LinkedDocument> linkedDocuments = linked.stream()
                    .map(document -> {
                        DeliveryCorrection attachment = attachments.get(document.getId());
                        boolean attached = attachment != null
                                && attachment.getDelivery().getId().equals(delivery.getId());
                        return new DeliveryKsefCorrections.LinkedDocument(document.getId(), document.getType().name(),
                                document.getType().singular(), document.getNumber(), document.getDocumentDate(),
                                document.getPurchaseValue(), attached,
                                attachment != null && !attached ? attachment.getDelivery().bonNumber() : null);
                    })
                    .toList();
            List<DeliveryKsefCorrections.Candidate> candidates = linked.isEmpty()
                    ? candidates(delivery, correction, pool, invoiced, attachments) : List.of();
            result.add(new DeliveryKsefCorrections.Correction(correction.getId(), correction.getKsefNumber(),
                    correction.getInvoiceNumber(), correction.getIssueDate(), correction.getSellerName(),
                    correction.getNetAmount(), correction.getGrossAmount(), correctedNumber(correction, originals),
                    linkedDocuments, candidates));
        }
        return new DeliveryKsefCorrections(result, pending);
    }

    /**
     * Przypina korektę z KSeF przyjęcia do korekty od dostawcy (WZ), a WZ do przyjęcia, jeśli jeszcze nie jest.
     * Zwraca, czy WZ została przypięta do przyjęcia teraz (cofnięcie wtedy ją odpina).
     */
    @Transactional
    public boolean link(UUID storeId, UUID deliveryId, UUID invoiceId, UUID documentId, UUID userId) {
        requireCorrection(storeId, deliveryId, invoiceId);
        Document document = documents.findHeader(storeId, DocumentType.WZ, documentId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono korekty od dostawcy"));
        boolean attached = deliveryCorrections.findActiveByCorrection(document.getId())
                .filter(link -> link.getDelivery().getId().equals(deliveryId)).isPresent();
        ksefLinks.link(storeId, document.getId(), invoiceId, userId);
        if (!attached) {
            corrections.attach(storeId, DocumentType.PZ.name(), deliveryId, document.getId(), userId);
        }
        return !attached;
    }

    /** Cofnięcie {@link #link}: odpina fakturę od WZ, a z {@code detach} także WZ od przyjęcia (znaczniki). */
    @Transactional
    public void unlink(UUID storeId, UUID deliveryId, UUID invoiceId, UUID documentId, boolean detach, UUID userId) {
        delivery(storeId, deliveryId);
        ksefLinks.unlink(storeId, documentId, invoiceId, userId);
        if (detach) {
            corrections.detach(storeId, DocumentType.PZ.name(), deliveryId, documentId, userId);
        }
    }

    private void requireCorrection(UUID storeId, UUID deliveryId, UUID invoiceId) {
        Document delivery = delivery(storeId, deliveryId);
        String nip = ksefLinks.nip(storeId);
        if (nip == null || correctionsOf(storeId, nip, originals(storeId, delivery)).stream()
                .noneMatch(correction -> correction.getId().equals(invoiceId))) {
            throw ApiException.notFound("Ta korekta z KSeF nie dotyczy faktury przyjęcia");
        }
    }

    private Document delivery(UUID storeId, UUID deliveryId) {
        return documents.findHeader(storeId, DocumentType.PZ, deliveryId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono przyjęcia"));
    }

    /** Faktury KSeF przyjęcia (przypięte i po numerze), bez faktur jego korekt. */
    private List<KsefInvoice> originals(UUID storeId, Document delivery) {
        List<UUID> ids = ksefLinks.invoicesOf(storeId, List.of(delivery)).getOrDefault(delivery.getId(), List.of())
                .stream().filter(invoice -> !invoice.correction()).map(LinkedKsefInvoice::id).toList();
        return ids.isEmpty() ? List.of() : invoices.findAllById(ids);
    }

    /** Korekty widoczne w sklepie, które korygują którąś z faktur, od najstarszej. */
    private List<KsefInvoice> correctionsOf(UUID storeId, String nip, List<KsefInvoice> originals) {
        if (originals.isEmpty()) {
            return List.of();
        }
        Set<String> own = internalIds.findActive(storeId).stream().map(KsefInternalId::getInternalId)
                .collect(Collectors.toSet());
        return invoices.findCorrectionsOf(nip, originals.stream().map(KsefInvoice::getKsefNumber).toList(),
                        originals.stream().map(invoice -> upper(invoice.getInvoiceNumber())).toList()).stream()
                .filter(correction -> KsefInvoiceSearch.visibleInStore(correction, own)
                        && correctedNumber(correction, originals) != null)
                .sorted(Comparator.comparing(KsefInvoice::getIssueDate).thenComparing(KsefInvoice::getInvoiceNumber))
                .toList();
    }

    /** Numer faktury przyjęcia, którą koryguje korekta ({@code null}, gdy żadnej). */
    private static String correctedNumber(KsefInvoice correction, List<KsefInvoice> originals) {
        for (KsefInvoice original : originals) {
            for (KsefInvoice.CorrectedRef ref : correction.getCorrectedInvoices()) {
                boolean byKsefNumber = original.getKsefNumber().equals(ref.ksefNumber());
                boolean byNumber = ref.invoiceNumber() != null
                        && correction.getSellerNip().equals(original.getSellerNip())
                        && upper(ref.invoiceNumber()).equals(upper(original.getInvoiceNumber()));
                if (byKsefNumber || byNumber) {
                    return original.getInvoiceNumber();
                }
            }
        }
        return null;
    }

    /** Korekty od dostawcy przyjęcia z okresu wokół dat korekt z KSeF. */
    private List<Document> wzCandidates(UUID storeId, Document delivery, List<KsefInvoice> found) {
        String supplierCode = delivery.getSupplierCode();
        if (supplierCode == null) {
            return List.of();
        }
        LocalDateTime from = found.stream().map(KsefInvoice::getIssueDate).min(Comparator.naturalOrder()).orElseThrow()
                .minusDays(DocumentKsefLinkService.AUTO_MATCH_DAYS).atStartOfDay();
        LocalDateTime to = found.stream().map(KsefInvoice::getIssueDate).max(Comparator.naturalOrder()).orElseThrow()
                .plusDays(DocumentKsefLinkService.AUTO_MATCH_DAYS + 1L).atStartOfDay();
        Specification<Document> matching = (root, query, cb) -> cb.and(
                cb.equal(root.get("store").get("id"), storeId),
                cb.equal(root.get("type"), DocumentType.WZ),
                cb.equal(root.get("supplierCode"), supplierCode),
                cb.greaterThanOrEqualTo(root.get("documentDate"), from),
                cb.lessThan(root.get("documentDate"), to));
        return documents.findAll(matching);
    }

    /**
     * WZ do korekty z KSeF: bez faktury z KSeF, nieprzypięte do innego przyjęcia, z datą do
     * {@link DocumentKsefLinkService#AUTO_MATCH_DAYS} dni od faktury i kwotą w granicach tolerancji; najpierw
     * przypięte już do przyjęcia, potem od najmniejszej różnicy kwot i najbliższej daty.
     */
    private static List<DeliveryKsefCorrections.Candidate> candidates(Document delivery, KsefInvoice correction,
            List<Document> pool, Set<UUID> invoiced, Map<UUID, DeliveryCorrection> attachments) {
        BigDecimal amount = abs(correction.getNetAmount());
        BigDecimal tolerance = amount.multiply(DocumentKsefLinkService.AMOUNT_TOLERANCE_PERCENT).movePointLeft(2)
                .max(MIN_TOLERANCE);
        LocalDate issued = correction.getIssueDate();
        return pool.stream()
                .filter(document -> !invoiced.contains(document.getId()))
                .filter(document -> {
                    DeliveryCorrection attachment = attachments.get(document.getId());
                    return attachment == null || attachment.getDelivery().getId().equals(delivery.getId());
                })
                .filter(document -> Math.abs(ChronoUnit.DAYS.between(issued,
                        document.getDocumentDate().toLocalDate())) <= DocumentKsefLinkService.AUTO_MATCH_DAYS)
                .map(document -> new DeliveryKsefCorrections.Candidate(document.getId(), document.getNumber(),
                        document.getDocumentDate(), document.getPurchaseValue(),
                        abs(document.getPurchaseValue()).subtract(amount).abs(),
                        attachments.containsKey(document.getId())))
                .filter(candidate -> candidate.difference().compareTo(tolerance) <= 0)
                .sorted(Comparator.comparing(DeliveryKsefCorrections.Candidate::attached).reversed()
                        .thenComparing(DeliveryKsefCorrections.Candidate::difference)
                        .thenComparing(candidate -> Math.abs(ChronoUnit.DAYS.between(issued,
                                candidate.documentDate().toLocalDate()))))
                .limit(CANDIDATES)
                .toList();
    }

    private static BigDecimal abs(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.abs();
    }

    private static String upper(String value) {
        return value.toUpperCase(Locale.ROOT);
    }
}
