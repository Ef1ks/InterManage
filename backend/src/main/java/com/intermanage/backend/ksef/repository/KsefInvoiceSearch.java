package com.intermanage.backend.ksef.repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentKsefInvoice;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.repository.DocumentSearch;
import com.intermanage.backend.ksef.component.DeliveryAddress;
import com.intermanage.backend.ksef.entity.KsefBaseSupplierCode;
import com.intermanage.backend.ksef.entity.KsefConnection;
import com.intermanage.backend.ksef.entity.KsefInvoice;
import com.intermanage.backend.ksef.entity.KsefInvoiceEntry;
import com.intermanage.backend.ksef.entity.KsefSkippedSupplier;

import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Filtry listy faktur KSeF po stronie bazy. Faktury są wspólne dla firmy (NIP połączenia sklepu). Sklep bez IDWew
 * widzi wszystkie faktury firmy, sklep z IDWew - te ze swoimi IDWew i wszystkie bez IDWew (albo, na żądanie, tylko
 * ze swoimi). Faktury pominiętych dostawców tylko na żądanie. "Wprowadzona" = w którymkolwiek sklepie firmy (sklepy
 * z połączeniem KSeF z tym samym NIP-em) numer faktury jest numerem dokumentu dostawcy w przyjęciu PZ (bez względu na
 * wielkość liter), sklep przypiął do niej dokument albo oznaczył ją ręcznie - faktura wprowadzona w jednym sklepie
 * jest wprowadzona we wszystkich sklepach firmy.
 */
public final class KsefInvoiceSearch {

    /**
     * {@code internalId}: konkretny IDWew sklepu, {@link #OWN_INTERNAL_IDS} (tylko z IDWew sklepu),
     * {@link #WITHOUT_INTERNAL_ID} (tylko bez IDWew) albo {@code null} (wszystkie widoczne w sklepie).
     */
    public record Filter(String query, LocalDate from, LocalDate to, Entered entered, Kind kind, String internalId,
            boolean showSkipped, boolean myDeliveries, boolean quantityChanges) {

        public Filter(String query, LocalDate from, LocalDate to, Entered entered, Kind kind, String internalId,
                boolean showSkipped) {
            this(query, from, to, entered, kind, internalId, showSkipped, false, false);
        }
    }

    /**
     * Sklep, dla którego liczy się lista: jego IDWew, ulica z numerem z adresu sklepu ({@link DeliveryAddress#storeKey},
     * {@code null} bez adresu) i ulice wszystkich sklepów firmy (do rozpoznania faktur niczyich).
     */
    public record Scope(UUID storeId, String nip, Collection<String> ownInternalIds, String ownAddressKey,
            Collection<String> companyAddressKeys) {
    }

    public enum Entered {
        ALL, ENTERED, NOT_ENTERED
    }

    public enum Kind {
        ALL, INVOICES, CORRECTIONS
    }

    public static final String WITHOUT_INTERNAL_ID = "NONE";
    public static final String OWN_INTERNAL_IDS = "OWN";
    /**
     * Moje i nierozpoznane: faktury sklepu (jego IDWew, bez IDWew z adresem dostawy sklepu, z przyjęciem PZ w sklepie;
     * korekta też przez fakturę, którą koryguje) i wszystkie, których nie da się przypisać żadnemu sklepowi firmy
     * (np. korekta faktury sprzed KSeF bez PZ), żeby żadna nie zginęła.
     */
    public static final String MINE = "MINE";

    private static final Map<String, String> SORTABLE = Map.of(
            "issueDate", "issueDate",
            "number", "invoiceNumber",
            "seller", "sellerName",
            "net", "netAmount",
            "gross", "grossAmount");

    private static final String POLISH = "ąćęłńóśźż";
    private static final String PLAIN = "acelnoszz";

    private KsefInvoiceSearch() {
    }

    /** Domyślnie najnowsze na górze; remis po numerze KSeF. */
    public static Sort sort(String column, String direction) {
        String field = SORTABLE.getOrDefault(column, "issueDate");
        Sort.Order order = "asc".equalsIgnoreCase(direction) ? Sort.Order.asc(field) : Sort.Order.desc(field);
        return Sort.by(order.nullsLast(), Sort.Order.desc("ksefNumber"));
    }

    public static Specification<KsefInvoice> matching(UUID storeId, String nip, Collection<String> ownInternalIds,
            Filter filter) {
        return matching(new Scope(storeId, nip, ownInternalIds, null, List.of()), filter);
    }

    public static Specification<KsefInvoice> matching(Scope scope, Filter filter) {
        UUID storeId = scope.storeId();
        String nip = scope.nip();
        Collection<String> ownInternalIds = scope.ownInternalIds();
        return (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();
            all.add(cb.equal(root.get("nip"), nip));
            if (MINE.equals(filter.internalId())) {
                all.add(visibleInStore(root, query, cb, ownInternalIds, null));
                all.add(mineOrUnknown(root, query, cb, scope));
            } else {
                all.add(visibleInStore(root, query, cb, ownInternalIds, filter.internalId()));
            }
            if (filter.myDeliveries()) {
                all.add(toMyDeliveries(root, query, cb, scope));
            }
            if (filter.quantityChanges()) {
                // Bez korekt tylko na kwoty; korekta jeszcze nieodczytana zostaje, żeby nie zginęła.
                all.add(cb.or(cb.notLike(root.get("invoiceType"), "Kor%"), cb.isNull(root.get("quantityChange")),
                        cb.isTrue(root.get("quantityChange"))));
            }
            if (!filter.showSkipped()) {
                all.add(cb.not(cb.exists(skippedSupplier(root, query, cb, storeId))));
            }
            if (filter.from() != null) {
                all.add(cb.greaterThanOrEqualTo(root.get("issueDate"), filter.from()));
            }
            if (filter.to() != null) {
                all.add(cb.lessThanOrEqualTo(root.get("issueDate"), filter.to()));
            }
            Predicate correction = cb.like(root.get("invoiceType"), "Kor%");
            if (filter.kind() == Kind.INVOICES) {
                all.add(cb.not(correction));
            } else if (filter.kind() == Kind.CORRECTIONS) {
                all.add(correction);
            }
            if (filter.entered() != Entered.ALL) {
                Predicate entered = cb.or(cb.exists(manualEntry(root, query, cb, nip)),
                        hasDocument(root, query, cb, nip));
                all.add(filter.entered() == Entered.ENTERED ? entered : cb.not(entered));
            }
            for (String word : DocumentSearch.words(filter.query())) {
                String contains = "%" + word + "%";
                all.add(cb.or(
                        cb.like(plain(cb, root.get("invoiceNumber")), contains),
                        cb.like(plain(cb, root.get("sellerName")), contains),
                        cb.like(root.get("sellerNip"), contains),
                        cb.like(plain(cb, root.get("ksefNumber")), contains),
                        cb.like(plain(cb, root.get("baseSupplierInvoice")), contains),
                        cb.like(root.get("baseSupplierCode"), contains),
                        cb.exists(baseSupplierNamed(root, query, cb, contains))));
            }
            return cb.and(all.toArray(Predicate[]::new));
        };
    }

    /**
     * Refaktura bazy, której kod dostawcy w bazie firma przypisała dostawcy pasującemu do słowa (nazwa albo NIP):
     * po wpisaniu "artek" są też faktury Artka wystawione przez bazę.
     */
    private static Subquery<Integer> baseSupplierNamed(Root<KsefInvoice> root, CriteriaQuery<?> query,
            CriteriaBuilder cb, String contains) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<KsefBaseSupplierCode> code = subquery.from(KsefBaseSupplierCode.class);
        subquery.select(cb.literal(1)).where(
                cb.isNotNull(root.get("baseSupplierCode")),
                cb.equal(code.get("nip"), root.get("nip")),
                cb.isNull(code.get("deletedAt")),
                cb.equal(code.get("code"),
                        cb.function("ltrim", String.class, root.get("baseSupplierCode"), cb.literal("0"))),
                cb.or(cb.like(plain(cb, code.get("name")), contains),
                        cb.like(cb.coalesce(code.<String>get("supplierNip"), ""), contains)));
        return subquery;
    }

    /**
     * Sklep bez IDWew: wszystkie faktury firmy. Sklep z IDWew: bez IDWew albo z jego IDWew; zawężenie do jego IDWew
     * (wszystkich albo jednego) albo do faktur bez IDWew.
     */
    private static Predicate visibleInStore(Root<KsefInvoice> root, CriteriaQuery<?> query, CriteriaBuilder cb,
            Collection<String> own, String selected) {
        Predicate without = cb.not(cb.exists(internalIds(root, query, cb, null)));
        if (WITHOUT_INTERNAL_ID.equals(selected)) {
            return without;
        }
        if (own.isEmpty()) {
            return selected == null ? cb.conjunction() : cb.disjunction();
        }
        Predicate withOwn = cb.exists(internalIds(root, query, cb, own));
        if (OWN_INTERNAL_IDS.equals(selected)) {
            return withOwn;
        }
        if (selected != null) {
            return own.contains(selected) ? cb.exists(internalIds(root, query, cb, List.of(selected))) : cb.disjunction();
        }
        return cb.or(without, withOwn);
    }

    /** To samo co {@link #visibleInStore} bez zawężeń, dla pojedynczej faktury. */
    public static boolean visibleInStore(KsefInvoice invoice, Collection<String> own) {
        return own.isEmpty() || invoice.getInternalIds().isEmpty()
                || invoice.getInternalIds().stream().anyMatch(ref -> own.contains(ref.internalId()));
    }

    /** IDWew faktury - wszystkie ({@code among} = null) albo spośród podanych. */
    private static Subquery<Integer> internalIds(Root<KsefInvoice> root, CriteriaQuery<?> query, CriteriaBuilder cb,
            Collection<String> among) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<KsefInvoice> invoice = subquery.from(KsefInvoice.class);
        Join<KsefInvoice, KsefInvoice.InternalIdRef> ids = invoice.join("internalIds");
        Predicate same = cb.equal(invoice, root);
        subquery.select(cb.literal(1))
                .where(among == null ? same : cb.and(same, ids.get("internalId").in(among)));
        return subquery;
    }

    private static Subquery<Integer> skippedSupplier(Root<KsefInvoice> root, CriteriaQuery<?> query,
            CriteriaBuilder cb, UUID storeId) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<KsefSkippedSupplier> skipped = subquery.from(KsefSkippedSupplier.class);
        subquery.select(cb.literal(1)).where(
                cb.equal(skipped.get("store").get("id"), storeId),
                cb.equal(skipped.get("nip"), root.get("sellerNip")),
                cb.isNull(skipped.get("removedAt")));
        return subquery;
    }

    /**
     * Faktura ma dokument z raportu w którymś sklepie firmy: przyjęcie PZ z jej numerem albo przypięty dokument
     * (przyjęcie, korekta od dostawcy). Ręczne oznaczenie "wprowadzona" się tu nie liczy.
     */
    public static Predicate hasDocument(Root<KsefInvoice> root, CriteriaQuery<?> query, CriteriaBuilder cb,
            String nip) {
        return cb.or(cb.exists(delivery(root, query, cb, nip)), cb.exists(linkedDocument(root, query, cb, nip)));
    }

    /** Sklepy firmy: z połączeniem KSeF z tym NIP-em. */
    private static Subquery<UUID> companyStores(CommonAbstractCriteria query, CriteriaBuilder cb, String nip) {
        Subquery<UUID> subquery = query.subquery(UUID.class);
        Root<KsefConnection> connection = subquery.from(KsefConnection.class);
        subquery.select(connection.get("storeId")).where(cb.equal(connection.get("nip"), nip));
        return subquery;
    }

    private static Subquery<Integer> manualEntry(Root<KsefInvoice> root, CriteriaQuery<?> query, CriteriaBuilder cb,
            String nip) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<KsefInvoiceEntry> entry = subquery.from(KsefInvoiceEntry.class);
        subquery.select(cb.literal(1)).where(
                entry.get("id").get("storeId").in(companyStores(query, cb, nip)),
                cb.equal(entry.get("id").get("invoiceId"), root.get("id")));
        return subquery;
    }

    private static Subquery<Integer> delivery(Root<KsefInvoice> root, CriteriaQuery<?> query, CriteriaBuilder cb,
            String nip) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<Document> document = subquery.from(Document.class);
        subquery.select(cb.literal(1)).where(
                document.get("store").get("id").in(companyStores(query, cb, nip)),
                cb.equal(document.get("type"), DocumentType.PZ),
                cb.isNull(document.get("errorMarkedAt")),
                cb.equal(cb.upper(document.get("supplierDocumentNumber")), cb.upper(root.get("invoiceNumber"))));
        return subquery;
    }

    /** Dokument sklepu firmy (przyjęcie, korekta od dostawcy) przypięty do faktury ręcznie i nieodpięty. */
    private static Subquery<Integer> linkedDocument(Root<KsefInvoice> root, CriteriaQuery<?> query,
            CriteriaBuilder cb, String nip) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<DocumentKsefInvoice> link = subquery.from(DocumentKsefInvoice.class);
        subquery.select(cb.literal(1)).where(
                cb.equal(link.get("invoice"), root),
                link.get("document").get("store").get("id").in(companyStores(query, cb, nip)),
                cb.isNull(link.get("document").get("errorMarkedAt")),
                cb.isNull(link.get("unlinkedAt")));
        return subquery;
    }

    /** Warunek na fakturze ({@code invoice}) budowany w podanym zapytaniu (główne albo podzapytanie). */
    private interface InvoiceCondition {
        Predicate on(Path<KsefInvoice> invoice, CommonAbstractCriteria query);
    }

    /** {@link #MINE}: moja (wprost albo przez fakturę korygowaną) albo niczyja. */
    private static Predicate mineOrUnknown(Root<KsefInvoice> root, CriteriaQuery<?> query, CriteriaBuilder cb,
            Scope scope) {
        InvoiceCondition mine = (invoice, q) -> mineDirect(invoice, q, cb, scope);
        InvoiceCondition assigned = (invoice, q) -> assignedDirect(invoice, q, cb, scope);
        Predicate correctedMine = cb.or(
                cb.exists(correctedMatching(root, query, cb, scope.nip(), mine)),
                cb.exists(correctedDelivery(root, query, cb, scope.storeId(), null)));
        Predicate unknown = cb.and(
                cb.not(assignedDirect(root, query, cb, scope)),
                cb.not(cb.exists(correctedMatching(root, query, cb, scope.nip(), assigned))),
                cb.not(cb.exists(correctedDelivery(root, query, cb, null, scope.nip()))));
        return cb.or(mineDirect(root, query, cb, scope), correctedMine, unknown);
    }

    /**
     * Do moich przyjęć: faktura z przyjęciem PZ (albo przypiętym dokumentem) w sklepie, a korekta - gdy takie ma
     * faktura, którą koryguje (także faktura sprzed KSeF: po numerze w PZ).
     */
    private static Predicate toMyDeliveries(Root<KsefInvoice> root, CriteriaQuery<?> query, CriteriaBuilder cb,
            Scope scope) {
        InvoiceCondition delivered = (invoice, q) -> documentInStore(invoice, q, cb, scope.storeId());
        return cb.or(
                documentInStore(root, query, cb, scope.storeId()),
                cb.exists(correctedDelivery(root, query, cb, scope.storeId(), null)),
                cb.exists(correctedMatching(root, query, cb, scope.nip(), delivered)));
    }

    /** Faktura sklepu sama z siebie: jego IDWew, bez IDWew z jego adresem dostawy albo z PZ w sklepie. */
    private static Predicate mineDirect(Path<KsefInvoice> invoice, CommonAbstractCriteria query, CriteriaBuilder cb,
            Scope scope) {
        List<Predicate> any = new ArrayList<>();
        if (!scope.ownInternalIds().isEmpty()) {
            any.add(cb.exists(internalIdsOf(invoice, query, cb, scope.ownInternalIds())));
        }
        if (scope.ownAddressKey() != null) {
            any.add(cb.and(cb.not(cb.exists(internalIdsOf(invoice, query, cb, null))),
                    addressOf(invoice, cb, scope.ownAddressKey())));
        }
        any.add(documentInStore(invoice, query, cb, scope.storeId()));
        return cb.or(any.toArray(Predicate[]::new));
    }

    /** Faktura przypisana któremuś sklepowi firmy: ma IDWew, adres dostawy któregoś sklepu albo dokument. */
    private static Predicate assignedDirect(Path<KsefInvoice> invoice, CommonAbstractCriteria query,
            CriteriaBuilder cb, Scope scope) {
        List<Predicate> any = new ArrayList<>();
        any.add(cb.exists(internalIdsOf(invoice, query, cb, null)));
        for (String key : scope.companyAddressKeys()) {
            any.add(addressOf(invoice, cb, key));
        }
        any.add(cb.exists(documentOf(invoice, query, cb, null, scope.nip())));
        return cb.or(any.toArray(Predicate[]::new));
    }

    private static Predicate addressOf(Path<KsefInvoice> invoice, CriteriaBuilder cb, String storeKey) {
        // Bez adresu "" zamiast null: inaczej not(...) przy fakturze niczyjej dałoby null i ją odrzuciło.
        Expression<String> address = cb.coalesce(invoice.<String>get("deliveryAddress"), "");
        return cb.like(address, "%" + DeliveryAddress.likeFragment(storeKey) + "%");
    }

    private static Predicate documentInStore(Path<KsefInvoice> invoice, CommonAbstractCriteria query,
            CriteriaBuilder cb, UUID storeId) {
        return cb.exists(documentOf(invoice, query, cb, storeId, null));
    }

    /**
     * Przyjęcie PZ z numerem faktury albo dokument przypięty do niej - w sklepie {@code storeId} albo (gdy null)
     * w którymkolwiek sklepie firmy {@code nip}.
     */
    private static Subquery<Integer> documentOf(Path<KsefInvoice> invoice, CommonAbstractCriteria query,
            CriteriaBuilder cb, UUID storeId, String nip) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<KsefInvoice> same = subquery.from(KsefInvoice.class);
        Subquery<Integer> delivery = subquery.subquery(Integer.class);
        Root<Document> document = delivery.from(Document.class);
        delivery.select(cb.literal(1)).where(
                storeOf(document.get("store").get("id"), delivery, cb, storeId, nip),
                cb.equal(document.get("type"), DocumentType.PZ),
                cb.isNull(document.get("errorMarkedAt")),
                cb.equal(cb.upper(document.get("supplierDocumentNumber")), cb.upper(same.get("invoiceNumber"))));
        Subquery<Integer> linked = subquery.subquery(Integer.class);
        Root<DocumentKsefInvoice> link = linked.from(DocumentKsefInvoice.class);
        linked.select(cb.literal(1)).where(
                cb.equal(link.get("invoice"), same),
                storeOf(link.get("document").get("store").get("id"), linked, cb, storeId, nip),
                cb.isNull(link.get("document").get("errorMarkedAt")),
                cb.isNull(link.get("unlinkedAt")));
        subquery.select(cb.literal(1)).where(cb.equal(same, invoice),
                cb.or(cb.exists(delivery), cb.exists(linked)));
        return subquery;
    }

    private static Predicate storeOf(Path<UUID> store, CommonAbstractCriteria query, CriteriaBuilder cb,
            UUID storeId, String nip) {
        return storeId != null ? cb.equal(store, storeId) : store.in(companyStores(query, cb, nip));
    }

    /** IDWew faktury - wszystkie ({@code among} = null) albo spośród podanych. */
    private static Subquery<Integer> internalIdsOf(Path<KsefInvoice> invoice, CommonAbstractCriteria query,
            CriteriaBuilder cb, Collection<String> among) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<KsefInvoice> same = subquery.from(KsefInvoice.class);
        Join<KsefInvoice, KsefInvoice.InternalIdRef> ids = same.join("internalIds");
        Predicate equal = cb.equal(same, invoice);
        subquery.select(cb.literal(1)).where(among == null ? equal : cb.and(equal, ids.get("internalId").in(among)));
        return subquery;
    }

    /**
     * Korekta ({@code root}) koryguje fakturę firmy spełniającą {@code condition}: po numerze KSeF faktury
     * korygowanej albo po NIP-ie sprzedawcy i numerze (bez względu na wielkość liter).
     */
    private static Subquery<Integer> correctedMatching(Root<KsefInvoice> root, CriteriaQuery<?> query,
            CriteriaBuilder cb, String nip, InvoiceCondition condition) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<KsefInvoice> correction = subquery.from(KsefInvoice.class);
        Join<KsefInvoice, KsefInvoice.CorrectedRef> ref = correction.join("correctedInvoices");
        // Dwa osobne podzapytania zamiast "numer KSeF albo NIP i numer": z OR baza nie bierze indeksów i przegląda
        // wszystkie faktury firmy dla każdej korekty. Po NIP-ie i numerze tylko faktury sprzed KSeF (bez numeru KSeF).
        Subquery<Integer> byKsefNumber = subquery.subquery(Integer.class);
        Root<KsefInvoice> found = byKsefNumber.from(KsefInvoice.class);
        byKsefNumber.select(cb.literal(1)).where(
                cb.equal(found.get("nip"), nip),
                cb.equal(found.get("ksefNumber"), ref.get("ksefNumber")),
                condition.on(found, byKsefNumber));
        Subquery<Integer> byNumber = subquery.subquery(Integer.class);
        Root<KsefInvoice> same = byNumber.from(KsefInvoice.class);
        byNumber.select(cb.literal(1)).where(
                cb.isNull(ref.get("ksefNumber")),
                cb.equal(same.get("nip"), nip),
                cb.equal(same.get("sellerNip"), correction.get("sellerNip")),
                cb.equal(cb.upper(same.get("invoiceNumber")), cb.upper(ref.get("invoiceNumber"))),
                condition.on(same, byNumber));
        subquery.select(cb.literal(1)).where(cb.equal(correction, root),
                cb.or(cb.exists(byKsefNumber), cb.exists(byNumber)));
        return subquery;
    }

    /**
     * Korekta ({@code root}) koryguje fakturę, której numer jest numerem dokumentu dostawcy w przyjęciu PZ - w sklepie
     * {@code storeId} albo (gdy null) w sklepie firmy {@code nip}. Działa też dla faktur sprzed KSeF.
     */
    private static Subquery<Integer> correctedDelivery(Root<KsefInvoice> root, CriteriaQuery<?> query,
            CriteriaBuilder cb, UUID storeId, String nip) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<KsefInvoice> correction = subquery.from(KsefInvoice.class);
        Join<KsefInvoice, KsefInvoice.CorrectedRef> ref = correction.join("correctedInvoices");
        Subquery<Integer> delivery = subquery.subquery(Integer.class);
        Root<Document> document = delivery.from(Document.class);
        delivery.select(cb.literal(1)).where(
                storeOf(document.get("store").get("id"), delivery, cb, storeId, nip),
                cb.equal(document.get("type"), DocumentType.PZ),
                cb.isNull(document.get("errorMarkedAt")),
                cb.equal(cb.upper(document.get("supplierDocumentNumber")), cb.upper(ref.get("invoiceNumber"))));
        subquery.select(cb.literal(1)).where(cb.equal(correction, root), cb.exists(delivery));
        return subquery;
    }

    private static Expression<String> plain(CriteriaBuilder cb, Expression<String> value) {
        return cb.function("translate", String.class, cb.lower(value), cb.literal(POLISH), cb.literal(PLAIN));
    }
}
