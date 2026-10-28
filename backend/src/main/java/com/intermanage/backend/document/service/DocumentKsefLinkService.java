package com.intermanage.backend.document.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.dto.AutoInvoiceMatch;
import com.intermanage.backend.document.dto.AutoInvoiceMatchesRequest;
import com.intermanage.backend.document.dto.DocumentCandidate;
import com.intermanage.backend.document.dto.KsefInvoiceCandidate;
import com.intermanage.backend.document.dto.LinkedKsefInvoice;
import com.intermanage.backend.document.entity.DeliveryCorrection;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentKsefInvoice;
import com.intermanage.backend.document.entity.DocumentManualInvoice;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.repository.DocumentKsefInvoiceRepository;
import com.intermanage.backend.document.repository.DocumentManualInvoiceRepository;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.document.repository.DocumentSearch;
import com.intermanage.backend.ksef.entity.KsefConnection;
import com.intermanage.backend.ksef.entity.KsefInternalId;
import com.intermanage.backend.ksef.entity.KsefInvoice;
import com.intermanage.backend.ksef.repository.KsefConnectionRepository;
import com.intermanage.backend.ksef.repository.KsefInternalIdRepository;
import com.intermanage.backend.ksef.repository.KsefInvoiceRepository;
import com.intermanage.backend.ksef.repository.KsefInvoiceSearch;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Faktury KSeF przy dokumentach sklepu (przyjęcia PZ i korekty od dostawcy). Przyjęcie z numerem faktury łączy się
 * samo (numer dokumentu dostawcy = numer faktury), pozostałe przypina się ręcznie z obu stron: z dokumentu albo
 * z listy faktur. Podpowiedzi mają domyślnie kwotę w granicach {@link #AMOUNT_TOLERANCE_PERCENT}% i są ułożone
 * od tego samego dostawcy i najbliższej kwoty. Przypięcie oznacza fakturę jako wprowadzoną.
 */
@Service
public class DocumentKsefLinkService {

    /** Domyślny zakres podpowiedzi: kwota netto faktury w granicach ±10% wartości zakupu dokumentu. */
    public static final BigDecimal AMOUNT_TOLERANCE_PERCENT = BigDecimal.TEN;
    /** Automatyczne przypięcie: kwota netto faktury najwyżej o tyle różna od wartości zakupu przyjęcia. */
    public static final BigDecimal AUTO_MATCH_TOLERANCE = BigDecimal.TEN;
    /** Automatyczne przypięcie: faktura wystawiona najwyżej tyle dni przed albo po przyjęciu. */
    public static final int AUTO_MATCH_DAYS = 45;
    private static final int SCANNED = 200;
    private static final int CANDIDATES = 30;

    private final DocumentKsefInvoiceRepository links;
    private final DocumentManualInvoiceRepository manualInvoices;
    private final DocumentRepository documents;
    private final KsefInvoiceRepository invoices;
    private final KsefConnectionRepository connections;
    private final KsefInternalIdRepository internalIds;
    private final SupplierRepository suppliers;
    private final AppUserRepository users;

    public DocumentKsefLinkService(DocumentKsefInvoiceRepository links, DocumentRepository documents,
            KsefInvoiceRepository invoices, KsefConnectionRepository connections,
            KsefInternalIdRepository internalIds, SupplierRepository suppliers, AppUserRepository users,
            DocumentManualInvoiceRepository manualInvoices) {
        this.links = links;
        this.manualInvoices = manualInvoices;
        this.documents = documents;
        this.invoices = invoices;
        this.connections = connections;
        this.internalIds = internalIds;
        this.suppliers = suppliers;
        this.users = users;
    }

    /** Faktury dokumentów: najpierw przypięte, potem dopasowane po numerze (przyjęcia). */
    @Transactional(readOnly = true)
    public Map<UUID, List<LinkedKsefInvoice>> invoicesOf(UUID storeId, Collection<Document> page) {
        List<Document> linkable = page.stream().filter(document -> document.getType().canLinkKsefInvoice()).toList();
        if (linkable.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<LinkedKsefInvoice>> result = new LinkedHashMap<>();
        for (DocumentKsefInvoice link : links.findActiveByDocumentIds(linkable.stream().map(Document::getId).toList())) {
            result.computeIfAbsent(link.getDocument().getId(), id -> new ArrayList<>())
                    .add(toLinked(link.getInvoice(), false));
        }
        String nip = nip(storeId);
        List<String> numbers = linkable.stream()
                .filter(document -> document.getType() == DocumentType.PZ && document.getSupplierDocumentNumber() != null)
                .map(document -> upper(document.getSupplierDocumentNumber()))
                .distinct().toList();
        if (nip != null && !numbers.isEmpty()) {
            Set<String> own = ownInternalIds(storeId);
            Map<String, List<KsefInvoice>> byNumber = invoices.findByInvoiceNumbers(nip, numbers).stream()
                    .filter(invoice -> KsefInvoiceSearch.visibleInStore(invoice, own))
                    .collect(Collectors.groupingBy(invoice -> upper(invoice.getInvoiceNumber())));
            for (Document document : linkable) {
                if (document.getType() != DocumentType.PZ || document.getSupplierDocumentNumber() == null) {
                    continue;
                }
                List<LinkedKsefInvoice> current = result.computeIfAbsent(document.getId(), id -> new ArrayList<>());
                for (KsefInvoice invoice : byNumber.getOrDefault(upper(document.getSupplierDocumentNumber()), List.of())) {
                    if (current.stream().noneMatch(linked -> linked.id().equals(invoice.getId()))) {
                        current.add(toLinked(invoice, true));
                    }
                }
            }
        }
        return result;
    }

    /**
     * Jak {@link #invoicesOf}, a przy przyjęciach PZ dodatkowo faktury ich korekt od dostawcy ({@code corrections}:
     * korekty przyjęć z {@link DeliveryCorrectionService#correctionsOf}). Takie faktury mają {@code viaCorrection}
     * i nie liczą się do limitu jednej faktury przyjęcia.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<LinkedKsefInvoice>> invoicesWithCorrections(UUID storeId, Collection<Document> page,
            Map<UUID, List<DeliveryCorrection>> corrections) {
        Map<UUID, List<LinkedKsefInvoice>> result = new LinkedHashMap<>(invoicesOf(storeId, page));
        List<Document> correctionDocuments = corrections.values().stream().flatMap(List::stream)
                .map(DeliveryCorrection::getCorrection).toList();
        if (correctionDocuments.isEmpty()) {
            return result;
        }
        Map<UUID, List<LinkedKsefInvoice>> ofCorrections = invoicesOf(storeId, correctionDocuments);
        corrections.forEach((deliveryId, links) -> {
            for (DeliveryCorrection link : links) {
                for (LinkedKsefInvoice invoice : ofCorrections.getOrDefault(link.getCorrection().getId(), List.of())) {
                    List<LinkedKsefInvoice> current = result.computeIfAbsent(deliveryId, id -> new ArrayList<>());
                    if (current.stream().noneMatch(linked -> linked.id().equals(invoice.id()))) {
                        current.add(new LinkedKsefInvoice(invoice.id(), invoice.ksefNumber(), invoice.invoiceNumber(),
                                invoice.issueDate(), invoice.sellerName(), invoice.netAmount(), invoice.grossAmount(),
                                invoice.correction(), invoice.matchedByNumber(), link.getCorrection().getNumber()));
                    }
                }
            }
        });
        return result;
    }

    /**
     * Faktury do przypięcia dokumentu: widoczne w sklepie, z tekstem z wyszukiwarki, domyślnie z kwotą w zakresie.
     * Najpierw faktury dostawcy dokumentu, potem od najbliższej kwoty.
     */
    @Transactional(readOnly = true)
    public List<KsefInvoiceCandidate> invoiceCandidates(UUID storeId, String typeCode, UUID documentId, String query,
            boolean amountRange) {
        Document document = linkableDocument(storeId, typeCode, documentId);
        String nip = nip(storeId);
        if (nip == null) {
            throw ApiException.badRequest("Sklep nie jest połączony z KSeF");
        }
        BigDecimal value = abs(document.getPurchaseValue());
        Specification<KsefInvoice> matching = KsefInvoiceSearch.matching(storeId, nip, ownInternalIds(storeId),
                new KsefInvoiceSearch.Filter(query, null, null, KsefInvoiceSearch.Entered.ALL,
                        KsefInvoiceSearch.Kind.ALL, null, true));
        if (amountRange && value.signum() > 0) {
            BigDecimal[] range = range(value);
            matching = matching.and((root, q, cb) -> cb.between(cb.abs(root.<BigDecimal>get("netAmount")),
                    range[0], range[1]));
        }
        List<KsefInvoice> found = invoices.findAll(matching,
                PageRequest.of(0, SCANNED, Sort.by(Sort.Order.desc("issueDate").nullsLast()))).getContent();
        String supplierNip = supplierNips(storeId).get(document.getSupplierCode());
        Map<UUID, List<String>> linkedDocuments = documentNumbers(storeId, found);
        return found.stream()
                .map(invoice -> new KsefInvoiceCandidate(invoice.getId(), invoice.getKsefNumber(),
                        invoice.getInvoiceNumber(), invoice.getIssueDate(), invoice.getSellerName(),
                        invoice.getSellerNip(), invoice.getNetAmount(), invoice.getGrossAmount(),
                        isCorrection(invoice), supplierNip != null && supplierNip.equals(invoice.getSellerNip()),
                        difference(invoice.getNetAmount(), value),
                        linkedDocuments.getOrDefault(invoice.getId(), List.of())))
                .sorted(Comparator.comparing(KsefInvoiceCandidate::sameSupplier).reversed()
                        .thenComparing(KsefInvoiceCandidate::difference))
                .limit(CANDIDATES)
                .toList();
    }

    /** Dokumenty sklepu (przyjęcia, korekty od dostawcy) do przypięcia faktury - jak {@link #invoiceCandidates}. */
    @Transactional(readOnly = true)
    public List<DocumentCandidate> documentCandidates(UUID storeId, UUID invoiceId, String query, boolean amountRange) {
        KsefInvoice invoice = visibleInvoice(storeId, invoiceId);
        BigDecimal value = abs(invoice.getNetAmount());
        List<String> words = DocumentSearch.words(query);
        Specification<Document> matching = (root, q, cb) -> {
            List<jakarta.persistence.criteria.Predicate> all = new ArrayList<>();
            all.add(cb.equal(root.get("store").get("id"), storeId));
            all.add(root.get("type").in(DocumentType.PZ, DocumentType.WZ));
            for (String word : words) {
                String contains = "%" + word + "%";
                all.add(cb.or(
                        cb.like(cb.lower(root.get("number")), contains),
                        cb.like(cb.lower(root.get("supplierName")), contains),
                        cb.like(cb.lower(root.get("supplierCode")), contains),
                        cb.like(cb.lower(root.get("supplierDocumentNumber")), contains)));
            }
            if (amountRange && value.signum() > 0) {
                BigDecimal[] range = range(value);
                all.add(cb.between(cb.abs(root.<BigDecimal>get("purchaseValue")), range[0], range[1]));
            }
            return cb.and(all.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        List<Document> found = documents.findAll(matching,
                PageRequest.of(0, SCANNED, Sort.by(Sort.Order.desc("documentDate")))).getContent();
        Map<String, String> nips = supplierNips(storeId);
        // Przyjęcie z inną fakturą odpada: PZ ma najwyżej jedną.
        Map<UUID, List<LinkedKsefInvoice>> current = invoicesOf(storeId, found);
        return found.stream()
                .filter(document -> document.getType() != DocumentType.PZ
                        || current.getOrDefault(document.getId(), List.of()).stream()
                                .allMatch(linked -> linked.id().equals(invoice.getId())))
                .map(document -> new DocumentCandidate(document.getId(), document.getType().name(),
                        document.getType().singular(), document.getNumber(), document.getDocumentDate(),
                        document.getSupplierName(), document.getSupplierDocumentNumber(), document.getPurchaseValue(),
                        Objects.equals(nips.get(document.getSupplierCode()), invoice.getSellerNip()),
                        difference(document.getPurchaseValue(), value)))
                .sorted(Comparator.comparing(DocumentCandidate::sameSupplier).reversed()
                        .thenComparing(DocumentCandidate::difference))
                .limit(CANDIDATES)
                .toList();
    }

    /**
     * Propozycje automatycznego przypięcia: przyjęcia PZ bez faktury (przypiętej, po numerze ani spoza KSeF)
     * i niewprowadzone faktury (nie korekty) tego samego dostawcy, wystawione do {@link #AUTO_MATCH_DAYS} dni
     * przed albo po przyjęciu, z kwotą netto różną od wartości zakupu najwyżej o {@link #AUTO_MATCH_TOLERANCE}.
     * Pary są dobierane od najmniejszej różnicy kwot, potem najbliższej daty; każde przyjęcie i każda faktura
     * trafia najwyżej do jednej pary. Niczego nie zapisuje.
     */
    @Transactional(readOnly = true)
    public List<AutoInvoiceMatch> autoMatches(UUID storeId) {
        String nip = nip(storeId);
        if (nip == null) {
            throw ApiException.badRequest("Sklep nie jest połączony z KSeF");
        }
        Map<String, String> nips = supplierNips(storeId);
        List<Document> deliveries = documents.findAll(DocumentSearch.inStoreMatching(storeId, DocumentType.PZ, List.of())
                        .and(DocumentSearch.withInvoice(DocumentSearch.Presence.WITHOUT, nip))).stream()
                .filter(document -> document.getPurchaseValue() != null && document.getPurchaseValue().signum() > 0
                        && nips.containsKey(document.getSupplierCode()))
                .toList();
        if (deliveries.isEmpty()) {
            return List.of();
        }
        LocalDate from = deliveries.stream().map(document -> document.getDocumentDate().toLocalDate())
                .min(Comparator.naturalOrder()).orElseThrow().minusDays(AUTO_MATCH_DAYS);
        LocalDate to = deliveries.stream().map(document -> document.getDocumentDate().toLocalDate())
                .max(Comparator.naturalOrder()).orElseThrow().plusDays(AUTO_MATCH_DAYS);
        Set<String> sellerNips = deliveries.stream().map(document -> nips.get(document.getSupplierCode()))
                .collect(Collectors.toSet());
        Specification<KsefInvoice> free = KsefInvoiceSearch.matching(storeId, nip, ownInternalIds(storeId),
                new KsefInvoiceSearch.Filter(null, from, to, KsefInvoiceSearch.Entered.NOT_ENTERED,
                        KsefInvoiceSearch.Kind.INVOICES, null, false));
        Map<String, List<KsefInvoice>> bySeller = invoices
                .findAll(free.and((root, q, cb) -> root.get("sellerNip").in(sellerNips))).stream()
                .filter(invoice -> invoice.getNetAmount() != null && invoice.getIssueDate() != null)
                .collect(Collectors.groupingBy(KsefInvoice::getSellerNip));

        record Pair(Document delivery, KsefInvoice invoice, BigDecimal difference, long days) {
        }
        List<Pair> pairs = new ArrayList<>();
        for (Document delivery : deliveries) {
            LocalDate date = delivery.getDocumentDate().toLocalDate();
            for (KsefInvoice invoice : bySeller.getOrDefault(nips.get(delivery.getSupplierCode()), List.of())) {
                long days = Math.abs(ChronoUnit.DAYS.between(date, invoice.getIssueDate()));
                BigDecimal difference = difference(invoice.getNetAmount(), delivery.getPurchaseValue());
                if (days <= AUTO_MATCH_DAYS && difference.compareTo(AUTO_MATCH_TOLERANCE) <= 0) {
                    pairs.add(new Pair(delivery, invoice, difference, days));
                }
            }
        }
        pairs.sort(Comparator.comparing(Pair::difference).thenComparingLong(Pair::days));
        Set<UUID> usedDeliveries = new HashSet<>();
        Set<UUID> usedInvoices = new HashSet<>();
        List<AutoInvoiceMatch> result = new ArrayList<>();
        for (Pair pair : pairs) {
            if (usedDeliveries.contains(pair.delivery().getId()) || usedInvoices.contains(pair.invoice().getId())) {
                continue;
            }
            usedDeliveries.add(pair.delivery().getId());
            usedInvoices.add(pair.invoice().getId());
            Document delivery = pair.delivery();
            KsefInvoice invoice = pair.invoice();
            result.add(new AutoInvoiceMatch(delivery.getId(), delivery.getNumber(), delivery.getDocumentDate(),
                    delivery.getSupplierName(), delivery.getSupplierDocumentNumber(), delivery.getPurchaseValue(),
                    invoice.getId(), invoice.getKsefNumber(), invoice.getInvoiceNumber(), invoice.getIssueDate(),
                    invoice.getSellerName(), invoice.getNetAmount(), pair.difference()));
        }
        result.sort(Comparator.comparing(AutoInvoiceMatch::documentDate).reversed());
        return result;
    }

    /** Przypina zaakceptowane pary z {@link #autoMatches}; błąd którejkolwiek (np. przyjęcie ma już fakturę) wycofuje wszystkie. */
    @Transactional
    public int linkAll(UUID storeId, List<AutoInvoiceMatchesRequest.Pair> matches, UUID userId) {
        for (AutoInvoiceMatchesRequest.Pair pair : matches) {
            link(storeId, pair.documentId(), pair.invoiceId(), userId);
        }
        return matches.size();
    }

    /** Cofnięcie automatycznego przypięcia: odpina pary (znacznik), już odpięte pomija. */
    @Transactional
    public void unlinkAll(UUID storeId, List<AutoInvoiceMatchesRequest.Pair> matches, UUID userId) {
        for (AutoInvoiceMatchesRequest.Pair pair : matches) {
            documents.findHeader(storeId, pair.documentId())
                    .flatMap(document -> links.findActive(document.getId(), pair.invoiceId()))
                    .ifPresent(link -> link.unlink(userId != null ? users.getReferenceById(userId) : null));
        }
    }

    /**
     * Przypina fakturę do dokumentu; już przypięta zostaje bez zmian. Przyjęcie PZ ma najwyżej jedną fakturę
     * (przypiętą albo dopasowaną po numerze), korekta od dostawcy może mieć kilka.
     */
    @Transactional
    public void link(UUID storeId, UUID documentId, UUID invoiceId, UUID userId) {
        Document document = documents.findHeader(storeId, documentId)
                .filter(found -> found.getType().canLinkKsefInvoice())
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono przyjęcia ani korekty od dostawcy"));
        KsefInvoice invoice = visibleInvoice(storeId, invoiceId);
        if (document.getType() == DocumentType.PZ) {
            List<LinkedKsefInvoice> current = invoicesOf(storeId, List.of(document))
                    .getOrDefault(document.getId(), List.of());
            if (current.stream().anyMatch(linked -> linked.id().equals(invoice.getId()))) {
                return;
            }
            String existing = !current.isEmpty() ? current.getFirst().invoiceNumber()
                    : manualInvoices.findActiveByDocumentIds(List.of(document.getId())).stream()
                            .map(DocumentManualInvoice::getInvoiceNumber).findFirst().orElse(null);
            if (existing != null) {
                throw ApiException.conflict("Przyjęcie " + document.getNumber() + " ma już fakturę "
                        + existing + ". Do przyjęcia przypina się jedną fakturę.");
            }
        }
        if (links.findActive(document.getId(), invoice.getId()).isEmpty()) {
            links.save(new DocumentKsefInvoice(document, invoice, userId != null ? users.getReferenceById(userId) : null));
        }
    }

    /** Odpina fakturę (znacznik, wpis zostaje); ponowne przypięcie to „Przywróć”. */
    @Transactional
    public void unlink(UUID storeId, UUID documentId, UUID invoiceId, UUID userId) {
        documents.findHeader(storeId, documentId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu"));
        links.findActive(documentId, invoiceId)
                .orElseThrow(() -> ApiException.notFound("Ta faktura nie jest przypięta do dokumentu"))
                .unlink(userId != null ? users.getReferenceById(userId) : null);
    }

    public Document linkableDocument(UUID storeId, String typeCode, UUID documentId) {
        DocumentType type = DocumentType.fromCode(typeCode)
                .orElseThrow(() -> ApiException.notFound("Nieznany typ dokumentu"));
        Document document = documents.findHeader(storeId, type, documentId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu"));
        if (!type.canLinkKsefInvoice()) {
            throw ApiException.badRequest("Fakturę z KSeF przypina się do przyjęć i korekt od dostawcy");
        }
        return document;
    }

    private KsefInvoice visibleInvoice(UUID storeId, UUID invoiceId) {
        String nip = nip(storeId);
        KsefInvoice invoice = nip == null ? null : invoices.findByIdAndNip(invoiceId, nip).orElse(null);
        if (invoice == null || !KsefInvoiceSearch.visibleInStore(invoice, ownInternalIds(storeId))) {
            throw ApiException.notFound("Nie znaleziono faktury");
        }
        return invoice;
    }

    /** Numery dokumentów sklepu przy fakturach: przypiętych i przyjęć z numerem faktury. */
    private Map<UUID, List<String>> documentNumbers(UUID storeId, List<KsefInvoice> found) {
        if (found.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<String>> result = new LinkedHashMap<>();
        for (DocumentKsefInvoice link : links.findActiveByInvoiceIds(storeId,
                found.stream().map(KsefInvoice::getId).toList())) {
            result.computeIfAbsent(link.getInvoice().getId(), id -> new ArrayList<>())
                    .add(link.getDocument().getNumber());
        }
        Map<String, List<Document>> deliveries = documents.findDeliveriesBySupplierDocument(storeId,
                        found.stream().map(invoice -> upper(invoice.getInvoiceNumber())).distinct().toList())
                .stream().collect(Collectors.groupingBy(document -> upper(document.getSupplierDocumentNumber())));
        for (KsefInvoice invoice : found) {
            for (Document document : deliveries.getOrDefault(upper(invoice.getInvoiceNumber()), List.of())) {
                List<String> numbers = result.computeIfAbsent(invoice.getId(), id -> new ArrayList<>());
                if (!numbers.contains(document.getNumber())) {
                    numbers.add(document.getNumber());
                }
            }
        }
        return result;
    }

    /** Kod dostawcy z raportu → NIP z listy dostawców sklepu. */
    private Map<String, String> supplierNips(UUID storeId) {
        return suppliers.findImported(storeId).stream()
                .filter(supplier -> supplier.getNip() != null)
                .collect(Collectors.toMap(Supplier::getCode, Supplier::getNip, (first, second) -> first));
    }

    /** NIP firmy z połączenia KSeF sklepu; {@code null}, gdy sklep nie jest połączony. */
    @Transactional(readOnly = true)
    public String nip(UUID storeId) {
        return connections.findById(storeId).map(KsefConnection::getNip).orElse(null);
    }

    private Set<String> ownInternalIds(UUID storeId) {
        return internalIds.findActive(storeId).stream().map(KsefInternalId::getInternalId).collect(Collectors.toSet());
    }

    private static LinkedKsefInvoice toLinked(KsefInvoice invoice, boolean matchedByNumber) {
        return new LinkedKsefInvoice(invoice.getId(), invoice.getKsefNumber(), invoice.getInvoiceNumber(),
                invoice.getIssueDate(), invoice.getSellerName(), invoice.getNetAmount(), invoice.getGrossAmount(),
                isCorrection(invoice), matchedByNumber, null);
    }

    private static boolean isCorrection(KsefInvoice invoice) {
        return invoice.getInvoiceType() != null && invoice.getInvoiceType().startsWith("Kor");
    }

    /** Zakres ±{@link #AMOUNT_TOLERANCE_PERCENT}% wokół kwoty (bez znaku). */
    private static BigDecimal[] range(BigDecimal value) {
        BigDecimal tolerance = value.multiply(AMOUNT_TOLERANCE_PERCENT).movePointLeft(2);
        return new BigDecimal[] {value.subtract(tolerance), value.add(tolerance)};
    }

    private static BigDecimal difference(BigDecimal amount, BigDecimal value) {
        return abs(amount).subtract(value).abs();
    }

    private static BigDecimal abs(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.abs();
    }

    private static String upper(String value) {
        return value.toUpperCase(Locale.ROOT);
    }
}
